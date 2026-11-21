package com.streamflixreborn.streamflix.desktop.player

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.torrent.TorrentEngine
import com.streamflixreborn.streamflix.torrent.TorrentSpec
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A loopback HTTP server between VLC and the stream hosts. VLC can only send a Referer and a
 * User-Agent; many hosts also want Origin, cookies or custom headers, so every request goes
 * through here with the server's headers added. Playlists are rewritten so their segments,
 * keys and renditions come back through the proxy too; data: playlists (VixSrc) are served
 * from memory.
 */
object StreamProxy {
    private const val TAG = "StreamProxy"

    private class Session(
        val headers: Map<String, String>,
        val data: ByteArray? = null,
        val dataBase: String? = null,
        val client: okhttp3.OkHttpClient = NetworkClient.default,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val torrents = ConcurrentHashMap<String, TorrentSpec>()

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 128).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r, "stream-proxy").apply { isDaemon = true } }
            createContext("/") { ex -> runCatching { handle(ex) }.onFailure { e -> if (e !is IOException) Log.w(TAG, "request failed", e); runCatching { ex.close() } } }
            start()
        }
    }

    private val base get() = "http://127.0.0.1:${server.address.port}"

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
    private fun unb64(s: String) = String(Base64.getUrlDecoder().decode(s))

    /** The URL VLC should open for [video]. */
    /**
     * [isolated] gives the stream its own connection pool and request queue, so a background
     * 4K test can't starve the stream that is playing.
     */
    fun register(video: Video, isolated: Boolean = false): String {
        val id = UUID.randomUUID().toString().take(8)
        val headers = video.headers.orEmpty()
        val client = if (isolated) NetworkClient.default.newBuilder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 })
            .connectionPool(okhttp3.ConnectionPool())
            .build() else NetworkClient.default
        val src = video.source
        TorrentSpec.parse(src)?.let { return registerTorrent(it) }
        return when {
            src.startsWith("data:") -> {
                val comma = src.indexOf(',')
                val meta = src.substring(5, comma)
                val payload = src.substring(comma + 1)
                val bytes = if (meta.endsWith(";base64")) Base64.getMimeDecoder().decode(payload) else java.net.URLDecoder.decode(payload, Charsets.UTF_8).toByteArray()
                sessions[id] = Session(headers, data = bytes, dataBase = headers["Referer"], client = client)
                "$base/d/$id/index.m3u8"
            }
            else -> {
                sessions[id] = Session(headers, client = client)
                proxied(id, src)
            }
        }
    }

    /** A torrent file served with byte ranges from the torrent engine, as it downloads. */
    private fun registerTorrent(spec: TorrentSpec): String {
        val id = UUID.randomUUID().toString().take(8)
        torrents[id] = spec
        val ext = spec.name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{2,4}")) } ?: "mkv"
        return "$base/t/$id/video.$ext"
    }

    /** Subtitle files sometimes need the same headers. */
    fun registerSubtitle(video: Video, url: String): String {
        if (!url.startsWith("http")) return url
        val id = UUID.randomUUID().toString().take(8)
        sessions[id] = Session(video.headers.orEmpty())
        return proxied(id, url)
    }

    fun release(url: String?) {
        val id = url?.removePrefix(base)?.split('/')?.getOrNull(2) ?: return
        sessions.remove(id)
        torrents.remove(id)
    }

    private fun proxied(id: String, url: String): String {
        // Keep the file extension visible at the end so VLC picks the right demuxer.
        val ext = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").take(5).lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
        return "$base/p/$id/${b64(url)}" + (ext?.let { "/f.$it" } ?: "")
    }

    private fun handle(ex: HttpExchange) {
        val parts = ex.requestURI.rawPath.split('/').filter { it.isNotEmpty() }
        val kind = parts.getOrNull(0)
        if (kind == "t") {
            val spec = parts.getOrNull(1)?.let(torrents::get)
            if (spec == null) { ex.sendResponseHeaders(404, -1); ex.close() } else serveTorrent(ex, spec)
            return
        }
        val session = parts.getOrNull(1)?.let(sessions::get)
        if (session == null) {
            ex.sendResponseHeaders(404, -1)
            ex.close()
            return
        }
        val id = parts[1]
        when (kind) {
            "p" -> proxy(ex, id, session, unb64(parts[2]))
            "d" -> {
                val body = rewritePlaylist(String(session.data!!), session.dataBase ?: "https://localhost/", id).toByteArray()
                ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            else -> { ex.sendResponseHeaders(404, -1); ex.close() }
        }
    }

    private fun serveTorrent(ex: HttpExchange, spec: TorrentSpec) {
        val reader = try {
            TorrentEngine.open(spec)
        } catch (e: IOException) {
            Log.w(TAG, "torrent unavailable: ${e.message}")
            ex.sendResponseHeaders(503, -1)
            ex.close()
            return
        }
        try {
            val size = reader.fileSize
            val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')
            val start = range?.getOrNull(0)?.toLongOrNull()?.coerceIn(0, maxOf(0, size - 1)) ?: 0L
            val end = range?.getOrNull(1)?.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            val partial = range != null
            val type = when (spec.name?.substringAfterLast('.', "")?.lowercase()) {
                "mp4", "m4v" -> "video/mp4"
                "avi" -> "video/x-msvideo"
                "webm" -> "video/webm"
                else -> "video/x-matroska"
            }
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.responseHeaders.add("Content-Type", type)
            if (partial) ex.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
            val length = end - start + 1
            if (ex.requestMethod == "HEAD") {
                ex.responseHeaders.add("Content-Length", size.toString())
                ex.sendResponseHeaders(if (partial) 206 else 200, -1)
                ex.close()
                return
            }
            ex.sendResponseHeaders(if (partial) 206 else 200, length)
            ex.responseBody.use { out ->
                val buf = ByteArray(256 * 1024)
                var pos = start
                while (pos <= end) {
                    val n = reader.read(pos, buf, 0, minOf(buf.size.toLong(), end - pos + 1).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    pos += n
                }
            }
        } finally {
            reader.close()
        }
    }

    private fun isPlaylistUrl(url: String) = url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") }

    private fun proxy(ex: HttpExchange, id: String, session: Session, url: String) {
        val builder = Request.Builder().url(url)
        session.headers.forEach { (k, v) -> if (!k.equals("Range", true) && !k.equals("Host", true)) builder.header(k, v) }
        if (session.headers.keys.none { it.equals("User-Agent", true) }) builder.header("User-Agent", NetworkClient.USER_AGENT)
        builder.header("Accept", "*/*")
        builder.header("Sec-Fetch-Mode", "cors")
        ex.requestHeaders.getFirst("Range")?.let { builder.header("Range", it) }
        if (ex.requestMethod == "HEAD") builder.head()

        session.client.newCall(builder.build()).execute().use { response ->
            val type = response.header("Content-Type").orEmpty()
            val body = response.body
            val looksPlaylist = isPlaylistUrl(url) || type.contains("mpegurl", true)
            if (response.isSuccessful && body != null && (looksPlaylist || (type.startsWith("text/") && peekPlaylist(response)))) {
                val text = body.string()
                if (text.trimStart().startsWith("#EXTM3U")) {
                    val out = rewritePlaylist(text, response.request.url.toString(), id).toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                    ex.sendResponseHeaders(200, out.size.toLong())
                    ex.responseBody.use { it.write(out) }
                    return
                }
                val out = text.toByteArray()
                ex.responseHeaders.add("Content-Type", type.ifEmpty { "text/plain" })
                ex.sendResponseHeaders(response.code, out.size.toLong())
                ex.responseBody.use { it.write(out) }
                return
            }
            for (h in listOf("Content-Type", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")) {
                response.header(h)?.let { ex.responseHeaders.add(h, it) }
            }
            // Some CDNs disguise TS segments as images; tell VLC what they really are.
            if (type.startsWith("image/") && url.contains(".ts", true)) ex.responseHeaders.set("Content-Type", "video/mp2t")
            val length = response.header("Content-Length")?.toLongOrNull()
            if (ex.requestMethod == "HEAD" || body == null) {
                ex.sendResponseHeaders(response.code, -1)
                ex.close()
                return
            }
            ex.sendResponseHeaders(response.code, length?.takeIf { it > 0 } ?: 0)
            ex.responseBody.use { out -> body.byteStream().use { it.copyTo(out, 256 * 1024) } }
        }
    }

    private fun peekPlaylist(response: okhttp3.Response): Boolean =
        runCatching { response.peekBody(64).string().trimStart().startsWith("#EXTM3U") }.getOrDefault(false)

    private val uriAttr = Regex("""URI="([^"]+)"""")

    private fun rewritePlaylist(text: String, baseUrl: String, id: String): String {
        val base = baseUrl.toHttpUrlOrNull()
        fun abs(u: String): String = when {
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("data:") -> u
            base != null -> base.resolve(u)?.toString() ?: u
            else -> u
        }
        return text.lineSequence().joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> raw
                line.startsWith("#") -> uriAttr.replace(raw) { m ->
                    val target = abs(m.groupValues[1])
                    if (target.startsWith("data:")) m.value else "URI=\"${proxied(id, target)}\""
                }
                else -> proxied(id, abs(line))
            }
        }
    }
}
