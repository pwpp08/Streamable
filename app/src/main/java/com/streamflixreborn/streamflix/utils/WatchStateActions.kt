package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.sync.SyncProviders
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Watch-state edits used by the show options dialogs (Continue Watching row and elsewhere).
 *
 * Every write goes to the item's own provider database (Continue Watching also lists other
 * providers' items, so the current provider is not switched), then through [UserDataCache],
 * which refreshes Home via UserDataNotifier and enqueues the cloud mutation (CloudSyncHooks).
 *
 * Rows are written with `update`/`insert`, not the DAOs' `save`: MovieDao.save and
 * TvShowDao.save keep the stored user state and would drop the change.
 *
 * All functions block on Room (and the season fetch on the network): call them off the main thread.
 */
object WatchStateActions {
    private const val TAG = "WatchStateActions"

    /** Pure rules, unit-tested. */
    object Rules {
        fun markWatched(item: WatchItem, now: Calendar) {
            item.isWatched = true
            item.watchedDate = now
            item.watchHistory = null
        }

        /** Keeps the playback position; only the watched flag goes. */
        fun markUnwatched(item: WatchItem) {
            item.isWatched = false
            item.watchedDate = null
        }

        fun clearProgress(item: WatchItem) {
            item.watchHistory = null
        }

        fun providerNameOf(item: AppAdapter.Item): String? = when (item) {
            is Movie -> item.providerName
            is TvShow -> item.providerName
            is Episode -> item.tvShow?.providerName
            else -> null
        }?.takeIf { it.isNotBlank() }

        fun isOtherProvider(item: AppAdapter.Item, currentProviderName: String?): Boolean {
            val name = providerNameOf(item) ?: return false
            return name != currentProviderName
        }

        /**
         * The episodes of one season to store: [remote] (fresh metadata from the provider) with the
         * user state of the matching [local] rows, plus local rows the provider did not return.
         * Every result points at [tvShowId] / [seasonId].
         */
        fun seasonEpisodes(
            local: List<Episode>,
            remote: List<Episode>,
            tvShowId: String,
            seasonId: String,
        ): List<Episode> {
            val localById = local.associateBy { it.id }
            val remoteIds = remote.map { it.id }.toSet()
            val merged = remote.map { episode ->
                localById[episode.id]?.let { episode.merge(it) }
                episode
            } + local.filter { it.id !in remoteIds }
            val show = TvShow(tvShowId)
            val season = Season(seasonId)
            return merged
                .distinctBy { it.id }
                .onEach { episode ->
                    if (episode.tvShow?.id != tvShowId) episode.tvShow = show
                    if (episode.season?.id != seasonId) episode.season = season
                }
        }

        /** Episodes of the same season up to and including [number]. */
        fun upTo(episodes: List<Episode>, number: Int): List<Episode> = episodes.filter { it.number <= number }
    }

    /** Runs [action] on the IO dispatcher, outliving the dialog that started it. */
    fun launch(context: Context, action: suspend () -> Unit) {
        val scope = context.toActivity()?.lifecycleScope ?: CoroutineScope(SupervisorJob())
        scope.launch(Dispatchers.IO) {
            try {
                action()
            } catch (error: Exception) {
                Log.e(TAG, "Watch state change failed", error)
            }
        }
    }

    /** Items rendered in the Home "Continue Watching" row. */
    fun isContinueWatchingItem(item: AppAdapter.Item): Boolean = runCatching {
        item.itemType in setOf(
            AppAdapter.Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM,
            AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM,
            AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM,
            AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM,
        )
    }.getOrDefault(false)

    fun isOtherProvider(item: AppAdapter.Item): Boolean =
        Rules.isOtherProvider(item, UserPreferences.currentProvider?.name)

    /**
     * Netflix "Remove from row": clears the progress. For an episode, clears it on every episode
     * of the show (the row shows one entry per show) and turns off `TvShow.isWatching`, so the
     * next-episode suggestion does not bring the show back.
     */
    suspend fun removeFromContinueWatching(context: Context, item: AppAdapter.Item) {
        withTarget(context, item) { provider, db ->
            when (item) {
                is Movie -> {
                    val dao = db.movieDao()
                    val stored = dao.getById(item.id)
                    if (stored != null) {
                        Rules.clearProgress(stored)
                        dao.update(stored)
                    }
                    val synced = stored ?: item.copy().merge(item).also(Rules::clearProgress)
                    UserDataCache.syncMovieToCache(context, provider, synced)
                }

                is Episode -> {
                    val dao = db.episodeDao()
                    val stored = dao.getById(item.id)
                    val tvShowId = item.tvShow?.id ?: stored?.tvShow?.id
                    val inProgress = (listOfNotNull(stored) +
                        (tvShowId?.let { dao.getByTvShowId(it) } ?: emptyList()))
                        .filter { it.watchHistory != null }
                        .distinctBy { it.id }
                    inProgress.forEach { episode ->
                        Rules.clearProgress(episode)
                        dao.update(episode)
                        UserDataCache.syncEpisodeToCache(context, provider, episode)
                    }
                    if (stored == null) {
                        val synced = item.copy().merge(item).also(Rules::clearProgress)
                        UserDataCache.syncEpisodeToCache(context, provider, synced)
                    }
                    tvShowId?.let { setWatching(context, provider, db, it, false) }
                }
            }
        }
    }

    /** Mark as watched (clears the progress) or unwatched (keeps it). */
    suspend fun setWatched(context: Context, item: AppAdapter.Item, watched: Boolean) {
        withTarget(context, item) { provider, db ->
            val now = Calendar.getInstance()
            when (item) {
                is Movie -> {
                    val dao = db.movieDao()
                    val stored = dao.getById(item.id)
                    val movie = stored ?: item.copy().merge(item)
                    if (watched) Rules.markWatched(movie, now) else Rules.markUnwatched(movie)
                    if (stored != null) dao.update(movie) else dao.insert(movie)
                    UserDataCache.syncMovieToCache(context, provider, movie)
                }

                is Episode -> {
                    val dao = db.episodeDao()
                    val stored = dao.getById(item.id)
                    val episode = stored ?: item.copy().merge(item)
                    if (watched) Rules.markWatched(episode, now) else Rules.markUnwatched(episode)
                    if (stored != null) dao.update(episode) else dao.insert(episode)
                    UserDataCache.syncEpisodeToCache(context, provider, episode)
                }
            }
        }
    }

    /** "Mark as (un)watched until here": the episodes of the same season up to [episode]. */
    suspend fun setWatchedUntil(context: Context, episode: Episode, watched: Boolean) {
        withTarget(context, episode) { provider, db ->
            val dao = db.episodeDao()
            val stored = dao.getById(episode.id)
            val tvShowId = episode.tvShow?.id ?: stored?.tvShow?.id ?: return@withTarget
            val seasonId = episode.season?.id ?: stored?.season?.id
            val now = Calendar.getInstance()
            Rules.upTo(dao.getEpisodesByTvShowIdAndSeason(tvShowId, seasonId), episode.number)
                .filter { it.isWatched != watched }
                .forEach { ep ->
                    if (watched) Rules.markWatched(ep, now) else Rules.markUnwatched(ep)
                    dao.update(ep)
                    UserDataCache.syncEpisodeToCache(context, provider, ep)
                }
            // Unwatching brings the show back to the next-episode suggestion.
            if (!watched) setWatching(context, provider, db, tvShowId, true)
        }
    }

    /**
     * Marks every episode of [episode]'s season as watched. The season is fetched from the
     * provider so episodes never opened on this device are included; without network the
     * stored episodes are used.
     */
    suspend fun markSeasonWatched(context: Context, episode: Episode) {
        withTarget(context, episode) { provider, db ->
            val dao = db.episodeDao()
            val stored = dao.getById(episode.id)
            val tvShowId = episode.tvShow?.id ?: stored?.tvShow?.id ?: return@withTarget
            val seasonId = episode.season?.id ?: stored?.season?.id ?: return@withTarget
            val remote = try {
                provider.getEpisodesBySeason(seasonId)
            } catch (error: Exception) {
                Log.w(TAG, "Season $seasonId not fetched, using stored episodes", error)
                emptyList()
            }
            val local = dao.getEpisodesByTvShowIdAndSeason(tvShowId, seasonId)
            val now = Calendar.getInstance()
            val episodes = Rules.seasonEpisodes(local, remote, tvShowId, seasonId)
            val changed = episodes.filter { !it.isWatched || it.watchHistory != null }
            episodes.forEach { Rules.markWatched(it, now) }
            dao.insertAll(episodes)
            changed.forEach { UserDataCache.syncEpisodeToCache(context, provider, it) }
        }
    }

    private fun setWatching(context: Context, provider: Provider, db: AppDatabase, tvShowId: String, watching: Boolean) {
        val dao = db.tvShowDao()
        val show = dao.getById(tvShowId) ?: return
        if (show.isWatching == watching) return
        show.isWatching = watching
        dao.update(show)
        UserDataCache.syncTvShowToCache(context, provider, show)
    }

    /**
     * Runs [block] on the database of [item]'s provider: the shared instance for the current
     * provider, a short-lived one (closed afterwards) for another provider.
     */
    private suspend fun withTarget(
        context: Context,
        item: AppAdapter.Item,
        block: suspend (Provider, AppDatabase) -> Unit,
    ) {
        val current = UserPreferences.currentProvider
        val name = Rules.providerNameOf(item)
        if (name == null || name == current?.name) {
            current ?: return
            block(current, AppDatabase.getInstance(context))
            return
        }
        val provider = SyncProviders.byName(name) ?: return
        val db = AppDatabase.getInstanceForProvider(provider.name, context.applicationContext)
        try {
            block(provider, db)
        } finally {
            db.close()
        }
    }
}
