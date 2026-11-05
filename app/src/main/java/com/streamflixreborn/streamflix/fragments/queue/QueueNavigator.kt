package com.streamflixreborn.streamflix.fragments.queue

import android.content.Context
import android.os.Bundle
import androidx.navigation.NavController
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.format

/** Opens a Queue title, or plays it with its details page underneath (Back returns there). */
object QueueNavigator {

    fun openDetails(navController: NavController, entry: QueueEntry) {
        val args = Bundle().apply {
            putString("id", entry.id)
            if (entry.isTv) {
                putString("poster", entry.tvShow?.poster ?: entry.poster)
                putString("banner", entry.tvShow?.banner)
            }
        }
        navController.navigate(if (entry.isTv) R.id.tv_show else R.id.movie, args)
    }

    /** Movies and Up-next episodes play straight away; a show without an episode opens its page. */
    fun play(context: Context, navController: NavController, entry: QueueEntry) {
        val args = playerArgs(context, entry)
        openDetails(navController, entry)
        if (args != null) navController.navigate(R.id.player, args)
    }

    fun canPlay(entry: QueueEntry): Boolean = !entry.isTv || entry.episode != null

    private fun playerArgs(context: Context, entry: QueueEntry): Bundle? {
        if (!entry.isTv) {
            val movie = entry.movie
            val videoType = Video.Type.Movie(
                id = entry.id,
                title = entry.title,
                releaseDate = movie?.released?.format("yyyy-MM-dd") ?: "",
                poster = movie?.poster ?: entry.poster ?: "",
                imdbId = movie?.imdbId,
            )
            return Bundle().apply {
                putString("id", entry.id)
                putString("title", entry.title)
                putString("subtitle", entry.year.orEmpty())
                putSerializable("videoType", videoType)
            }
        }
        val episode = entry.episode ?: return null
        val show = episode.tvShow ?: entry.tvShow
        val season = episode.season
        val episodeTitle = episode.title ?: context.getString(R.string.episode_number, episode.number)
        val subtitle = season?.takeIf { it.number != 0 }
            ?.let { context.getString(R.string.player_subtitle_tv_show, it.number, episode.number, episodeTitle) }
            ?: context.getString(R.string.player_subtitle_tv_show_episode_only, episode.number, episodeTitle)
        val videoType = Video.Type.Episode(
            id = episode.id,
            number = episode.number,
            title = episode.title,
            poster = episode.poster,
            overview = episode.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = show?.id ?: entry.id,
                title = show?.title ?: entry.title,
                poster = show?.poster,
                banner = show?.banner,
                releaseDate = show?.released?.format("yyyy-MM-dd"),
                imdbId = show?.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = season?.number ?: 0,
                title = season?.title,
            ),
        )
        return Bundle().apply {
            putString("id", episode.id)
            putString("title", show?.title ?: entry.title)
            putString("subtitle", subtitle)
            putSerializable("videoType", videoType)
        }
    }
}
