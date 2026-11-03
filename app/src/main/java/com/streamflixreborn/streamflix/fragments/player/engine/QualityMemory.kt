package com.streamflixreborn.streamflix.fragments.player.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.health.HlsPlaylistParser

/**
 * Remembers the best video height each server has actually delivered, so the ranker can prefer
 * servers that really play in 1080p. Health checks can't see the quality of every stream (many
 * playlists carry no RESOLUTION), but the player always knows what it ended up decoding.
 */
object QualityMemory {

    private const val PREFS = "server_quality_memory"
    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    /** Server being played right now; set by the view model, read by [Listener]. */
    @Volatile
    var currentServer: String? = null

    private fun prefs() = StreamFlixApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(serverName: String) = serverName.trim().lowercase()

    /** Records that [serverName] played at [height]; the best recent height wins. */
    fun record(serverName: String, height: Int, now: Long = System.currentTimeMillis()) {
        if (height <= 0 || serverName.isBlank()) return
        val prefs = prefs()
        val old = decode(prefs.getString(key(serverName), null), now)
        if (old != null && old >= height) {
            // Same or better than before: refresh the timestamp only.
            prefs.edit().putString(key(serverName), "$old|$now").apply()
            return
        }
        prefs.edit().putString(key(serverName), "$height|$now").apply()
        Log.d("QualityMemory", "$serverName delivered ${height}p")
    }

    /** Best recently observed height for [serverName], or null when never played / stale. */
    /** Best height a server's playlist declares (null when unknown); learned without playing it. */
    fun hint(serverName: String, height: Int?) {
        if (height != null) record(serverName, height)
    }

    fun height(serverName: String, now: Long = System.currentTimeMillis()): Int? =
        decode(prefs().getString(key(serverName), null), now)

    internal fun decode(raw: String?, now: Long): Int? {
        val parts = raw?.split('|') ?: return null
        val height = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return height.takeIf { now - at <= MAX_AGE_MS }
    }

    /** How long a stream must have been playing before its resolution counts. */
    internal const val SETTLE_MS = 20_000L

    /**
     * Attached to every player: tracks the best decoded quality of [currentServer] and records it
     * once the stream has played for [SETTLE_MS], so adaptive streams get time to ramp up.
     */
    @OptIn(UnstableApi::class)
    class Listener : AnalyticsListener {
        private var server: String? = null
        private var startedAt = 0L
        private var best = 0

        private fun track(height: Int) {
            val current = currentServer ?: return
            if (current != server) {
                server = current
                startedAt = SystemClock.elapsedRealtime()
                best = 0
            }
            best = maxOf(best, height)
            commitIfSettled()
        }

        private fun commitIfSettled() {
            val name = server ?: return
            if (best > 0 && SystemClock.elapsedRealtime() - startedAt >= SETTLE_MS) {
                record(name, best)
                best = 0
                startedAt = SystemClock.elapsedRealtime()
            }
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            // Letterboxed films decode at e.g. 1280x534; count them by the 16:9 frame they fit.
            if (format.width > 0 && format.height > 0) {
                track(HlsPlaylistParser.qualityHeight(format.width, format.height))
            }
        }

        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
            if (!isPlaying) commitIfSettled()
        }

        override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) = commitIfSettled()
    }
}
