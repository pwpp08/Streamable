package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.utils.health.HlsPlaylistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Reads the real picture size of a direct video link by looking at its header instead of trusting
 * the label an addon gave it: the track table of a Matroska file, the `tkhd` box of an MP4, or the
 * RESOLUTION of an HLS master playlist. A few hundred KB of the file is enough. Addons sometimes
 * call a 720p file "1080p"; this is what tells them apart.
 */
object ContainerProbe {

    private const val HEAD_BYTES = 256 * 1024
    private const val TAIL_BYTES = 1024 * 1024

    private val cache = ConcurrentHashMap<String, Int>()

    private val probeClient: OkHttpClient by lazy {
        NetworkClient.default.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    }

    /** Quality class (2160, 1080, 720 ...) of the video at [url], or null when it can't be read in time. */
    suspend fun height(
        url: String,
        headers: Map<String, String>?,
        mime: String?,
        timeoutMs: Long = 4_500L,
    ): Int? {
        cache[url]?.let { return it }
        val found = withTimeoutOrNull(timeoutMs) {
            runCatching { runInterruptible(Dispatchers.IO) { measure(url, headers, mime) } }.getOrNull()
        }
        if (found != null) cache[url] = found
        return found
    }

    private fun request(url: String, headers: Map<String, String>?, range: String?): Request {
        val builder = Request.Builder().url(url)
        headers?.forEach { (name, value) -> runCatching { builder.header(name, value) } }
        if (range != null) builder.header("Range", range)
        return builder.build()
    }

    private fun measure(url: String, headers: Map<String, String>?, mime: String?): Int? {
        val hls = mime?.contains("mpegurl", ignoreCase = true) == true ||
            url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
            "/hls/" in url
        if (hls) {
            val text = probeClient.newCall(request(url, headers, null)).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.source()?.let { it.request(64 * 1024L); it.buffer.clone().readUtf8() }.orEmpty()
            }
            return HlsPlaylistParser.maxHeight(text)
        }
        var total: Long? = null
        val head = probeClient.newCall(request(url, headers, "bytes=0-${HEAD_BYTES - 1}")).execute().use { response ->
            if (!response.isSuccessful) return null
            if (response.code == 206) {
                total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
            }
            readUpTo(response, HEAD_BYTES)
        }
        pictureHeight(head)?.let { return it }
        // An MP4 written without "fast start" keeps its header at the very end of the file.
        val size = total ?: return null
        if (size <= HEAD_BYTES) return null
        val from = maxOf(0L, size - TAIL_BYTES)
        val tail = probeClient.newCall(request(url, headers, "bytes=$from-${size - 1}")).execute().use { response ->
            if (response.code != 206) return null
            readUpTo(response, TAIL_BYTES)
        }
        return mp4Height(tail)
    }

    private fun readUpTo(response: okhttp3.Response, limit: Int): ByteArray {
        val source = response.body?.source() ?: return ByteArray(0)
        source.request(limit.toLong())
        val available = minOf(limit.toLong(), source.buffer.size)
        return source.buffer.readByteArray(available)
    }

    /** Picture quality class from the start of a Matroska or MP4 file; null when it isn't there. */
    fun pictureHeight(data: ByteArray): Int? =
        if (data.size > 4 && (data[0].toInt() and 0xFF) == 0x1A && (data[1].toInt() and 0xFF) == 0x45) mkvHeight(data)
        else mp4Height(data)

    // ---- Matroska ------------------------------------------------------------------------------

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_VIDEO = 0xE0L
    private const val ID_PIXEL_WIDTH = 0xB0L
    private const val ID_PIXEL_HEIGHT = 0xBAL
    private const val ID_CLUSTER = 0x1F43B675L

    fun mkvHeight(d: ByteArray): Int? {
        var width = 0
        var height = 0
        fun walk(from: Int, to: Int): Boolean {
            var pos = from
            while (pos < to) {
                val idLen = vintLength(d[pos])
                if (idLen == 0 || pos + idLen >= d.size) return false
                var id = 0L
                for (k in 0 until idLen) id = (id shl 8) or (d[pos + k].toLong() and 0xFF)
                val sizePos = pos + idLen
                val sizeLen = vintLength(d[sizePos])
                if (sizeLen == 0 || sizePos + sizeLen > d.size) return false
                var size = (d[sizePos].toLong() and 0xFF) and ((1L shl (8 - sizeLen)) - 1)
                var unknown = size == (1L shl (8 - sizeLen)) - 1
                for (k in 1 until sizeLen) {
                    val b = d[sizePos + k].toLong() and 0xFF
                    size = (size shl 8) or b
                    if (b != 0xFFL) unknown = false
                }
                val body = sizePos + sizeLen
                val end = if (unknown) to else minOf(to.toLong(), body + size).toInt()
                when (id) {
                    ID_CLUSTER -> return true
                    ID_SEGMENT, ID_TRACKS, ID_TRACK_ENTRY, ID_VIDEO -> if (walk(body, end)) return true
                    ID_PIXEL_WIDTH -> width = uint(d, body, end)
                    ID_PIXEL_HEIGHT -> height = uint(d, body, end)
                }
                if (width > 0 && height > 0) return true
                if (body + size > d.size) return false
                pos = (body + size).toInt()
            }
            return false
        }
        walk(0, d.size)
        return if (width > 0 && height > 0) HlsPlaylistParser.qualityHeight(width, height) else null
    }

    private fun vintLength(first: Byte): Int {
        val b = first.toInt() and 0xFF
        if (b == 0) return 0
        return Integer.numberOfLeadingZeros(b) - 24 + 1
    }

    private fun uint(d: ByteArray, from: Int, to: Int): Int {
        var value = 0L
        for (i in from until minOf(to, from + 4)) value = (value shl 8) or (d[i].toLong() and 0xFF)
        return value.toInt()
    }

    // ---- MP4 -----------------------------------------------------------------------------------

    /** Largest picture among the `tkhd` boxes inside the `moov` box: audio tracks have size 0. */
    fun mp4Height(d: ByteArray): Int? {
        val moov = indexOf(d, "moov", 0).takeIf { it >= 4 } ?: return null
        var best = 0
        var from = moov
        while (true) {
            val tkhd = indexOf(d, "tkhd", from)
            if (tkhd < 0) break
            from = tkhd + 4
            val start = tkhd - 4
            val size = be32(d, start)
            if (start < 0 || size < 84 || start + size > d.size) continue
            val width = be32(d, start + size - 8) ushr 16
            val height = be32(d, start + size - 4) ushr 16
            if (width in 160..8192 && height in 90..4320) best = maxOf(best, HlsPlaylistParser.qualityHeight(width, height))
        }
        return best.takeIf { it > 0 }
    }

    private fun be32(d: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > d.size) return 0
        return ((d[at].toInt() and 0xFF) shl 24) or ((d[at + 1].toInt() and 0xFF) shl 16) or
            ((d[at + 2].toInt() and 0xFF) shl 8) or (d[at + 3].toInt() and 0xFF)
    }

    private fun indexOf(d: ByteArray, tag: String, from: Int): Int {
        val t = tag.toByteArray(Charsets.ISO_8859_1)
        var i = maxOf(0, from)
        while (i <= d.size - t.size) {
            if (d[i] == t[0] && d[i + 1] == t[1] && d[i + 2] == t[2] && d[i + 3] == t[3]) return i
            i++
        }
        return -1
    }
}
