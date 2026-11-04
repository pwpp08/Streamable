package com.streamflixreborn.streamflix.fragments.player.engine

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * Notices a source that keeps stalling (the free CDNs behind most servers are often slower than
 * their top rung needs) and asks for the next server instead of letting the viewer sit through
 * endless buffering. Seeks and the initial load are not counted.
 */
class StallWatchdog(private val player: Player) : Player.Listener {

    /** Called on the main thread once the current source is judged too slow. */
    var onStalled: (() -> Unit)? = null

    private var started = false
    private var seeking = false
    private var stallStartedAt = 0L
    private val stalls = ArrayDeque<Pair<Long, Long>>() // end time to duration
    private var triggers = 0

    fun reset() {
        started = false
        seeking = false
        stallStartedAt = 0L
        stalls.clear()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            seeking = true
        }
    }

    /** A new source (server switch, next episode) starts with its own initial load. */
    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = reset()

    override fun onPlaybackStateChanged(state: Int) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_READY -> {
                if (stallStartedAt != 0L && started && !seeking) record(now)
                stallStartedAt = 0L
                started = true
                seeking = false
            }
            Player.STATE_BUFFERING -> {
                if (started && !seeking && player.playWhenReady) stallStartedAt = now
            }
            else -> stallStartedAt = 0L
        }
    }

    private fun record(now: Long) {
        stalls.addLast(now to (now - stallStartedAt))
        while (stalls.isNotEmpty() && now - stalls.first().first > WINDOW_MS) stalls.removeFirst()
        val total = stalls.sumOf { it.second }
        if (triggers < MAX_TRIGGERS && (total >= TOTAL_STALL_MS || stalls.last().second >= SINGLE_STALL_MS)) {
            triggers++
            stalls.clear()
            onStalled?.invoke()
        }
    }

    companion object {
        private const val WINDOW_MS = 90_000L
        private const val TOTAL_STALL_MS = 10_000L
        private const val SINGLE_STALL_MS = 12_000L
        private const val MAX_TRIGGERS = 2
    }
}

/** Servers that stalled recently; the ranker tries them last for a while. */
object StallMemory {
    private const val PREFS = "server_stall_memory"
    private const val TTL_MS = 30 * 60_000L

    private fun prefs() = StreamFlixApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(server: String, now: Long = System.currentTimeMillis()) {
        if (server.isBlank()) return
        prefs().edit().putLong(server.trim().lowercase(), now).apply()
    }

    fun isRecent(server: String, now: Long = System.currentTimeMillis()): Boolean =
        now - prefs().getLong(server.trim().lowercase(), 0L) < TTL_MS
}
