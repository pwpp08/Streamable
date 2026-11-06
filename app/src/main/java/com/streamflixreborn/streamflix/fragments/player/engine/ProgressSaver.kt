package com.streamflixreborn.streamflix.fragments.player.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.fragments.queue.WatchEvents
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.sync.CloudSyncHooks
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.UserDataCache
import com.streamflixreborn.streamflix.utils.UserPreferences
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Persists watch progress (Room + [UserDataCache] + [CloudSyncHooks]).
 *
 * - [saveNow] is the pause/stop path and blocks briefly so the write survives the
 *   firmware killing the app right after a Home press.
 * - [start]/[stop] save every 15 s while playing.
 *
 * All writes go through one background thread, so they never interleave.
 */
class ProgressSaver(
    context: Context,
    private val database: AppDatabase,
    private val videoType: () -> Video.Type,
) {
    data class Snapshot(val positionMs: Long, val durationMs: Long) {
        /** Same thresholds the player used before. */
        val hasStarted get() = positionMs > durationMs * 0.005 || positionMs > 20_000
        val hasFinished get() = positionMs > durationMs * 0.90
        val hasReallyFinished get() =
            durationMs > 0 && positionMs >= durationMs - UserPreferences.autoplayBuffer * 1000

        companion object {
            fun of(player: Player) = Snapshot(player.currentPosition, player.duration)
        }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            val player = player ?: return
            if (!running) return
            if (player.isPlaying) {
                val snapshot = Snapshot.of(player)
                // Finished items are handled once by the pause/stop path.
                if (snapshot.durationMs > 0 && snapshot.hasStarted && !snapshot.hasFinished) {
                    val type = videoType()
                    executor.execute { runCatching { persist(type, snapshot) }.onFailure { Log.e(TAG, "periodic save failed", it) } }
                }
            }
            mainHandler.postDelayed(this, PERIODIC_INTERVAL_MS)
        }
    }

    fun start(player: Player) {
        this.player = player
        if (running) return
        running = true
        mainHandler.postDelayed(tick, PERIODIC_INTERVAL_MS)
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(tick)
    }

    fun release() {
        stop()
        player = null
    }

    /** Saves the current position and waits (bounded) for the write to finish. */
    fun saveNow(snapshot: Snapshot) {
        val type = videoType()
        val future = executor.submit { persist(type, snapshot) }
        runCatching { future.get(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .onFailure { Log.e(TAG, "save failed or timed out", it) }
    }

    /** Marks the title as just played (recently watched rows). */
    fun recordStart(type: Video.Type) {
        executor.execute { runCatching { recordRecentlyWatchedStart(type) }.onFailure { Log.e(TAG, "recordStart failed", it) } }
    }

    private fun persist(videoType: Video.Type, snapshot: Snapshot) {
        val watchItem: WatchItem? = when (videoType) {
            is Video.Type.Movie -> database.movieDao().getById(videoType.id)
            is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
        }

        val wasWatched = watchItem?.isWatched == true
        when {
            snapshot.hasStarted && !snapshot.hasFinished -> {
                watchItem?.isWatched = false
                watchItem?.watchedDate = null
                watchItem?.watchHistory = WatchItem.WatchHistory(
                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                    lastPlaybackPositionMillis = snapshot.positionMs,
                    durationMillis = snapshot.durationMs,
                )
            }

            snapshot.hasFinished -> {
                watchItem?.isWatched = true
                watchItem?.watchedDate = Calendar.getInstance()
                watchItem?.watchHistory = null
            }
        }

        val provider = UserPreferences.currentProvider ?: return
        when (videoType) {
            is Video.Type.Movie -> {
                (watchItem as? Movie)?.let {
                    database.movieDao().update(it)
                    UserDataCache.syncMovieToCache(appContext, provider, it)
                    // Finished just now: ask how it was (once; the screen on top shows the popup).
                    if (snapshot.hasFinished && !wasWatched) WatchEvents.movieWatched(it)
                }
            }

            is Video.Type.Episode -> {
                (watchItem as? Episode)?.let { episode ->
                    if (snapshot.hasFinished) {
                        database.episodeDao().resetProgressionFromEpisode(videoType.id)
                        UserDataCache.removeEpisodeFromContinueWatching(appContext, provider, episode.id)
                        queueNextEpisodeForContinueWatching(provider)
                    }
                    database.episodeDao().update(episode)
                    if (!snapshot.hasFinished) {
                        UserDataCache.syncEpisodeToCache(appContext, provider, episode)
                    }

                    episode.tvShow?.let { tvShow ->
                        database.tvShowDao().getById(tvShow.id)
                    }?.let { tvShow ->
                        val isStillWatching = database.episodeDao().hasAnyWatchHistoryForTvShow(tvShow.id)
                        val updatedTvShow = tvShow.copy().apply {
                            merge(tvShow)
                            isWatching = !snapshot.hasReallyFinished || isStillWatching
                        }
                        database.tvShowDao().update(updatedTvShow)
                        CloudSyncHooks.tvShow(appContext, provider, updatedTvShow)
                    }
                }
            }
        }
    }

    private fun queueNextEpisodeForContinueWatching(provider: Provider) {
        val nextEpisode = EpisodeManager.peekNextEpisode() ?: return
        val episodeDao = database.episodeDao()
        val persistedNextEpisode = episodeDao.getById(nextEpisode.id)?.apply {
            isWatched = false
            watchedDate = null
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                lastPlaybackPositionMillis = 0L,
                durationMillis = 0L,
            )
        } ?: Episode(
            id = nextEpisode.id,
            number = nextEpisode.number,
            title = nextEpisode.title,
            poster = nextEpisode.poster,
            overview = nextEpisode.overview,
            tvShow = database.tvShowDao().getById(nextEpisode.tvShow.id) ?: TvShow(
                id = nextEpisode.tvShow.id,
                title = nextEpisode.tvShow.title,
                poster = nextEpisode.tvShow.poster,
                banner = nextEpisode.tvShow.banner,
            ),
            season = Season(
                number = nextEpisode.season.number,
                title = nextEpisode.season.title,
            ),
        ).apply {
            isWatched = false
            watchedDate = null
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                lastPlaybackPositionMillis = 0L,
                durationMillis = 0L,
            )
        }

        episodeDao.save(persistedNextEpisode)
        UserDataCache.syncEpisodeToCache(appContext, provider, persistedNextEpisode)
    }

    private fun recordRecentlyWatchedStart(videoType: Video.Type) {
        val playedAtMillis = System.currentTimeMillis()
        when (videoType) {
            is Video.Type.Movie -> {
                if (database.movieDao().markRecentlyWatched(videoType.id, playedAtMillis) == 0) {
                    database.movieDao().insert(
                        Movie(
                            id = videoType.id,
                            title = videoType.title,
                            released = videoType.releaseDate,
                            poster = videoType.poster,
                            imdbId = videoType.imdbId,
                        ).apply {
                            lastPlayedAtMillis = playedAtMillis
                        }
                    )
                }
            }

            is Video.Type.Episode -> {
                val storedTvShow = database.tvShowDao().getById(videoType.tvShow.id)
                    ?: TvShow(
                        id = videoType.tvShow.id,
                        title = videoType.tvShow.title,
                        released = videoType.tvShow.releaseDate,
                        poster = videoType.tvShow.poster,
                        banner = videoType.tvShow.banner,
                        imdbId = videoType.tvShow.imdbId,
                    ).apply {
                        lastPlayedAtMillis = playedAtMillis
                        lastPlayedEpisodeId = videoType.id
                        database.tvShowDao().insert(this)
                    }

                if (database.episodeDao().getById(videoType.id) == null) {
                    database.episodeDao().insert(
                        Episode(
                            id = videoType.id,
                            number = videoType.number,
                            title = videoType.title,
                            poster = videoType.poster,
                            overview = videoType.overview,
                            tvShow = storedTvShow,
                            season = Season(
                                number = videoType.season.number,
                                title = videoType.season.title.orEmpty(),
                            ),
                        )
                    )
                }
                database.tvShowDao().markRecentlyWatched(
                    id = videoType.tvShow.id,
                    episodeId = videoType.id,
                    playedAtMillis = playedAtMillis,
                )
            }
        }
    }

    companion object {
        private const val TAG = "ProgressSaver"
        const val PERIODIC_INTERVAL_MS = 15_000L
        private const val SYNC_TIMEOUT_MS = 2_000L

        /** Single writer shared by all player screens. */
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "progress-saver").apply { isDaemon = true }
        }
    }
}
