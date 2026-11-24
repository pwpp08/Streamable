package com.streamflixreborn.streamflix.downloads.torrent

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.torrent.AddonStreamParser
import com.streamflixreborn.streamflix.torrent.TorrentImdb
import com.streamflixreborn.streamflix.torrent.TorrentStream
import com.streamflixreborn.streamflix.torrent.TorrentStreamSource
import com.streamflixreborn.streamflix.torrent.TorrentioHttpSource
import com.streamflixreborn.streamflix.torrent.TorrentioParser

/**
 * The downloads offered in the quality popup: one pick per resolution. Looser than the streaming
 * rules (a phone plays almost anything, and nothing has to start within seconds), but still no
 * cams, no burned-in subtitles and no dead torrents.
 */
object DownloadCandidates {
    const val MIN_SEEDERS = 2
    const val MAX_BYTES = 20L shl 30
    private val ALLOWED_HEIGHTS = setOf(2160, 1080, 720, 480)

    private val badSource = Regex(
        "(?<![a-z0-9])(cam|camrip|cam-rip|hqcam|hdcam|hd-cam|ts|hdts|hd-ts|tc|hdtc|telesync|telecine|screener|scr|dvdscr|pdvd|predvd|pre-dvd|r5)(?![a-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val cyrillic = Regex("[\\u0400-\\u04FF]")
    private val goodCodec = Regex("(?<![a-z0-9])(x264|h[. ]?264|avc|yts|yify)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val goodSource = Regex("(?<![a-z0-9])(web-?dl|bluray|brrip|bdrip|webrip)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val acceptedFlags = listOf("🇬🇧", "🇺🇸", "🇭🇺", "🌎")
    private val multiOrEnglish = Regex("(?<![a-z0-9])(multi|dual|eng|english)(?![a-z0-9])", RegexOption.IGNORE_CASE)

    suspend fun load(type: Video.Type, source: TorrentStreamSource = TorrentioHttpSource()): List<TorrentChoice> {
        val imdb = TorrentImdb.resolve(type) ?: return emptyList()
        val path = when (type) {
            is Video.Type.Movie -> "movie/$imdb"
            is Video.Type.Episode -> "series/$imdb:${type.season.number}:${type.number}"
        }
        val json = runCatching { source.fetch(path) }.getOrNull() ?: return emptyList()
        return pick(TorrentioParser.parse(json))
    }

    fun pick(streams: List<TorrentStream>): List<TorrentChoice> =
        streams.asSequence()
            .filter(::isDownloadable)
            .distinctBy { it.infoHash to it.fileIdx }
            .groupBy { it.height }
            .map { (_, group) -> group.maxByOrNull(::score)!! }
            .sortedByDescending { it.height }
            .map { s ->
                val label = if (s.release.contains("yts", true) || s.release.contains("yify", true)) "YTS" else s.site.ifBlank { "P2P" }
                TorrentChoice(
                    infoHash = s.infoHash, fileIdx = s.fileIdx, release = s.release, sizeBytes = s.sizeBytes,
                    seeders = s.seeders, height = s.height, site = label, trackers = s.trackers,
                )
            }
            .toList()

    fun isDownloadable(s: TorrentStream): Boolean {
        if (s.height !in ALLOWED_HEIGHTS) return false
        if (s.seeders < MIN_SEEDERS) return false
        if (s.sizeBytes > MAX_BYTES) return false
        val text = "${s.release} ${s.fileName.orEmpty()}"
        if (cyrillic.containsMatchIn(text)) return false
        if (badSource.containsMatchIn(text)) return false
        if (AddonStreamParser.hardcodedSubs.containsMatchIn(text)) return false
        val langs = s.languages
        if (langs.isNotBlank() && acceptedFlags.none { langs.contains(it) } && !multiOrEnglish.containsMatchIn(text)) return false
        return true
    }

    private fun score(s: TorrentStream): Double {
        val text = "${s.release} ${s.fileName.orEmpty()}"
        var score = minOf(s.seeders, 200).toDouble()
        if (goodCodec.containsMatchIn(text)) score += 40
        if (goodSource.containsMatchIn(text)) score += 10
        return score
    }
}
