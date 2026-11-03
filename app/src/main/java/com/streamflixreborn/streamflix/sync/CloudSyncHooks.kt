package com.streamflixreborn.streamflix.sync

import android.content.Context
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.ProfileManager

object CloudSyncHooks {
    fun movie(context: Context, provider: Provider, movie: Movie) {
        enqueue(context) { userId, now ->
            RemoteMediaState.fromMovie(userId, provider.name, movie, now)
        }
    }

    fun movie(context: Context, provider: Provider, id: String) {
        if (CloudSyncManager.isApplyingRemote) return
        val profileId = ProfileManager.activeProfileId ?: return
        val movie = withDatabase(context, provider, profileId) { it.movieDao().getById(id) }
            ?: Movie(id = id)
        movie(context, provider, movie)
    }

    fun tvShow(context: Context, provider: Provider, show: TvShow) {
        enqueue(context) { userId, now ->
            RemoteMediaState.fromTvShow(userId, provider.name, show, now)
        }
    }

    fun tvShow(context: Context, provider: Provider, id: String) {
        if (CloudSyncManager.isApplyingRemote) return
        val profileId = ProfileManager.activeProfileId ?: return
        val show = withDatabase(context, provider, profileId) { it.tvShowDao().getById(id) }
            ?: TvShow(id = id)
        tvShow(context, provider, show)
    }

    fun episode(context: Context, provider: Provider, episode: Episode) {
        enqueue(context) { userId, now ->
            RemoteMediaState.fromEpisode(userId, provider.name, episode, now)
        }
    }

    fun episode(context: Context, provider: Provider, id: String) {
        if (CloudSyncManager.isApplyingRemote) return
        val profileId = ProfileManager.activeProfileId ?: return
        val episode = withDatabase(context, provider, profileId) { it.episodeDao().getById(id) }
            ?: Episode(id = id)
        episode(context, provider, episode)
    }

    private inline fun enqueue(
        context: Context,
        state: (userId: String, now: Long) -> RemoteMediaState,
    ) {
        if (CloudSyncManager.isApplyingRemote) return
        val profileId = ProfileManager.activeProfileId ?: return
        val userId = CloudSyncManager.currentUserId(profileId)
            ?: CloudAccountStore.activeUserId(context, profileId)
            ?: return
        val mutation = state(userId, System.currentTimeMillis())
            .copy(lastDeviceId = DeviceIdentity.id(context))
        LastDeviceStore.record(context, profileId, listOf(mutation))
        CloudMutationStore.enqueue(
            context.applicationContext,
            profileId,
            mutation,
        )
        CloudSyncScheduler.enqueue(context, profileId, userId)
    }

    /** Opens a short-lived Room instance for [provider] and always closes it. */
    private inline fun <T> withDatabase(
        context: Context,
        provider: Provider,
        profileId: String,
        block: (AppDatabase) -> T?,
    ): T? = runCatching {
        val db = AppDatabase.getInstanceForProvider(provider.name, context, profileId)
        try {
            block(db)
        } finally {
            db.close()
        }
    }.getOrNull()
}
