package com.streamflixreborn.streamflix.torrent

import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.format

/** Gets the addons' streams for what is waiting in "continue watching", before anyone presses play. */
object AddonPrefetch {
    fun continueWatching(items: List<Any>) {
        items.take(4).forEach { item ->
            val type = when (item) {
                is Movie -> Video.Type.Movie(
                    id = item.id,
                    title = item.title,
                    releaseDate = item.released?.format("yyyy-MM-dd") ?: "",
                    poster = item.poster ?: "",
                    imdbId = item.imdbId,
                )
                is Episode -> Video.Type.Episode(
                    id = item.id,
                    number = item.number,
                    title = item.title,
                    poster = item.poster,
                    overview = item.overview,
                    tvShow = Video.Type.Episode.TvShow(
                        id = item.tvShow?.id ?: "",
                        title = item.tvShow?.title ?: "",
                        poster = item.tvShow?.poster,
                        banner = item.tvShow?.banner,
                        releaseDate = item.tvShow?.released?.format("yyyy-MM-dd"),
                        imdbId = item.tvShow?.imdbId,
                    ),
                    season = Video.Type.Episode.Season(number = item.season?.number ?: 0, title = item.season?.title),
                )
                else -> null
            }
            if (type != null) StremioAddons.prefetch(type)
        }
    }
}
