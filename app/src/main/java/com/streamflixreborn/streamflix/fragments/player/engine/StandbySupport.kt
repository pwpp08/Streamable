package com.streamflixreborn.streamflix.fragments.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/** The server a player is playing, kept per player so two players never blame each other's stream. */
class ServerTag(@Volatile var name: String? = null)

/**
 * A player that is getting the next source ready in the background must not hog the connection or
 * the memory of the one that is playing: it only buffers up to [maxBufferedUs] until it takes over.
 */
class PrepLimit(@Volatile var maxBufferedUs: Long = UNLIMITED) {
    fun lift() {
        maxBufferedUs = UNLIMITED
    }

    companion object {
        const val UNLIMITED = Long.MAX_VALUE
    }
}

/** Forwards everything to [delegate], except that loading stops once [limit] is reached. */
@OptIn(UnstableApi::class)
class LimitedLoadControl(
    private val delegate: LoadControl,
    private val limit: PrepLimit,
) : LoadControl {
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    // The overload Media3 1.11 calls; the default one throws, so it must be forwarded.
    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onTracksSelected(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection>,
    ) = delegate.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        parameters.bufferedDurationUs < limit.maxBufferedUs && delegate.shouldContinueLoading(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = delegate.shouldStartPlayback(parameters)
}

/** Remembers which decoder the video renderer ended up with; a software one is a reason not to run two players. */
@OptIn(UnstableApi::class)
class DecoderWatch : AnalyticsListener {
    @Volatile
    var videoDecoder: String? = null
        private set

    val isSoftware: Boolean get() = isSoftwareDecoder(videoDecoder)

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        videoDecoder = decoderName
    }

    companion object {
        private val SOFTWARE = listOf("omx.google.", "c2.android.", "c2.google.", "omx.ffmpeg", "ffmpeg", "libgav1", "libvpx", "libdav1d")

        fun isSoftwareDecoder(name: String?): Boolean {
            val lower = name?.lowercase() ?: return false
            return SOFTWARE.any { lower.startsWith(it) || lower.contains("ffmpeg") }
        }
    }
}

/**
 * Where the playing stream hands over to the one that was prepared in the background. The cut is
 * made on a position both have: the prepared one's own start when the picture hasn't got there yet,
 * otherwise a moment ahead of the picture, inside what the prepared one has already buffered.
 */
object SwitchPlan {
    /** The playing stream must still be this far before the point when it is scheduled. */
    const val MIN_RUNWAY_MS = 700L

    /** How far ahead of the picture a freshly chosen point lies; grows with every failed attempt. */
    const val SYNC_LEAD_MS = 1_200L

    /** The prepared stream must have buffered this much beyond the point, so it doesn't stall right after. */
    const val MIN_AHEAD_MS = 2_500L

    /**
     * How far ahead of the picture the prepared stream starts. The switch can't happen before the
     * picture gets there, so a fixed generous lead made every switch take at least that long. The
     * lead now follows how long the last few preparations really took (a little more, for safety).
     */
    fun startLeadMs(torrent: Boolean): Long {
        val recent = if (torrent) torrentPrepares else webPrepares
        val typical = synchronized(recent) { if (recent.isEmpty()) null else recent.sorted()[recent.size / 2] }
            ?: return if (torrent) 8_000L else 2_500L
        return if (torrent) (typical + 2_000L).coerceIn(4_000L, 30_000L) else (typical + 800L).coerceIn(1_500L, 10_000L)
    }

    /** How long getting a [torrent] (or web) source ready to play took, from creating the player. */
    fun recordPrepare(torrent: Boolean, elapsedMs: Long) {
        val recent = if (torrent) torrentPrepares else webPrepares
        synchronized(recent) {
            if (recent.size == 5) recent.removeFirst()
            recent.addLast(elapsedMs)
        }
    }

    private val webPrepares = ArrayDeque<Long>()
    private val torrentPrepares = ArrayDeque<Long>()

    /** Null while the prepared stream hasn't buffered far enough past the chosen point. */
    fun swapPoint(
        mainMs: Long,
        standbyStartMs: Long,
        standbyBufferedMs: Long,
        durationMs: Long,
        attempt: Int = 0,
    ): Long? {
        val point = if (mainMs + MIN_RUNWAY_MS <= standbyStartMs) standbyStartMs else mainMs + SYNC_LEAD_MS * (attempt + 1)
        val needed = if (durationMs > 0) minOf(point + MIN_AHEAD_MS, durationMs - 100) else point + MIN_AHEAD_MS
        if (durationMs > 0 && point >= durationMs - 500) return null
        return point.takeIf { standbyBufferedMs >= needed }
    }
}
