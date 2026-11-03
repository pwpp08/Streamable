package com.streamflixreborn.streamflix.fragments.player.engine

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.IntroDbClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Skip intro/recap handling, driven by the player's 1 s progress tick.
 * The fragments only render the pill / message.
 */
class SkipSegmentController(
    private val scope: CoroutineScope,
    private val ui: Ui,
) {
    interface Ui {
        fun showSkipButton(segment: IntroDbClient.Segment)
        fun hideSkipButton()
        fun showAutoSkipped(segment: IntroDbClient.Segment)
        fun seekTo(positionMs: Long)
    }

    var segments: IntroDbClient.Segments? = null
        private set
    private var loadJob: Job? = null
    private var shown: IntroDbClient.Segment? = null
    private val handled = mutableSetOf<IntroDbClient.Segment>()

    val hasData get() = segments?.isEmpty == false

    fun load(provider: Provider?, videoType: Video.Type) {
        loadJob?.cancel()
        segments = null
        handled.clear()
        hide()
        val query = IntroDbClient.queryFor(provider, videoType) ?: return
        loadJob = scope.launch { segments = IntroDbClient.fetch(query) }
    }

    fun onTick(positionMs: Long, durationMs: Long) {
        val mode = UserPreferences.skipSegmentsMode
        val segment = segments?.skippableAt(positionMs, durationMs)
        if (mode == UserPreferences.SkipSegmentsMode.OFF || segment == null || segment in handled) {
            hide()
            return
        }
        when (mode) {
            UserPreferences.SkipSegmentsMode.AUTO -> {
                handled += segment
                hide()
                ui.seekTo(segment.end(durationMs))
                ui.showAutoSkipped(segment)
            }
            else -> if (shown != segment) {
                shown = segment
                ui.showSkipButton(segment)
            }
        }
    }

    /** The pill was pressed. */
    fun skip(durationMs: Long) {
        val segment = shown ?: return
        handled += segment
        hide()
        ui.seekTo(segment.end(durationMs))
    }

    /** The pill was dismissed (Back); don't offer this segment again. */
    fun dismiss() {
        shown?.let { handled += it }
        hide()
    }

    val isShowing get() = shown != null

    private fun hide() {
        if (shown != null) {
            shown = null
            ui.hideSkipButton()
        }
    }
}

/**
 * "Up next" card for episodes: appears when the credits start (or [FALLBACK_LEAD_MS] before
 * the end when there is no credits data), counts down [UserPreferences.upNextSeconds] while
 * playing and then plays the next episode if autoplay is on.
 */
class UpNextController(private val ui: Ui) {

    interface Ui {
        fun nextEpisode(): Video.Type.Episode?
        /** [secondsLeft] is null when autoplay is off (the card only offers the episode). */
        fun showUpNext(next: Video.Type.Episode, secondsLeft: Int?)
        fun hideUpNext()
        fun playNext()
        /** Load the next episode's metadata and pre-resolve its servers. */
        fun prepareNext()
    }

    private var dismissed = false
    private var secondsLeft: Int? = null
    private var launched = false
    private var prepared = false

    fun reset() {
        dismissed = false
        secondsLeft = null
        launched = false
        prepared = false
        ui.hideUpNext()
    }

    fun onTick(positionMs: Long, durationMs: Long, creditsStartMs: Long?) {
        if (durationMs <= 0) return
        val remaining = durationMs - positionMs

        if (!prepared && (positionMs >= durationMs * PREPARE_AT_PROGRESS || remaining <= FALLBACK_LEAD_MS * 2)) {
            prepared = true
            ui.prepareNext()
        }

        val trigger = creditsStartMs ?: (durationMs - FALLBACK_LEAD_MS)
        if (positionMs < trigger || remaining <= 0) {
            if (secondsLeft != null) {
                secondsLeft = null
                ui.hideUpNext()
            }
            return
        }
        if (dismissed || launched) return

        val next = ui.nextEpisode() ?: run {
            ui.hideUpNext()
            return
        }
        val current = secondsLeft
        secondsLeft = if (current == null) UserPreferences.upNextSeconds.coerceAtLeast(0) else current - 1

        if (UserPreferences.autoplay) {
            val left = secondsLeft!!.coerceAtLeast(0)
            ui.showUpNext(next, left)
            if (left == 0) launchNext()
        } else {
            ui.showUpNext(next, null)
        }
    }

    /** Cancel / Back on the card. */
    fun dismiss() {
        dismissed = true
        ui.hideUpNext()
    }

    /** Plays the next episode once, whatever triggered it (card, countdown, end of video). */
    fun launchNext(): Boolean {
        if (launched) return false
        launched = true
        ui.hideUpNext()
        ui.playNext()
        return true
    }

    companion object {
        const val FALLBACK_LEAD_MS = 30_000L
        const val PREPARE_AT_PROGRESS = 0.9
    }
}
