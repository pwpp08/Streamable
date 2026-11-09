package com.streamflixreborn.streamflix.fragments.player.engine

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.torrent.TorrentSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A player that is ready, or getting ready, to take over from the one on screen. */
class Standby(
    val built: PlayerFactory.Result,
    val dataSources: StreamDataSourceFactory,
    val video: Video,
    val server: Video.Server,
) {
    val player: ExoPlayer get() = built.player

    fun release() {
        runCatching { built.player.release() }
    }
}

/**
 * Changes the source that is playing without the picture or the sound noticing. The next source is
 * prepared in a second, silent player while the first one carries on; once it has buffered a few
 * seconds it takes over at an exact position (a short audio crossfade hides the cut), and only then
 * is the old player released. If anything goes wrong, the old one simply keeps playing.
 */
@OptIn(UnstableApi::class)
class SeamlessSwitch(
    private val scope: CoroutineScope,
    private val host: Host,
) {

    interface Host {
        /** The player on screen; null before the first one exists. */
        val player: ExoPlayer?

        /** Builds a muted, paused player for [video] that is preparing from [startMs]. */
        fun createStandby(video: Video, server: Video.Server, startMs: Long, snapshot: PlaybackSnapshot?): Standby

        fun mainUsesSoftwareDecoder(): Boolean

        /** [standby] becomes the player on screen; it plays if [play]. Returns the player that was replaced. */
        fun commit(standby: Standby, play: Boolean): ExoPlayer

        /** The two-player way is not possible with this stream; switch on the same player instead. */
        fun fallBack(video: Video, server: Video.Server)

        /** The source could not be prepared; the old one keeps playing. */
        fun failed(server: Video.Server)
    }

    private var job: Job? = null
    private var pending: Standby? = null

    val isPreparing: Boolean get() = job?.isActive == true

    fun cancel() {
        job?.cancel()
        job = null
        pending?.release()
        pending = null
    }

    fun start(video: Video, server: Video.Server, snapshot: PlaybackSnapshot?) {
        cancel()
        job = scope.launch { runCatching { run(video, server, snapshot) }.onFailure { if (it !is kotlinx.coroutines.CancellationException) fail(server, it) } }
    }

    private fun fail(server: Video.Server, error: Throwable?) {
        Log.w(TAG, "switch to ${server.name} failed", error)
        pending?.release()
        pending = null
        host.failed(server)
    }

    private suspend fun run(video: Video, server: Video.Server, snapshot: PlaybackSnapshot?) {
        val main = host.player ?: return
        val torrent = TorrentSpec.isTorrentUri(video.source)
        val started = SystemClock.elapsedRealtime()
        val deadline = started + if (torrent) TORRENT_TIMEOUT_MS else WEB_TIMEOUT_MS

        var startMs = main.currentPosition.coerceAtLeast(0) + if (main.isPlaying) SwitchPlan.startLeadMs(torrent) else 0
        val duration = main.duration
        if (duration > 0) startMs = minOf(startMs, maxOf(0, duration - 5_000))
        val standby = host.createStandby(video, server, startMs, snapshot)
        pending = standby
        val p = standby.player
        Log.d(TAG, "preparing ${server.name} from $startMs ms while ${if (main.isPlaying) "playing at ${main.currentPosition}" else "not playing"}")

        // 1. Ready: first frame decoded, a few seconds buffered.
        while (true) {
            currentCoroutineContext().ensureActive()
            if (host.player !== main) return drop()
            if (p.playerError != null) return fail(server, p.playerError)
            if (p.playbackState == Player.STATE_READY && p.bufferedPosition >= startMs + SwitchPlan.MIN_AHEAD_MS.coerceAtMost(1_500)) break
            if (SystemClock.elapsedRealtime() > deadline) return fail(server, null)
            delay(POLL_MS)
        }
        if (standby.built.decoder.isSoftware && !host.mainUsesSoftwareDecoder()) {
            // Only a software decoder was left for the second player; it would not keep up for a whole film.
            Log.w(TAG, "${server.name}: no hardware decoder for a second player, switching on the same player")
            pending = null
            standby.release()
            return host.fallBack(video, server)
        }
        Log.d(TAG, "${server.name} ready after ${SystemClock.elapsedRealtime() - started} ms")

        // 2. Choose where to cut over, and wait for the picture to get there.
        var attempt = 0
        var standbyAt = startMs
        while (attempt < MAX_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            val m = host.player
            if (m !== main) return drop()
            if (p.playerError != null) return fail(server, p.playerError)
            if (!isAdvancing(m)) return cutOverNow(standby, m, standbyAt, deadline)

            val point = SwitchPlan.swapPoint(m.currentPosition, standbyAt, p.bufferedPosition, m.duration, attempt)
            if (point == null) {
                if (SystemClock.elapsedRealtime() > deadline) return fail(server, null)
                delay(POLL_MS)
                continue
            }
            if (point != standbyAt) {
                p.seekTo(point)
                if (!awaitReady(p, deadline)) return fail(server, p.playerError)
                standbyAt = point
            }
            when (awaitPoint(m, point)) {
                Arrival.REACHED -> return commit(standby, play = true)
                Arrival.STOPPED -> return cutOverNow(standby, m, standbyAt, deadline)
                Arrival.MISSED -> attempt++
            }
        }
        cutOverNow(standby, host.player ?: return drop(), standbyAt, deadline)
    }

    /** The picture is not moving (paused, buffering, or broken): there is nothing to match, so cut over at once. */
    private suspend fun cutOverNow(standby: Standby, main: ExoPlayer, standbyAt: Long, deadline: Long) {
        val p = standby.player
        val at = main.currentPosition.coerceAtLeast(0)
        if (kotlin.math.abs(at - standbyAt) > 300) {
            p.seekTo(at)
            if (!awaitReady(p, deadline)) return fail(standby.server, p.playerError)
        }
        commit(standby, play = main.playWhenReady && main.playerError == null)
    }

    private fun commit(standby: Standby, play: Boolean) {
        pending = null
        job = null
        val old = host.commit(standby, play)
        scope.launch { crossfade(old, standby.player) }
        Log.d(TAG, "${standby.server.name} took over at ${standby.player.currentPosition} ms")
    }

    private fun drop() {
        pending?.release()
        pending = null
    }

    private suspend fun awaitReady(p: ExoPlayer, deadline: Long): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (p.playerError != null) return false
            if (p.playbackState == Player.STATE_READY) return true
            if (SystemClock.elapsedRealtime() > deadline) return false
            delay(POLL_MS)
        }
    }

    private enum class Arrival { REACHED, STOPPED, MISSED }

    private fun isAdvancing(m: ExoPlayer) = m.playerError == null && m.playWhenReady &&
        m.playbackState != Player.STATE_IDLE && m.playbackState != Player.STATE_ENDED

    /** Waits until [m] plays through [point]; the message is delivered by the player itself, at that exact position. */
    private suspend fun awaitPoint(m: ExoPlayer, point: Long): Arrival {
        val reached = CompletableDeferred<Unit>()
        val message = m.createMessage { _, _ -> reached.complete(Unit) }
            .setLooper(Looper.getMainLooper())
            .setPosition(point)
            .setDeleteAfterDelivery(true)
            .send()
        var notPlayingSince = 0L
        var last = m.currentPosition
        try {
            while (!reached.isCompleted) {
                currentCoroutineContext().ensureActive()
                delay(POLL_MS)
                val now = SystemClock.elapsedRealtime()
                if (!isAdvancing(m)) return Arrival.STOPPED
                if (m.isPlaying) notPlayingSince = 0L else if (notPlayingSince == 0L) notPlayingSince = now
                if (notPlayingSince != 0L && now - notPlayingSince > STALL_GRACE_MS) return Arrival.STOPPED
                val position = m.currentPosition
                // Sought away, or somehow past the point without the message: choose a new point.
                if (position > point + MISSED_MS || position < last - SEEK_JUMP_MS || position > last + SEEK_JUMP_MS) return Arrival.MISSED
                last = position
            }
            return Arrival.REACHED
        } finally {
            if (!reached.isCompleted) message.cancel()
        }
    }

    /** Old sound out, new sound in, over a fifth of a second; then the old player is let go. */
    private suspend fun crossfade(old: ExoPlayer, new: ExoPlayer) {
        try {
            val steps = FADE_MS / FADE_STEP_MS
            for (i in 1..steps) {
                if (!currentCoroutineContextActive()) break
                val t = i.toFloat() / steps
                runCatching { old.volume = 1f - t }
                runCatching { new.volume = t }
                delay(FADE_STEP_MS)
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { new.volume = 1f }
                runCatching { old.release() }
            }
        }
    }

    private suspend fun currentCoroutineContextActive(): Boolean = currentCoroutineContext().isActive

    companion object {
        private const val TAG = "SFSeamless"
        private const val POLL_MS = 100L
        private const val WEB_TIMEOUT_MS = 40_000L
        private const val TORRENT_TIMEOUT_MS = 150_000L
        private const val MAX_ATTEMPTS = 4
        private const val STALL_GRACE_MS = 1_500L
        private const val MISSED_MS = 400L
        private const val SEEK_JUMP_MS = 2_500L
        private const val FADE_MS = 200L
        private const val FADE_STEP_MS = 20L
    }
}
