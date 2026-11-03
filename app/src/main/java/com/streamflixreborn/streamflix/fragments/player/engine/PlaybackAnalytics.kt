package com.streamflixreborn.streamflix.fragments.player.engine

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Debug-only playback metrics, logged with the `SFPerf` tag:
 * `adb logcat -s SFPerf`
 */
@OptIn(UnstableApi::class)
class PlaybackAnalytics : AnalyticsListener {

    private var playRequestedAtMs = 0L
    private var firstFrameLogged = false
    private var wasReady = false
    private var rebufferStartedAtMs = 0L
    private var rebufferCount = 0
    private var rebufferTotalMs = 0L
    private var droppedFrames = 0L

    /** Call right before `prepare()` for a new source. */
    fun markPlayRequested(label: String) {
        playRequestedAtMs = SystemClock.elapsedRealtime()
        firstFrameLogged = false
        wasReady = false
        rebufferStartedAtMs = 0L
        Log.d(TAG, "play requested: $label")
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        if (firstFrameLogged || playRequestedAtMs == 0L) return
        firstFrameLogged = true
        Log.d(TAG, "time to first frame: ${SystemClock.elapsedRealtime() - playRequestedAtMs} ms")
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_READY -> {
                if (rebufferStartedAtMs > 0) {
                    val duration = now - rebufferStartedAtMs
                    rebufferTotalMs += duration
                    rebufferStartedAtMs = 0L
                    Log.d(TAG, "rebuffer #$rebufferCount ended after $duration ms (total ${rebufferTotalMs} ms)")
                }
                wasReady = true
            }
            Player.STATE_BUFFERING -> if (wasReady && rebufferStartedAtMs == 0L) {
                rebufferCount++
                rebufferStartedAtMs = now
                Log.d(TAG, "rebuffer #$rebufferCount started at ${eventTime.currentPlaybackPositionMs} ms")
            }
            Player.STATE_ENDED, Player.STATE_IDLE -> rebufferStartedAtMs = 0L
        }
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        Log.d(
            TAG,
            "video format: ${format.width}x${format.height} @ ${format.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: "? kbps"}, " +
                "codec=${format.codecs ?: format.sampleMimeType}, fps=${format.frameRate}"
        )
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        this.droppedFrames += droppedFrames
        Log.d(TAG, "dropped $droppedFrames frames in $elapsedMs ms (total ${this.droppedFrames})")
    }

    override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) {
        Log.d(TAG, "session summary: rebuffers=$rebufferCount (${rebufferTotalMs} ms), dropped frames=$droppedFrames")
    }

    companion object {
        const val TAG = "SFPerf"
    }
}
