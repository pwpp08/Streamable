package com.streamflixreborn.streamflix.torrent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Source of torrent streams for a title. Kept as an interface so tests can fake the HTTP. */
fun interface TorrentStreamSource {
    /** @param path Stremio resource id, e.g. `movie/tt0468569` or `series/tt0903747:1:1`. */
    suspend fun fetch(path: String): String?
}

class TorrentioHttpSource(
    private val client: () -> OkHttpClient = { NetworkClient.default },
    private val baseUrl: String = BASE_URL,
) : TorrentStreamSource {
    override suspend fun fetch(path: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/$OPTIONS/stream/$path.json").build()
        client().newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }

    companion object {
        const val BASE_URL = "https://torrentio.strem.fun"
        const val OPTIONS = "qualityfilter=4k,3d,scr,cam|sort=qualitysize"
    }
}

object TorrentioParser {
    private val hashRegex = Regex("[0-9a-f]{40}")
    private val seedersRegex = Regex("👤\\s*(\\d+)")
    private val sizeRegex = Regex("💾\\s*([\\d.,]+)\\s*(TB|GB|MB|KB)", RegexOption.IGNORE_CASE)
    private val siteRegex = Regex("⚙️?\\s*(.+)")
    private val heightRegex = Regex("(2160|1440|1080|720|576|480)p", RegexOption.IGNORE_CASE)
    private const val SEEDER_MARK = "👤"

    fun parse(json: String): List<TorrentStream> {
        val streams = runCatching { JsonParser.parseString(json).asJsonObject.getAsJsonArray("streams") }.getOrNull()
            ?: return emptyList()
        return streams.mapNotNull { element -> (element as? JsonObject)?.let { runCatching { parseStream(it) }.getOrNull() } }
    }

    private fun str(o: JsonObject, key: String): String? =
        o.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun parseStream(o: JsonObject): TorrentStream? {
        val hash = str(o, "infoHash")?.lowercase() ?: return null
        if (!hashRegex.matches(hash)) return null
        val title = str(o, "title") ?: return null
        val name = str(o, "name").orEmpty()
        val lines = title.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val statIndex = lines.indexOfFirst { it.contains(SEEDER_MARK) }
        if (statIndex < 0) return null
        val stats = lines[statIndex]
        val release = lines.first()
        val fileLine = lines.subList(1, statIndex).firstOrNull()
        val languages = lines.drop(statIndex + 1).joinToString(" ")
        val seeders = seedersRegex.find(stats)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val size = sizeRegex.find(stats)?.let {
            val n = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let 0L
            val unit = when (it.groupValues[2].uppercase()) {
                "TB" -> 1L shl 40
                "GB" -> 1L shl 30
                "MB" -> 1L shl 20
                else -> 1L shl 10
            }
            (n * unit).toLong()
        } ?: 0L
        val site = siteRegex.find(stats)?.groupValues?.get(1)?.trim().orEmpty()
        val fileName = o.getAsJsonObject("behaviorHints")?.let { str(it, "filename") } ?: fileLine
        val height = heightRegex.find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: heightRegex.find(release)?.groupValues?.get(1)?.toIntOrNull()
            ?: 0
        val trackers = o.getAsJsonArray("sources")
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }
            ?.filter { it.startsWith("tracker:") }
            ?.map { it.removePrefix("tracker:") }
            .orEmpty()
        return TorrentStream(
            infoHash = hash,
            fileIdx = o.get("fileIdx")?.takeIf { it.isJsonPrimitive }?.asInt,
            release = release,
            fileName = fileName,
            seeders = seeders,
            sizeBytes = size,
            site = site,
            languages = languages,
            height = height,
            trackers = trackers,
        )
    }
}

/**
 * Chooses streams the target hardware plays smoothly: 1080p, H.264/AVC in 8 bit, English (or
 * Hungarian/multi) audio, healthy swarm, not a huge remux.
 */
object TorrentCandidateSelector {
    const val MIN_SEEDERS = 5
    const val MAX_BYTES = 12L shl 30

    private val badCodec = Regex(
        "(?<![a-z0-9])(x265|h[. ]?265|hevc|av1|10[ -]?bit|hi10p?|hdr10?\\+?|hdr|dv|dovi|dolby[ .]?vision|remux|2160p|4k|uhd)(?![a-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val goodCodec = Regex("(?<![a-z0-9])(x264|h[. ]?264|avc|yts|yify)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val goodSource = Regex("(?<![a-z0-9])(web-?dl|bluray|brrip|bdrip|webrip)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val badSource = Regex("(?<![a-z0-9])(cam|hdcam|ts|hdts|telesync|telecine|screener|scr)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val cyrillic = Regex("[\\u0400-\\u04FF]")
    // GB, US, HU flags and the globe Torrentio uses for "multi audio".
    private val acceptedFlags = listOf("🇬🇧", "🇺🇸", "🇭🇺", "🌎")
    private val multiOrEnglish = Regex("(?<![a-z0-9])(multi|dual|eng|english)(?![a-z0-9])", RegexOption.IGNORE_CASE)

    fun select(streams: List<TorrentStream>, max: Int = 3): List<TorrentStream> =
        streams.asSequence()
            .filter(::isUsable)
            .distinctBy { it.infoHash to it.fileIdx }
            .sortedByDescending(::score)
            .distinctBy { it.site.lowercase().ifBlank { it.infoHash } }
            .take(max)
            .toList()

    fun isUsable(s: TorrentStream): Boolean {
        if (s.height != 1080) return false
        if (s.seeders < MIN_SEEDERS) return false
        if (s.sizeBytes > MAX_BYTES) return false
        val text = "${s.release} ${s.fileName.orEmpty()}"
        if (cyrillic.containsMatchIn(text)) return false
        if (badCodec.containsMatchIn(text)) return false
        if (badSource.containsMatchIn(text)) return false
        // Torrentio lists flags only for releases that are not English-only; none means English.
        val langs = s.languages
        if (langs.isNotBlank() && acceptedFlags.none { langs.contains(it) } && !multiOrEnglish.containsMatchIn(text)) return false
        return true
    }

    private fun score(s: TorrentStream): Double {
        val text = "${s.release} ${s.fileName.orEmpty()}"
        var score = minOf(s.seeders, 200).toDouble()
        if (goodCodec.containsMatchIn(text)) score += 60
        // 2-9 GB is the sweet spot: good bitrate, still quick to start.
        val gb = s.sizeBytes / (1L shl 30).toDouble()
        score += when {
            gb in 2.0..9.0 -> 30.0
            gb < 2.0 -> 0.0
            else -> 10.0
        }
        if (goodSource.containsMatchIn(text)) score += 10
        return score
    }
}

/** Resolves the IMDb id Torrentio needs, from the models first and TMDb external ids otherwise. */
object TorrentImdb {
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun resolve(type: Video.Type): String? = when (type) {
        is Video.Type.Movie -> type.imdbId?.takeIf { it.startsWith("tt") }
            ?: lookup("m:${type.id}") { TMDb3.Movies.details(type.id.toInt()).imdbId }
        is Video.Type.Episode -> type.tvShow.imdbId?.takeIf { it.startsWith("tt") }
            ?: lookup("t:${type.tvShow.id}") {
                TMDb3.TvSeries.details(
                    type.tvShow.id.toInt(),
                    listOf(TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS),
                ).externalIds?.imdbId
            }
    }

    private suspend fun lookup(key: String, fetch: suspend () -> String?): String? {
        cache[key]?.let { return it }
        val id = runCatching { fetch() }.getOrNull()?.takeIf { it.startsWith("tt") } ?: return null
        cache[key] = id
        return id
    }
}

/** Builds the app's torrent servers for a title. */
class TorrentServers(
    private val source: TorrentStreamSource = TorrentioHttpSource(),
    private val imdbIdFor: suspend (Video.Type) -> String? = { TorrentImdb.resolve(it) },
    private val enabled: () -> Boolean = { UserPreferences.torrentEnabled },
) {
    suspend fun servers(videoType: Video.Type): List<Video.Server> {
        if (!enabled()) return emptyList()
        val imdb = imdbIdFor(videoType) ?: return emptyList()
        val path = when (videoType) {
            is Video.Type.Movie -> "movie/$imdb"
            is Video.Type.Episode -> "series/$imdb:${videoType.season.number}:${videoType.number}"
        }
        val json = runCatching { source.fetch(path) }.getOrNull() ?: return emptyList()
        return toServers(TorrentCandidateSelector.select(TorrentioParser.parse(json)))
    }

    companion object {
        fun toServers(selected: List<TorrentStream>): List<Video.Server> {
            val used = mutableSetOf<String>()
            return selected.map { s ->
                val label = (if (s.release.contains("yts", true) || s.release.contains("yify", true)) "YTS" else s.site)
                    .ifBlank { "P2P" }
                var name = "Torrent ${s.height}p ($label)"
                var n = 2
                while (!used.add(name)) name = "Torrent ${s.height}p ($label) #${n++}"
                val spec = TorrentSpec(s.infoHash, s.fileIdx, s.height, s.fileName ?: s.release, s.trackers)
                Video.Server(id = "torrent:${s.infoHash}:${s.fileIdx ?: -1}", name = name, src = spec.toUri())
            }
        }

        /** The player gets a torrent URI; no network happens until playback starts. */
        fun videoFor(server: Video.Server): Video {
            val spec = TorrentSpec.parse(server.src) ?: error("bad torrent source")
            val mime = when (spec.name?.substringAfterLast('.', "")?.lowercase()) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                else -> null
            }
            return Video(source = server.src, type = mime)
        }

        fun isTorrentServer(server: Video.Server) = TorrentSpec.isTorrentUri(server.src)
    }
}
