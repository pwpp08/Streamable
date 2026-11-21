package com.streamflixreborn.streamflix.torrent

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/** One torrent a viewer can pick by hand, with what its release name says about it. */
data class TorrentOption(
    val stream: TorrentStream,
    val addon: String,
    /** 2160, 1080, 720 or 480 (anything smaller or unknown). */
    val resolution: Int,
    val source: String?,
    val codec: String?,
    val hdr: Boolean,
    val audio: String?,
) {
    val title: String get() = stream.fileName?.takeIf { it.isNotBlank() } ?: stream.release
    val sizeGb: Double get() = stream.sizeBytes / (1024.0 * 1024.0 * 1024.0)
}

/** The filters on the torrents page. Empty sets mean "any". */
data class TorrentFilter(
    val resolutions: Set<Int> = emptySet(),
    val sources: Set<String> = emptySet(),
    val codecs: Set<String> = emptySet(),
    val hdrOnly: Boolean = false,
    val minSeeders: Int = 0,
    val maxSizeGb: Double? = null,
    val text: String = "",
)

enum class TorrentSort { SEEDERS, QUALITY, SIZE }

object TorrentBrowser {
    private val sourceTags = listOf(
        "Remux" to Regex("(?<![a-z0-9])remux(?![a-z0-9])", RegexOption.IGNORE_CASE),
        "BluRay" to Regex("(?<![a-z0-9])(blu-?ray|bdrip|brrip|bd)(?![a-z0-9])", RegexOption.IGNORE_CASE),
        "WEB-DL" to Regex("(?<![a-z0-9])web[ .-]?dl(?![a-z0-9])", RegexOption.IGNORE_CASE),
        "WEBRip" to Regex("(?<![a-z0-9])web[ .-]?rip(?![a-z0-9])", RegexOption.IGNORE_CASE),
        "HDTV" to Regex("(?<![a-z0-9])hdtv(?![a-z0-9])", RegexOption.IGNORE_CASE),
        "DVD" to Regex("(?<![a-z0-9])(dvd-?rip|dvd)(?![a-z0-9])", RegexOption.IGNORE_CASE),
        "CAM" to Regex("(?<![a-z0-9])(cam|hdcam|telesync|ts|tc)(?![a-z0-9])", RegexOption.IGNORE_CASE),
    )
    private val codecTags = listOf(
        "AV1" to Regex("(?<![a-z0-9])av1(?![a-z0-9])", RegexOption.IGNORE_CASE),
        "x265" to Regex("(?<![a-z0-9])(x265|h[. ]?265|hevc)(?![a-z0-9])", RegexOption.IGNORE_CASE),
        "x264" to Regex("(?<![a-z0-9])(x264|h[. ]?264|avc)(?![a-z0-9])", RegexOption.IGNORE_CASE),
    )
    private val hdrTag = Regex("(?<![a-z0-9])(hdr(10)?\\+?|dv|dovi|dolby[ .]?vision)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val audioTags = listOf(
        "Atmos" to Regex("atmos", RegexOption.IGNORE_CASE),
        "TrueHD" to Regex("truehd", RegexOption.IGNORE_CASE),
        "DTS" to Regex("(?<![a-z0-9])dts", RegexOption.IGNORE_CASE),
        "DDP" to Regex("(?<![a-z0-9])(ddp|dd\\+|e-?ac-?3)", RegexOption.IGNORE_CASE),
        "AC3" to Regex("(?<![a-z0-9])(dd ?5[. ]?1|ac-?3)(?![a-z0-9])", RegexOption.IGNORE_CASE),
        "AAC" to Regex("(?<![a-z0-9])aac", RegexOption.IGNORE_CASE),
    )

    /** The source names offered as filters, in display order. */
    val SOURCES = listOf("Remux", "BluRay", "WEB-DL", "WEBRip", "HDTV")
    val CODECS = listOf("x265", "x264", "AV1")
    val RESOLUTIONS = listOf(2160, 1080, 720, 480)

    fun resolutionBucket(height: Int): Int = when {
        height >= 2000 -> 2160
        height >= 1000 -> 1080
        height >= 700 -> 720
        else -> 480
    }

    fun describe(stream: TorrentStream, addon: String): TorrentOption {
        val text = stream.release + " " + stream.fileName.orEmpty()
        return TorrentOption(
            stream = stream,
            addon = addon,
            resolution = resolutionBucket(stream.height),
            source = sourceTags.firstOrNull { it.second.containsMatchIn(text) }?.first,
            codec = codecTags.firstOrNull { it.second.containsMatchIn(text) }?.first,
            hdr = hdrTag.containsMatchIn(text),
            audio = audioTags.firstOrNull { it.second.containsMatchIn(text) }?.first,
        )
    }

    fun filter(options: List<TorrentOption>, f: TorrentFilter): List<TorrentOption> {
        val words = f.text.lowercase().split(' ').filter { it.isNotBlank() }
        return options.filter { o ->
            (f.resolutions.isEmpty() || o.resolution in f.resolutions) &&
                (f.sources.isEmpty() || (o.source != null && o.source in f.sources)) &&
                (f.codecs.isEmpty() || (o.codec != null && o.codec in f.codecs)) &&
                (!f.hdrOnly || o.hdr) &&
                o.stream.seeders >= f.minSeeders &&
                (f.maxSizeGb == null || o.stream.sizeBytes == 0L || o.sizeGb <= f.maxSizeGb) &&
                words.all { it in o.title.lowercase() || it in o.addon.lowercase() }
        }
    }

    fun sort(options: List<TorrentOption>, by: TorrentSort): List<TorrentOption> = when (by) {
        TorrentSort.SEEDERS -> options.sortedByDescending { it.stream.seeders }
        TorrentSort.SIZE -> options.sortedBy { if (it.stream.sizeBytes == 0L) Long.MAX_VALUE else it.stream.sizeBytes }
        TorrentSort.QUALITY -> options.sortedWith(
            compareByDescending<TorrentOption> { it.resolution }
                .thenByDescending { qualityRank(it.source) }
                .thenByDescending { it.stream.seeders },
        )
    }

    private fun qualityRank(source: String?) = when (source) {
        "Remux" -> 6
        "BluRay" -> 5
        "WEB-DL" -> 4
        "WEBRip" -> 3
        "HDTV" -> 2
        "DVD" -> 1
        else -> 0
    }

    /** The same file offered by two addons shows once, with the better seeder count. */
    fun merge(options: List<TorrentOption>): List<TorrentOption> =
        options.groupBy { it.stream.infoHash to it.stream.fileIdx }.values
            .map { group -> group.maxByOrNull { it.stream.seeders }!! }

    fun toServer(option: TorrentOption): Video.Server {
        val s = option.stream
        val spec = TorrentSpec(s.infoHash, s.fileIdx, s.height.takeIf { it > 0 } ?: option.resolution, s.fileName ?: s.release, s.trackers)
        val name = buildString {
            append("Torrent ").append(option.resolution).append('p')
            option.source?.let { append(' ').append(it) }
            append(" (").append(option.addon).append(')')
        }
        return Video.Server(id = "torrent:${s.infoHash}:${s.fileIdx ?: -1}", name = name, src = spec.toUri())
    }

    private const val TORRENTIO = "https://torrentio.strem.fun/sort=qualitysize"
    private const val TIMEOUT_MS = 14_000L

    /** Every torrent Torrentio and the viewer's addons (MediaFusion and so on) know for the title. */
    suspend fun load(videoType: Video.Type): List<TorrentOption> = coroutineScope {
        val imdb = TorrentImdb.resolve(videoType) ?: return@coroutineScope emptyList()
        val path = when (videoType) {
            is Video.Type.Movie -> "movie/$imdb"
            is Video.Type.Episode -> "series/$imdb:${videoType.season.number}:${videoType.number}"
        }
        val sources = buildList {
            add("Torrentio" to TORRENTIO)
            StremioAddons.configured().forEach { base -> add(StremioAddons.nameOf(base) to base) }
        }
        val client = NetworkClient.default
        val all = sources.map { (name, base) ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(TIMEOUT_MS) {
                    runCatching {
                        val json = client.newCall(Request.Builder().url("$base/stream/$path.json").build()).execute()
                            .use { if (it.isSuccessful) it.body?.string() else null } ?: return@runCatching emptyList()
                        AddonStreamParser.parse(json).mapNotNull { it.torrent }.map { describe(it, name) }
                    }.getOrNull()
                }.orEmpty()
            }
        }.awaitAll().flatten()
        merge(all)
    }
}

/** A torrent picked on the torrents page, waiting for the player of that title to start with it. */
object TorrentChoice {
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Video.Server>()

    fun set(titleId: String, server: Video.Server) {
        pending[titleId] = server
    }

    fun take(titleId: String): Video.Server? = pending.remove(titleId)
}
