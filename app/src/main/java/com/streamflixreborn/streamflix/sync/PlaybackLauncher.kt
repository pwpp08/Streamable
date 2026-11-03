package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.navigation.NavController
import androidx.navigation.navOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.UserDataCache
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens the player for a [DeviceCommand.Play] on the current device: switches
 * provider when needed, fills missing metadata from the local database and
 * navigates with the regular player Safe Args (via `action_global_player`).
 *
 * The start position is handed over through [PlayerStartPosition]. Until the
 * player reads that hook, the position is also written to the local watch
 * history (when the duration is known), which the player already resumes
 * from (minus 10 s).
 */
object PlaybackLauncher {
    private const val TAG = "PlaybackLauncher"
    private const val SEED_TOLERANCE_MS = 30_000L

    enum class Outcome { STARTED, NO_PROFILE, UNKNOWN_PROVIDER }

    suspend fun launch(context: Context, navController: NavController, play: DeviceCommand.Play): Outcome {
        val profileId = ProfileManager.activeProfileId ?: return Outcome.NO_PROFILE
        val provider = SyncProviders.byName(play.provider) ?: return Outcome.UNKNOWN_PROVIDER
        SyncProviders.switchTo(provider.name)

        val resolved = withContext(Dispatchers.IO) {
            runCatching { resolveAndSeed(context.applicationContext, profileId, play) }
                .onFailure { Log.w(TAG, "Could not read local metadata", it) }
                .getOrDefault(play)
        }
        PlayerStartPosition.set(resolved.mediaId, resolved.positionMs)

        val current = navController.currentDestination?.id
        if (current == R.id.profiles || current == R.id.providers) {
            navController.navigate(R.id.home, null, navOptions {
                launchSingleTop = true
                popUpTo(R.id.profiles) { inclusive = true }
            })
        }
        val options = if (navController.currentDestination?.id == R.id.player) {
            navOptions { popUpTo(R.id.player) { inclusive = true } }
        } else {
            null
        }
        navController.navigate(R.id.action_global_player, playerArgs(context, resolved), options)
        return Outcome.STARTED
    }

    private fun playerArgs(context: Context, play: DeviceCommand.Play): Bundle {
        val bundle = Bundle()
        bundle.putString("id", play.mediaId)
        bundle.putString("preferredServerName", play.serverName)
        if (play.isEpisode) {
            val number = play.episodeNumber ?: 0
            val episodeTitle = play.episodeTitle ?: context.getString(R.string.episode_number, number)
            val season = play.seasonNumber ?: 0
            bundle.putString("title", play.title)
            bundle.putString(
                "subtitle",
                if (season != 0) {
                    context.getString(R.string.player_subtitle_tv_show, season, number, episodeTitle)
                } else {
                    context.getString(R.string.player_subtitle_tv_show_episode_only, number, episodeTitle)
                },
            )
            bundle.putSerializable(
                "videoType",
                Video.Type.Episode(
                    id = play.mediaId,
                    number = number,
                    title = play.episodeTitle,
                    poster = play.poster,
                    overview = null,
                    tvShow = Video.Type.Episode.TvShow(
                        id = play.showId.orEmpty(),
                        title = play.title,
                        poster = play.poster,
                        banner = play.banner,
                        releaseDate = play.releaseDate,
                        imdbId = play.imdbId,
                    ),
                    season = Video.Type.Episode.Season(number = season, title = play.seasonTitle),
                ),
            )
        } else {
            bundle.putString("title", play.title)
            bundle.putString("subtitle", play.releaseDate?.take(4).orEmpty())
            bundle.putSerializable(
                "videoType",
                Video.Type.Movie(
                    id = play.mediaId,
                    title = play.title,
                    releaseDate = play.releaseDate.orEmpty(),
                    poster = play.poster ?: play.banner.orEmpty(),
                    imdbId = play.imdbId,
                ),
            )
        }
        return bundle
    }

    /** Completes [play] from the provider database and seeds the resume position. */
    private fun resolveAndSeed(context: Context, profileId: String, play: DeviceCommand.Play): DeviceCommand.Play {
        val provider = SyncProviders.byName(play.provider) ?: return play
        val db = AppDatabase.getInstanceForProvider(provider.name, context, profileId)
        try {
            return if (play.isEpisode) {
                val episode = db.episodeDao().getById(play.mediaId)
                val showId = play.showId ?: episode?.tvShow?.id
                val show = showId?.let { db.tvShowDao().getById(it) }
                val season = episode?.season?.id?.let { db.seasonDao().getById(it) }
                if (episode != null && seedHistory(episode, play)) {
                    db.episodeDao().update(episode)
                    UserDataCache.writeEpisodes(context, provider, db.episodeDao().getAllForBackup())
                }
                play.copy(
                    showId = showId,
                    title = play.title.ifBlank { show?.title.orEmpty() },
                    seasonNumber = play.seasonNumber ?: season?.number,
                    seasonTitle = play.seasonTitle ?: season?.title,
                    episodeNumber = play.episodeNumber ?: episode?.number,
                    episodeTitle = play.episodeTitle ?: episode?.title,
                    poster = play.poster ?: show?.poster ?: episode?.poster,
                    banner = play.banner ?: show?.banner,
                    releaseDate = play.releaseDate ?: show?.released?.format("yyyy-MM-dd"),
                )
            } else {
                val movie = db.movieDao().getById(play.mediaId)
                if (movie != null && seedHistory(movie, play)) {
                    db.movieDao().update(movie)
                    UserDataCache.writeMovies(context, provider, db.movieDao().getAll())
                }
                play.copy(
                    title = play.title.ifBlank { movie?.title.orEmpty() },
                    poster = play.poster ?: movie?.poster,
                    banner = play.banner ?: movie?.banner,
                    releaseDate = play.releaseDate ?: movie?.released?.format("yyyy-MM-dd"),
                )
            }
        } finally {
            db.close()
        }
    }

    private fun seedHistory(item: WatchItem, play: DeviceCommand.Play): Boolean {
        if (play.positionMs <= 0) return false
        val history = item.watchHistory
        val duration = play.durationMs?.takeIf { it > 0 } ?: history?.durationMillis?.takeIf { it > 0 } ?: return false
        if (history != null && kotlin.math.abs(history.lastPlaybackPositionMillis - play.positionMs) < SEED_TOLERANCE_MS) {
            return false
        }
        item.watchHistory = WatchItem.WatchHistory(
            lastEngagementTimeUtcMillis = System.currentTimeMillis(),
            lastPlaybackPositionMillis = play.positionMs,
            durationMillis = duration,
        )
        return true
    }

    /** Builds a Play request for a locally known movie or episode. */
    fun playFor(providerName: String, item: Movie, positionMs: Long, durationMs: Long?): DeviceCommand.Play =
        DeviceCommand.Play(
            provider = providerName,
            mediaType = DeviceCommand.Play.MEDIA_MOVIE,
            mediaId = item.id,
            title = item.title,
            positionMs = positionMs,
            durationMs = durationMs,
            poster = item.poster,
            banner = item.banner,
            releaseDate = item.released?.format("yyyy-MM-dd"),
            imdbId = item.imdbId,
        )

    fun playFor(providerName: String, item: Episode, positionMs: Long, durationMs: Long?): DeviceCommand.Play =
        DeviceCommand.Play(
            provider = providerName,
            mediaType = DeviceCommand.Play.MEDIA_EPISODE,
            mediaId = item.id,
            showId = item.tvShow?.id,
            seasonNumber = item.season?.number?.takeIf { it > 0 || item.season?.id != null },
            episodeNumber = item.number,
            title = item.tvShow?.title.orEmpty(),
            positionMs = positionMs,
            durationMs = durationMs,
            episodeTitle = item.title,
            seasonTitle = item.season?.title,
            poster = item.tvShow?.poster ?: item.poster,
            banner = item.tvShow?.banner,
            releaseDate = item.tvShow?.released?.format("yyyy-MM-dd"),
            imdbId = item.tvShow?.imdbId,
        )
}
