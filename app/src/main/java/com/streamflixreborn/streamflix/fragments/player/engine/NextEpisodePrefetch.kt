package com.streamflixreborn.streamflix.fragments.player.engine

import android.os.SystemClock
import com.streamflixreborn.streamflix.models.Video

/**
 * Servers (and already resolved videos) for the next episode, prepared while the current one
 * plays. The next episode opens in a new player screen/ViewModel, so this lives process-wide.
 */
object NextEpisodePrefetch {

    private const val MAX_AGE_MS = 10 * 60_000L

    class Entry(
        val episodeId: String,
        val providerName: String,
        val servers: List<Video.Server>,
        val resolved: Map<String, Video>,
        val createdAt: Long = SystemClock.elapsedRealtime(),
    )

    @Volatile
    private var entry: Entry? = null

    fun put(value: Entry) {
        entry = value
    }

    fun isPrepared(episodeId: String, providerName: String): Boolean =
        entry?.let { it.episodeId == episodeId && it.providerName == providerName && fresh(it) } == true

    /** Returns and clears the entry for [episodeId] if it is still fresh. */
    fun take(episodeId: String, providerName: String): Entry? {
        val current = entry ?: return null
        if (current.episodeId != episodeId || current.providerName != providerName) return null
        entry = null
        return current.takeIf(::fresh)
    }

    private fun fresh(entry: Entry) = SystemClock.elapsedRealtime() - entry.createdAt < MAX_AGE_MS
}
