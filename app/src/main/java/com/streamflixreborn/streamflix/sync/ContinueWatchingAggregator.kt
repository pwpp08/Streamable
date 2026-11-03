package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.providers.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * In-progress movies and episodes across every provider database of a
 * profile (`{profile}_{provider}.db`), using the same DAO queries as
 * HomeViewModel. Items get `providerName` set (Movie.providerName,
 * Episode.tvShow.providerName) so a click can switch provider first.
 */
object ContinueWatchingAggregator {
    private const val TAG = "CWAggregator"

    data class Entry(
        val providerName: String,
        val item: AppAdapter.Item,
        val mediaType: String,
        val mediaId: String,
        val lastEngagementMillis: Long,
        val positionMs: Long,
        val durationMs: Long,
    ) {
        val showKey: String
            get() = when (item) {
                is Episode -> "$providerName:show:${item.tvShow?.id ?: mediaId}"
                else -> "$providerName:movie:$mediaId"
            }
    }

    /**
     * Entries newest first. [excludeProvider] skips one provider (Home already
     * shows the current one with its own cache-first logic).
     */
    suspend fun inProgress(
        context: Context,
        profileId: String,
        excludeProvider: String? = null,
    ): List<Entry> = withContext(Dispatchers.IO) {
        SyncProviders.withDatabase(context.applicationContext, profileId)
            .filter { it.name != excludeProvider }
            .flatMap { provider ->
                try {
                    read(context.applicationContext, provider, profileId)
                } catch (error: Exception) {
                    Log.w(TAG, "Skipping ${provider.name}", error)
                    emptyList()
                }
            }
            .sortedByDescending { it.lastEngagementMillis }
    }

    /** Newest entry per show (or movie), newest first. */
    fun onePerShow(entries: List<Entry>): List<Entry> = entries.distinctBy { it.showKey }

    private suspend fun read(context: Context, provider: Provider, profileId: String): List<Entry> {
        val db = AppDatabase.getInstanceForProvider(provider.name, context, profileId)
        try {
            val movies = db.movieDao().getWatchingMovies().first().mapNotNull { movie ->
                movie.providerName = provider.name
                entry(provider.name, movie, "movie", movie.id, movie.watchHistory)
            }
            val episodes = db.episodeDao().getWatchingEpisodes().first().mapNotNull { episode ->
                val show = episode.tvShow?.id?.let { db.tvShowDao().getById(it) } ?: episode.tvShow
                show?.providerName = provider.name
                episode.tvShow = show
                episode.season = episode.season?.id?.let { db.seasonDao().getById(it) } ?: episode.season
                entry(provider.name, episode, "episode", episode.id, episode.watchHistory)
            }
            return movies + episodes
        } finally {
            db.close()
        }
    }

    private fun entry(
        providerName: String,
        item: AppAdapter.Item,
        type: String,
        id: String,
        history: WatchItem.WatchHistory?,
    ): Entry? {
        history ?: return null
        return Entry(
            providerName = providerName,
            item = item,
            mediaType = type,
            mediaId = id,
            lastEngagementMillis = history.lastEngagementTimeUtcMillis,
            positionMs = history.lastPlaybackPositionMillis,
            durationMs = history.durationMillis,
        )
    }

    /** Builds a Play request that reopens [entry] on this device. */
    fun toPlay(entry: Entry): DeviceCommand.Play? = when (val item = entry.item) {
        is Movie -> PlaybackLauncher.playFor(entry.providerName, item, entry.positionMs, entry.durationMs)
        is Episode -> PlaybackLauncher.playFor(entry.providerName, item, entry.positionMs, entry.durationMs)
        else -> null
    }
}
