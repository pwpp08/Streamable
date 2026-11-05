package com.streamflixreborn.streamflix.downloads

import android.content.Context
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.sync.SyncProviders
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format

/**
 * "Download a whole season (or the whole show)": builds the same [DownloadStarter.Target] the
 * episode buttons use and queues them. Quality follows the usual download rules (1080p, or 720p
 * with "Save space").
 */
object SeasonDownloads {

    fun targetFor(context: Context, episode: Episode): DownloadStarter.Target? {
        val providerName = episode.tvShow?.providerName ?: UserPreferences.currentProvider?.name ?: return null
        return DownloadStarter.Target(
            providerName = providerName,
            videoType = Video.Type.Episode(
                id = episode.id,
                number = episode.number,
                title = episode.title,
                poster = episode.poster,
                overview = episode.overview,
                tvShow = Video.Type.Episode.TvShow(
                    id = episode.tvShow?.id ?: "",
                    title = episode.tvShow?.title ?: "",
                    poster = episode.tvShow?.poster,
                    banner = episode.tvShow?.banner,
                    releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                    imdbId = episode.tvShow?.imdbId,
                ),
                season = Video.Type.Episode.Season(
                    number = episode.season?.number ?: 0,
                    title = episode.season?.title,
                ),
            ),
            title = episode.tvShow?.title ?: "",
            subtitle = playerSubtitle(context, episode),
            poster = episode.poster ?: episode.tvShow?.poster,
        )
    }

    /** "S1 E2 • Title", the line the player shows. */
    fun playerSubtitle(context: Context, episode: Episode): String =
        episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.player_subtitle_tv_show,
                season.number,
                episode.number,
                episode.title ?: context.getString(R.string.episode_number, episode.number),
            )
        } ?: context.getString(
            R.string.player_subtitle_tv_show_episode_only,
            episode.number,
            episode.title ?: context.getString(R.string.episode_number, episode.number),
        )

    /** Queues every episode that is not downloaded or running yet; returns how many were queued. */
    fun downloadEpisodes(context: Context, episodes: List<Episode>): Int {
        val app = context.applicationContext
        if (!DownloadUtil.isSupported(app)) return 0
        DownloadTracker.ensureStarted(app)
        val targets = episodes
            .sortedWith(compareBy({ it.season?.number ?: 0 }, { it.number }))
            .mapNotNull { targetFor(app, it) }
            .filter { target ->
                when (DownloadTracker.item(target.id)?.status) {
                    null, DownloadTracker.Status.NONE, DownloadTracker.Status.FAILED -> true
                    else -> false
                }
            }
        if (targets.isEmpty()) return 0
        SyncProviders.switchTo(targets.first().providerName)
        DownloadStarter.startAll(app, targets)
        return targets.size
    }

    /** Loads the episodes of [seasons] from the show's provider and queues them. */
    suspend fun downloadSeasons(context: Context, tvShow: TvShow, seasons: List<Season>): Int {
        SyncProviders.switchTo(tvShow.providerName)
        val provider = UserPreferences.currentProvider ?: return 0
        val episodes = seasons.flatMap { season ->
            runCatching { provider.getEpisodesBySeason(season.id) }.getOrDefault(emptyList()).onEach {
                it.tvShow = tvShow
                it.season = season
            }
        }
        return downloadEpisodes(context, episodes)
    }
}
