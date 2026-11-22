package com.streamflixreborn.streamflix.torrent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.ContainerProbe
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Stremio addons the viewer added in Settings > Sources (one manifest link per line). Each one is
 * asked for a title's streams the way Stremio asks: `<base>/stream/<type>/<imdb>.json`. Torrent
 * answers go through the same rules as Torrentio's; direct web links become servers of their own.
 */
object StremioAddons {

    data class Addon(val baseUrl: String, val name: String)

    /** One addon link as pasted: `stremio://`, a `/manifest.json` link or the bare base all work. */
    fun normalize(raw: String): String? {
        var url = raw.trim()
        if (url.isEmpty() || url.startsWith("#")) return null
        if (url.startsWith("stremio://", ignoreCase = true)) url = "https://" + url.substring("stremio://".length)
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        url = url.substringBefore('#').trimEnd('/')
        if (url.endsWith("/manifest.json", ignoreCase = true)) url = url.dropLast("/manifest.json".length)
        // Torrentio is built in (Settings > Sources > Torrent); a pasted copy would only list it twice.
        if (url.substringAfter("://").substringBefore('/').equals("torrentio.strem.fun", true)) return null
        return url.takeIf { it.length > 10 }
    }

    /** Links built into the app at build time (separated by |), then the ones the viewer pasted. */
    fun configured(text: String = UserPreferences.stremioAddons): List<String> =
        (com.streamflixreborn.streamflix.BuildConfig.DEFAULT_ADDONS.split('|') + text.lines()).mapNotNull(::normalize).distinct()

    private val names = ConcurrentHashMap<String, String>()

    /** Reads an addon's manifest; null when it isn't a Stremio addon that offers streams. */
    suspend fun manifest(baseUrl: String, client: OkHttpClient = NetworkClient.default): Addon? = withContext(Dispatchers.IO) {
        runCatching {
            val body = client.newCall(Request.Builder().url("$baseUrl/manifest.json").build()).execute().use {
                if (it.isSuccessful) it.body?.string() else null
            } ?: return@runCatching null
            val json = JsonParser.parseString(body).asJsonObject
            val resources = json.getAsJsonArray("resources")?.mapNotNull { r ->
                when {
                    r.isJsonPrimitive -> r.asString
                    r.isJsonObject -> r.asJsonObject.get("name")?.asString
                    else -> null
                }
            }.orEmpty()
            if ("stream" !in resources) return@runCatching null
            val name = json.get("name")?.asString?.trim().orEmpty().ifBlank { "Addon" }
            names[baseUrl] = name
            Addon(baseUrl, name)
        }.getOrNull()
    }

    suspend fun nameOf(baseUrl: String): String = names[baseUrl] ?: manifest(baseUrl)?.name
        ?: baseUrl.substringAfter("://").substringBefore('/')

    /** Builds servers from every configured addon, asked in parallel; slow or broken ones are skipped. */
    class Servers(
        private val client: () -> OkHttpClient = { NetworkClient.default },
        private val imdbIdFor: suspend (Video.Type) -> String? = { TorrentImdb.resolve(it) },
        private val addons: () -> List<String> = { configured() },
        private val torrentsAllowed: () -> Boolean = { UserPreferences.torrentEnabled },
    ) {
        suspend fun servers(videoType: Video.Type): List<Video.Server> {
            val bases = addons()
            if (bases.isEmpty()) return emptyList()
            val imdb = imdbIdFor(videoType) ?: return emptyList()
            val path = when (videoType) {
                is Video.Type.Movie -> "movie/$imdb"
                is Video.Type.Episode -> "series/$imdb:${videoType.season.number}:${videoType.number}"
            }
            return coroutineScope {
                bases.mapIndexed { index, base ->
                    async(Dispatchers.IO) {
                        // The request and the measuring have separate limits, so a slow answer still
                        // leaves time to measure, and the whole thing ends before the caller gives up.
                        val started = System.currentTimeMillis()
                        val tag = "addon ${index + 1}/${bases.size}"
                        // One quick retry: a refused or dropped first request is common and costs the whole list.
                        val json = withTimeoutOrNull(ADDON_TIMEOUT_MS) {
                            var body: String? = null
                            var attempt = 0
                            while (body == null && attempt < 2) {
                                attempt++
                                val outcome = runCatching {
                                    client().newCall(Request.Builder().url("$base/stream/$path.json").build()).execute().use { r ->
                                        if (r.isSuccessful) r.body?.string() else { logger("$tag attempt $attempt: HTTP ${r.code}"); null }
                                    }
                                }
                                body = outcome.getOrNull()
                                outcome.exceptionOrNull()?.let { logger("$tag attempt $attempt: ${it.javaClass.simpleName} after ${System.currentTimeMillis() - started} ms") }
                                if (body == null && attempt < 2) delay(500)
                            }
                            body
                        }
                        if (json == null) {
                            logger("$tag: no answer after ${System.currentTimeMillis() - started} ms")
                            return@async emptyList()
                        }
                        val result = runCatching { toServers(nameOf(base), measured(AddonStreamParser.parse(json)), torrentsAllowed()) }
                            .onFailure { logger("$tag: ${it.javaClass.simpleName} while reading the answer") }
                            .getOrDefault(emptyList())
                        logger("$tag: ${result.size} servers in ${System.currentTimeMillis() - started} ms")
                        result
                    }
                }.awaitAll().flatten()
            }
        }
    }

    /**
     * Addons label files by what the release says, and a "1080p" file is sometimes 720p. Looks at the
     * header of the best-looking candidates and replaces the label's height with the real one.
     */
    suspend fun measured(streams: List<AddonStream>): List<AddonStream> = coroutineScope {
        val candidates = DirectPicker.pick(streams.mapNotNull { it.direct }, max = MEASURE_CANDIDATES, perSource = 4)
        // Each file gets its own limit: one slow host no longer throws away the heights already read.
        val heights = candidates.map { d ->
            async(Dispatchers.IO) {
                d.url to withTimeoutOrNull(MEASURE_TIMEOUT_MS) {
                    ContainerProbe.height(d.url, d.headers.takeIf { it.isNotEmpty() }, DirectPicker.mimeOf(d))
                }
            }
        }.awaitAll().mapNotNull { (url, h) -> h?.let { url to it } }.toMap()
        streams.map { s ->
            val d = s.direct ?: return@map s
            val real = heights[d.url] ?: return@map s
            s.copy(direct = d.copy(height = real, measured = true))
        }
    }

    /** Torrents: the usual 1080p rules, at most two per addon. Web links: the most playable few. */
    fun toServers(addon: String, streams: List<AddonStream>, torrentsAllowed: Boolean): List<Video.Server> {
        val torrents = if (!torrentsAllowed) emptyList() else
            TorrentServers.toServers(TorrentCandidateSelector.select(streams.mapNotNull { it.torrent }, max = 2))
                .map { s -> Video.Server(s.id, s.name.replace(Regex("\\(([^)]*)\\)"), "($addon)"), s.src) }
        val used = mutableSetOf<String>()
        // One server per source site, two for Cinejoy Sirius (full HD and 4K): copies of the same host
        // only slow the list down and fail together.
        val perSite = HashMap<String, Int>()
        val direct = DirectPicker.pick(streams.mapNotNull { it.direct }, perSource = 2).filter { d ->
            val key = DirectPicker.sourceOf(d.label) ?: "?"
            val limit = if (key.contains("sirius", ignoreCase = true)) 2 else 1
            (perSite.merge(key, 1, Int::plus) ?: 1) <= limit
        }.map { d ->
            val source = DirectPicker.sourceOf(d.label)
            val base = buildString {
                append(addon)
                // Only a height read from the file itself is shown; a release label can be wrong.
                if (d.measured && d.height > 0) append(' ').append(d.height).append('p')
                if (source != null) append(" \u00b7 ").append(source)
            }
            var name = base
            var n = 2
            while (!used.add(name)) name = "$base #${n++}"
            val id = "addon:${d.url.hashCode()}"
            Video.Server(id = id, name = name, src = d.url).apply {
                video = Video(
                    source = d.url,
                    headers = d.headers.takeIf { it.isNotEmpty() },
                    type = DirectPicker.mimeOf(d),
                ).also { videos[id] = it }
            }
        }
        return direct + torrents
    }

    /** Where the addon loading reports what happened; the Android app points it at logcat. */
    @Volatile var logger: (String) -> Unit = {}

    /** A server's name without its measured height or "#2", so the same stream matches across fetches. */
    fun sameStream(name: String): String =
        name.replace(Regex(" \\d{3,4}p(?= |$)"), "").replace(Regex(" #\\d+$"), "").trim().lowercase()

    /**
     * Asks the addons again and returns fresh links by [sameStream] name. Addon links (Cinejoy's)
     * stop working after a while, so a stream that failed mid-film is retried with a new one.
     */
    suspend fun freshLinks(videoType: Video.Type): Map<String, Video> =
        Servers().servers(videoType).filter { isAddonServer(it) }
            .groupBy { sameStream(it.name) }
            .mapValues { (_, list) -> videoFor(list.first()) }

    /** What a failed link answers now, for the log: status, type and the first bytes, never the link. */
    suspend fun diagnose(video: Video): String = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(video.source).apply {
                video.headers?.forEach { (k, v) -> header(k, v) }
            }.build()
            NetworkClient.default.newCall(request).execute().use { r ->
                val head = r.body?.source()?.let { s -> s.request(80); s.buffer.snapshot().utf8().take(80) }.orEmpty()
                "HTTP ${r.code} ${r.header("Content-Type")} \"${head.replace(Regex("\\s+"), " ")}\""
            }
        }.getOrElse { "error ${it.javaClass.simpleName}: ${it.message}" }
    }

    /** Direct links keep their headers here, so a copied server object still plays. */
    private val videos = ConcurrentHashMap<String, Video>()

    fun isAddonServer(server: Video.Server) = server.id.startsWith("addon:")

    fun videoFor(server: Video.Server): Video = server.video ?: videos[server.id] ?: Video(source = server.src)

    private const val ADDON_TIMEOUT_MS = 10_000L
    private const val MEASURE_TIMEOUT_MS = 3_000L
    private const val MEASURE_CANDIDATES = 8
}

/** One stream an addon offered: a torrent, a direct web link, or something the app can't play. */
data class AddonStream(val torrent: TorrentStream? = null, val direct: Direct? = null) {
    data class Direct(val url: String, val height: Int, val label: String, val headers: Map<String, String>, val measured: Boolean = false)
}

/** Reads Stremio stream answers from any addon (Torrentio's emoji lines, MediaFusion's, plain ones). */
object AddonStreamParser {
    private val hashRegex = Regex("[0-9a-f]{40}")
    private val seedersRegex = Regex("(?:👤|👥|seeders?:?)\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val sizeRegex = Regex("(?:💾|📦)?\\s*([\\d.,]+)\\s*(TB|GB|MB)\\b", RegexOption.IGNORE_CASE)
    private val heightRegex = Regex("(?<![0-9])(2160|1440|1080|720|576|480)p", RegexOption.IGNORE_CASE)
    private val fourK = Regex("(?<![a-z0-9])(4k|uhd)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    // Placeholder entries some addons send ("You must sign in", "Configure me"); they aren't video.
    private val placeholder = Regex("sign ?in|log ?in|configure|authenticat|invalid|revoked|expired|error", RegexOption.IGNORE_CASE)

    /** Subtitles burned into the picture: release names say so with these tags. */
    val hardcodedSubs = Regex(
        "(?<![a-z0-9])(hc|hardsub(bed|s)?|hard[ .-]?coded|hcsub(s)?|korsub|hc[ .-]?subs?)(?![a-z0-9])",
        RegexOption.IGNORE_CASE,
    )

    fun parse(json: String): List<AddonStream> {
        val streams = runCatching { JsonParser.parseString(json).asJsonObject.getAsJsonArray("streams") }.getOrNull()
            ?: return emptyList()
        return streams.mapNotNull { e -> (e as? JsonObject)?.let { runCatching { parseOne(it) }.getOrNull() } }
    }

    private fun str(o: JsonObject?, key: String): String? = o?.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun parseOne(o: JsonObject): AddonStream? {
        val name = str(o, "name").orEmpty()
        val title = str(o, "title").orEmpty()
        val description = str(o, "description").orEmpty()
        val text = listOf(name, title, description).filter { it.isNotBlank() }.joinToString("\n")
        val hints = o.getAsJsonObject("behaviorHints")
        val fileName = str(hints, "filename")
        val height = heightRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (fourK.containsMatchIn(text)) 2160 else 0

        val hash = str(o, "infoHash")?.lowercase()
        if (hash != null) {
            if (!hashRegex.matches(hash)) return null
            val lines = (title.ifBlank { description }).lines().map { it.trim() }.filter { it.isNotEmpty() }
            val release = lines.firstOrNull() ?: fileName ?: return null
            val size = sizeRegex.find(text)?.let {
                val n = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let 0L
                val unit = when (it.groupValues[2].uppercase()) { "TB" -> 1L shl 40; "GB" -> 1L shl 30; else -> 1L shl 20 }
                (n * unit).toLong()
            } ?: 0L
            val trackers = o.getAsJsonArray("sources")
                ?.mapNotNull { s -> s.takeIf { it.isJsonPrimitive }?.asString }
                ?.filter { it.startsWith("tracker:") }
                ?.map { it.removePrefix("tracker:") }
                .orEmpty()
            return AddonStream(
                torrent = TorrentStream(
                    infoHash = hash,
                    fileIdx = o.get("fileIdx")?.takeIf { it.isJsonPrimitive }?.asInt,
                    release = release,
                    fileName = fileName ?: lines.getOrNull(1),
                    // Addons that don't report seeders still list live torrents; count them as healthy.
                    seeders = seedersRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: TorrentCandidateSelector.MIN_SEEDERS,
                    sizeBytes = size,
                    site = "",
                    languages = "",
                    height = height,
                    trackers = trackers,
                ),
            )
        }

        val url = str(o, "url") ?: return null
        if (!url.startsWith("http", true)) return null
        if (height == 0 && placeholder.containsMatchIn(text)) return null
        val headers = hints?.getAsJsonObject("proxyHeaders")?.getAsJsonObject("request")
            ?.entrySet()?.mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive }?.let { k to it.asString } }?.toMap()
            .orEmpty()
        return AddonStream(direct = AddonStream.Direct(url, height, (text + "\n" + fileName.orEmpty()).replace("\n", " | "), headers))
    }
}


/**
 * Chooses which of an addon's direct links to offer. Addons like PenguPlay return dozens of files
 * per title; taking the first few in list order gave files with E-AC3/DTS audio, which many phones
 * and the projector play silently. Prefers 1080p, MP4/HLS, H.264 and AAC/AC3 audio, and drops
 * files that have no English audio or burned-in subtitles.
 */
object DirectPicker {
    private val riskyAudio = Regex("(?<![a-z0-9])(ddp|dd\\+|e-?ac-?3|dts(-?hd)?|truehd|atmos)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val safeAudio = Regex("(?<![a-z0-9])(aac|ac-?3|dd ?5[. ]?1|dd ?2[. ]?0|opus|mp3)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val hevc = Regex("(?<![a-z0-9])(x265|h[. ]?265|hevc|av1)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val tenBit = Regex("(?<![a-z0-9])(10[ -]?bit|hi10p?)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val hdr = Regex("(?<![a-z0-9])(hdr(10)?\\+?|dv|dovi|dolby[ .]?vision)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val h264 = Regex("(?<![a-z0-9])(x264|h[. ]?264|avc)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val sizeGb = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(GB|MB)", RegexOption.IGNORE_CASE)
    private val audioLine = Regex("Audio:\\s*([^|\\u2022]*)", RegexOption.IGNORE_CASE)
    private val englishOk = Regex("english|(?<![a-z])eng(?![a-z])|multi|dual", RegexOption.IGNORE_CASE)
    private val sourceName = Regex("Source:\\s*([^|]+)")
    private val sirius = Regex("sirius", RegexOption.IGNORE_CASE)
    private val cinejoy = Regex("cinejoy", RegexOption.IGNORE_CASE)

    fun sourceOf(label: String): String? =
        sourceName.find(label)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

    fun mimeOf(d: AddonStream.Direct): String? {
        val path = d.url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || "/hls/" in path || Regex("(?<![a-z0-9])hls(?![a-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(d.label) -> "application/x-mpegURL"
            path.endsWith(".mp4") || Regex("(?<![a-z0-9])mp4(?![a-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(d.label) -> "video/mp4"
            path.endsWith(".mkv") || Regex("(?<![a-z0-9])mkv(?![a-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(d.label) -> "video/x-matroska"
            else -> null
        }
    }

    /** False when the file says what it is dubbed in and English isn't one of the languages. */
    fun hasEnglish(label: String): Boolean {
        val line = audioLine.find(label)?.groupValues?.get(1) ?: return true
        return line.isBlank() || englishOk.containsMatchIn(line)
    }

    fun score(d: AddonStream.Direct): Int {
        val text = d.label
        var score = when {
            d.height == 1080 -> 300
            d.height in 1081..1440 -> 260
            d.height >= 2160 -> 200
            d.height == 720 -> 120
            d.height == 0 -> 150
            else -> 0
        }
        if (d.measured) score += 25
        val mime = mimeOf(d)
        if (mime == "video/mp4" || mime == "application/x-mpegURL") score += 40
        // PenguPlay's Cinejoy "Sirius" HLS streams are the dependable ones, at full HD.
        val haystack = text + " " + d.url
        if (sirius.containsMatchIn(haystack)) score += if (d.height >= 1000 || d.height == 0) 250 else 60
        else if (cinejoy.containsMatchIn(haystack)) score += 80
        if (h264.containsMatchIn(text)) score += 40
        if (hevc.containsMatchIn(text)) score -= 30
        if (tenBit.containsMatchIn(text)) score -= 20
        if (hdr.containsMatchIn(text)) score -= 25
        if (riskyAudio.containsMatchIn(text) && !safeAudio.containsMatchIn(text)) score -= 150
        val gb = sizeGb.find(text)?.let {
            val n = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
            if (it.groupValues[2].equals("MB", true)) n / 1024 else n
        }
        if (gb != null) score += when {
            gb in 2.0..10.0 -> 30
            gb < 1.0 -> -30
            gb > 25 -> -40
            else -> 0
        }
        return score
    }

    /** The best few, at most two per source site so one failing host can't take them all. */
    fun pick(all: List<AddonStream.Direct>, max: Int = 6, perSource: Int = 2): List<AddonStream.Direct> {
        val usable = all
            .filter { it.height == 0 || it.height >= 720 }
            .filterNot { AddonStreamParser.hardcodedSubs.containsMatchIn(it.label) }
            .filter { hasEnglish(it.label) }
            .distinctBy { it.url.substringBefore('?') }
        val counts = HashMap<String, Int>()
        return usable.sortedByDescending(::score).filter { d ->
            val key = sourceOf(d.label) ?: "?"
            val count = counts.merge(key, 1, Int::plus) ?: 1
            count <= perSource
        }.take(max)
    }
}
