package com.streamflixreborn.streamflix.fragments.queue

import android.content.Context
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.database.AppDatabase
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.Fragment
import android.widget.Toast
import android.os.Bundle
import androidx.navigation.NavController
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.ReleaseStatus
import com.streamflixreborn.streamflix.utils.format

/** Opens a Queue title, or plays it with its details page underneath (Back returns there). */
object QueueNavigator {

    fun openDetails(navController: NavController, entry: QueueEntry) {
        openDetails(navController, entry.id, entry.isTv, entry.tvShow?.poster ?: entry.poster, entry.tvShow?.banner)
    }

    fun openDetails(navController: NavController, id: String, isTv: Boolean, poster: String?, banner: String?) {
        val args = Bundle().apply {
            putString("id", id)
            if (isTv) {
                putString("poster", poster)
                putString("banner", banner)
            }
        }
        navController.navigate(if (isTv) R.id.tv_show else R.id.movie, args)
    }

    /** Movies and Up-next episodes play straight away; a show without an episode opens its page. */
    fun play(context: Context, navController: NavController, entry: QueueEntry) {
        val args = if (isUnreleased(entry)) null else playerArgs(context, entry)
        openDetails(navController, entry)
        if (args != null) navController.navigate(R.id.player, args)
    }

    /** Not out yet: there is nothing to play, only the details page and its release date. */
    fun isUnreleased(entry: QueueEntry): Boolean = ReleaseStatus.isUnreleased(entry.releasedMillis)

    /** Anything that is out can be played; a show without a chosen episode gets one looked up (see [playResolving]). */
    fun canPlay(entry: QueueEntry): Boolean = !isUnreleased(entry)

    /** "Releases Nov 12" for a title that is not out yet, else null. */
    fun releaseLabel(context: Context, entry: QueueEntry): String? {
        val millis = entry.releasedMillis?.takeIf { ReleaseStatus.isUnreleased(it) } ?: return null
        val date = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return context.getString(R.string.movie_releases_on, ReleaseStatus.shortDate(context, date))
    }

    /**
     * Plays [entry] from anywhere in the Queue. A show that has no episode picked yet (Watchlist,
     * Spin) starts at its first episode you haven't watched; [onBusy] brackets the lookup.
     */
    fun playResolving(fragment: Fragment, entry: QueueEntry, onBusy: (Boolean) -> Unit = {}) {
        val context = fragment.requireContext()
        if (isUnreleased(entry)) {
            openDetails(fragment.findNavController(), entry)
            return
        }
        if (!entry.isTv || entry.episode != null) {
            play(context, fragment.findNavController(), entry)
            return
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            onBusy(true)
            val resolved = firstEpisodeOf(context, entry)
            onBusy(false)
            if (resolved != null) {
                play(context, fragment.findNavController(), resolved)
            } else {
                Toast.makeText(context, R.string.queue_no_episode, Toast.LENGTH_LONG).show()
                openDetails(fragment.findNavController(), entry)
            }
        }
    }

    /** The first episode of [entry] that isn't watched (season 1 first, specials last), or null. */
    suspend fun firstEpisodeOf(context: Context, entry: QueueEntry): QueueEntry? = withContext(Dispatchers.IO) {
        val provider = UserPreferences.currentProvider ?: return@withContext null
        val show = runCatching { provider.getTvShow(entry.id) }.getOrNull() ?: return@withContext null
        val watched = AppDatabase.getInstance(context).episodeDao().getByTvShowId(entry.id)
            .filter { it.isWatched }.map { it.id }.toSet()
        val seasons = show.seasons.sortedWith(compareBy({ it.number == 0 }, { it.number }))
        for (season in seasons) {
            val episodes = runCatching { provider.getEpisodesBySeason(season.id) }.getOrNull().orEmpty()
                .sortedBy { it.number }
            val next = episodes.firstOrNull { it.id !in watched && !ReleaseStatus.isUnreleased(it.released) }
                ?: continue
            next.tvShow = show
            next.season = season
            return@withContext entry.copy(
                episode = next,
                seasonNumber = season.number,
                episodeNumber = next.number,
                tvShow = entry.tvShow ?: show,
            )
        }
        null
    }

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
