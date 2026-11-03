package com.streamflixreborn.streamflix.downloads

import com.streamflixreborn.streamflix.models.Video
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * App metadata stored in `DownloadRequest.data`: what the Downloads screen shows and what the
 * player needs to play the title offline.
 */
@Serializable
data class DownloadMetadata(
    val title: String,
    /** Second line, same text the player header shows (year, or "S1 E2 • Title"). */
    val subtitle: String = "",
    val poster: String? = null,
    val provider: String,
    val server: String? = null,
    /** Servers already tried for this title; "Try another server" skips them. */
    val triedServers: List<String> = emptyList(),
    val videoType: StoredVideoType,
    val subtitles: List<LocalSubtitle> = emptyList(),
) {
    fun encode(): ByteArray = json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(data: ByteArray?): DownloadMetadata? {
            if (data == null || data.isEmpty()) return null
            return runCatching { json.decodeFromString(serializer(), data.toString(Charsets.UTF_8)) }.getOrNull()
        }
    }
}

/** A sideloaded subtitle saved next to the download. [path] is an absolute file path. */
@Serializable
data class LocalSubtitle(
    val label: String,
    val path: String,
    val default: Boolean = false,
)

/** Serialisable copy of [Video.Type]. */
@Serializable
data class StoredVideoType(
    val kind: String,
    val id: String,
    val title: String? = null,
    val releaseDate: String? = null,
    val poster: String? = null,
    val imdbId: String? = null,
    val number: Int = 0,
    val overview: String? = null,
    val showId: String? = null,
    val showTitle: String? = null,
    val showPoster: String? = null,
    val showBanner: String? = null,
    val showReleaseDate: String? = null,
    val showImdbId: String? = null,
    val seasonNumber: Int = 0,
    val seasonTitle: String? = null,
) {
    fun toVideoType(): Video.Type = when (kind) {
        KIND_EPISODE -> Video.Type.Episode(
            id = id,
            number = number,
            title = title,
            poster = poster,
            overview = overview,
            tvShow = Video.Type.Episode.TvShow(
                id = showId ?: "",
                title = showTitle ?: "",
                poster = showPoster,
                banner = showBanner,
                releaseDate = showReleaseDate,
                imdbId = showImdbId,
            ),
            season = Video.Type.Episode.Season(number = seasonNumber, title = seasonTitle),
        )
        else -> Video.Type.Movie(
            id = id,
            title = title ?: "",
            releaseDate = releaseDate ?: "",
            poster = poster ?: "",
            imdbId = imdbId,
        )
    }

    companion object {
        const val KIND_MOVIE = "movie"
        const val KIND_EPISODE = "episode"

        fun of(type: Video.Type): StoredVideoType = when (type) {
            is Video.Type.Movie -> StoredVideoType(
                kind = KIND_MOVIE,
                id = type.id,
                title = type.title,
                releaseDate = type.releaseDate,
                poster = type.poster,
                imdbId = type.imdbId,
            )
            is Video.Type.Episode -> StoredVideoType(
                kind = KIND_EPISODE,
                id = type.id,
                title = type.title,
                poster = type.poster,
                number = type.number,
                overview = type.overview,
                showId = type.tvShow.id,
                showTitle = type.tvShow.title,
                showPoster = type.tvShow.poster,
                showBanner = type.tvShow.banner,
                showReleaseDate = type.tvShow.releaseDate,
                showImdbId = type.tvShow.imdbId,
                seasonNumber = type.season.number,
                seasonTitle = type.season.title,
            )
        }
    }
}

object DownloadIds {
    /** One download per provider and title (movie or single episode). */
    fun of(providerName: String, type: Video.Type): String = when (type) {
        is Video.Type.Movie -> "$providerName|movie|${type.id}"
        is Video.Type.Episode -> "$providerName|episode|${type.id}"
    }

    /** File-system safe form of a download id (used for the subtitle folder). */
    fun folderName(id: String): String {
        val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return "${safe}_${Integer.toHexString(id.hashCode())}"
    }
}
