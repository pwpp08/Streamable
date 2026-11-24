package com.streamflixreborn.streamflix.downloads.torrent

import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.LanguageMatcher
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import com.streamflixreborn.streamflix.utils.SubDL
import com.streamflixreborn.streamflix.utils.SubtitleTitleMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Finds the subtitle a viewer asked for at download time, the way the player does (OpenSubtitles, then SubDL). */
object DownloadSubtitles {
    private const val TAG = "DownloadSubtitles"
    private const val MAX_BYTES = 5L * 1024 * 1024

    /** SubRip text as bytes (UTF-8), or null when nothing suitable was found. */
    suspend fun find(type: Video.Type, wish: SubtitleWish, release: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { fromOpenSubtitles(type, wish, release) }.onFailure { Log.w(TAG, "OpenSubtitles failed", it) }.getOrNull()
            ?: runCatching { fromSubDl(type, wish, release) }.onFailure { Log.w(TAG, "SubDL failed", it) }.getOrNull()
    }

    /** How many words of the torrent's release name (WEB-DL, YTS, the group...) the subtitle's file name shares. */
    internal fun similarity(release: String, subtitleName: String?): Int {
        val words = Regex("[a-z0-9]+").findAll(release.lowercase()).map { it.value }.filter { it.length > 2 }.toSet()
        val other = Regex("[a-z0-9]+").findAll(subtitleName.orEmpty().lowercase()).map { it.value }.toSet()
        return words.count { it in other }
    }

    private suspend fun fromOpenSubtitles(type: Video.Type, wish: SubtitleWish, release: String): ByteArray? {
        val languages = LanguageMatcher.toIso6392B(wish.iso1) ?: return null
        val found = when (type) {
            is Video.Type.Episode -> SubtitleTitleMatch.filter(
                OpenSubtitles.search(
                    query = type.tvShow.title,
                    season = type.season.number,
                    episode = type.number,
                    subLanguageId = languages,
                ),
                title = type.tvShow.title,
                year = type.tvShow.releaseDate,
                series = true,
            )
            is Video.Type.Movie -> {
                val imdb = type.imdbId?.takeIf { it.startsWith("tt") }
                val byImdb = imdb?.let {
                    runCatching { OpenSubtitles.search(imdbId = it.removePrefix("tt").trimStart('0'), subLanguageId = languages) }
                        .getOrDefault(emptyList())
                }.orEmpty()
                SubtitleTitleMatch.filter(byImdb, type.title, type.releaseDate, imdb).ifEmpty {
                    SubtitleTitleMatch.filter(
                        OpenSubtitles.search(query = type.title, subLanguageId = languages),
                        type.title, type.releaseDate, imdb,
                    )
                }
            }
        }
        val best = found
            .filter { it.subFormat.equals("srt", ignoreCase = true) || it.subFileName.orEmpty().endsWith(".srt", ignoreCase = true) }
            .sortedWith(
                compareByDescending<OpenSubtitles.Subtitle> { similarity(release, it.subFileName) }
                    .thenByDescending { it.subDownloadsCnt?.toLongOrNull() ?: 0L },
            )
        for (candidate in best.take(3)) {
            val file = runCatching { File(requireNotNull(OpenSubtitles.download(candidate).path)) }.getOrNull() ?: continue
            if (file.length() in 1..MAX_BYTES) return file.readBytes().also { file.delete() }
            file.delete()
        }
        return null
    }

    private suspend fun fromSubDl(type: Video.Type, wish: SubtitleWish, release: String): ByteArray? {
        val list = when (type) {
            is Video.Type.Episode -> SubDL.search(
                filmName = type.tvShow.title,
                seasonNumber = type.season.number,
                episodeNumber = type.number,
                type = "tv",
                languages = listOf(wish.iso1),
            )
            is Video.Type.Movie -> SubDL.search(filmName = type.title, type = "movie", languages = listOf(wish.iso1))
        }
        val best = list.sortedByDescending { similarity(release, it.releaseName) }
        for (candidate in best.take(3)) {
            val file = runCatching { File(requireNotNull(SubDL.download(candidate).path)) }.getOrNull() ?: continue
            val isSrt = file.name.endsWith(".srt", ignoreCase = true)
            if (isSrt && file.length() in 1..MAX_BYTES) return file.readBytes().also { file.delete() }
            file.delete()
        }
        return null
    }
}
