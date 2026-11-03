package com.streamflixreborn.streamflix.utils

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/** Implemented by activities that own an [IdleDimmer], so fragments (e.g. the players) can reach it. */
interface IdleDimHost {
    val idleDimmer: IdleDimmer
}

/** User-facing anti burn-in settings. [levelPercent] is the brightness kept while dimmed. */
data class IdleDimSettings(
    val enabled: Boolean = DEFAULT_ENABLED,
    val delaySeconds: Int = DEFAULT_DELAY_SECONDS,
    val levelPercent: Int = DEFAULT_LEVEL_PERCENT,
) {
    val delayMs: Long get() = delaySeconds.coerceAtLeast(1) * 1000L

    private val level: Float get() = levelPercent.coerceIn(MIN_LEVEL_PERCENT, MAX_LEVEL_PERCENT) / 100f

    /** Window brightness while dimmed (never fully off). */
    val windowBrightness: Float get() = level.coerceAtLeast(0.02f)

    /** Alpha of the black overlay while dimmed, for projectors whose firmware ignores window brightness. */
    val overlayAlpha: Float get() = 1f - level

    companion object {
        const val DEFAULT_ENABLED = true
        const val DEFAULT_DELAY_SECONDS = 20
        const val DEFAULT_LEVEL_PERCENT = 15
        const val MIN_LEVEL_PERCENT = 5
        const val MAX_LEVEL_PERCENT = 60
    }
}

/**
 * Pure state machine behind [IdleDimmer]: dimmed when enabled, nothing is playing and the last
 * interaction (or the moment playback stopped) is at least the delay ago. Time is injected.
 */
class IdleDimState(startMs: Long) {
    private var lastInteractionMs = startMs
    var isPlaying = false
        private set

    fun onInteraction(nowMs: Long) {
        lastInteractionMs = nowMs
    }

    /** Pausing (or leaving a player) starts a fresh idle period; resuming ends it. */
    fun setPlaying(nowMs: Long, playing: Boolean) {
        if (playing == isPlaying) return
        isPlaying = playing
        lastInteractionMs = nowMs
    }

    fun isDimmed(nowMs: Long, settings: IdleDimSettings): Boolean =
        settings.enabled && !isPlaying && nowMs - lastInteractionMs >= settings.delayMs

    /** Milliseconds until the screen should dim, or null when it never will (disabled or playing). */
    fun msUntilDim(nowMs: Long, settings: IdleDimSettings): Long? {
        if (!settings.enabled || isPlaying) return null
        return (settings.delayMs - (nowMs - lastInteractionMs)).coerceAtLeast(0)
    }
}

/**
 * Dims the screen against burn-in when nothing plays (browse/menu screens) or a video has been
 * paused for a while. Applies both the window brightness and a black overlay. Input is never
 * swallowed: the activity forwards [onUserInteraction] and the overlay is neither clickable nor
 * focusable. Uses a single postDelayed, no polling.
 */
class IdleDimmer(
    private val activity: Activity,
    private val settingsProvider: () -> IdleDimSettings = { UserPreferences.idleDimSettings },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val state = IdleDimState(clock())
    private var overlay: View? = null
    private var dimmed = false
    private var resumed = false

    private val check = Runnable { evaluate() }

    fun onResume() {
        resumed = true
        state.onInteraction(clock())
        evaluate()
    }

    /** Leaving the foreground always restores full brightness and stops the timer. */
    fun onPause() {
        resumed = false
        handler.removeCallbacks(check)
        undim()
    }

    fun release() {
        onPause()
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
    }

    fun onUserInteraction() {
        state.onInteraction(clock())
        if (resumed) evaluate()
    }

    /** Reported by the players; false everywhere else (browse screens count as "not playing"). */
    fun setPlaying(playing: Boolean) {
        state.setPlaying(clock(), playing)
        if (resumed) evaluate()
    }

    private fun evaluate() {
        handler.removeCallbacks(check)
        if (!resumed) return
        val settings = settingsProvider()
        val now = clock()
        if (state.isDimmed(now, settings)) {
            dim(settings)
        } else {
            undim()
            state.msUntilDim(now, settings)?.let { handler.postDelayed(check, it.coerceAtLeast(1)) }
        }
    }

    private fun dim(settings: IdleDimSettings) {
        val view = overlayView()
        val params = activity.window.attributes
        params.screenBrightness = settings.windowBrightness
        activity.window.attributes = params
        view.bringToFront()
        view.animate().cancel()
        view.animate().alpha(settings.overlayAlpha).setDuration(if (dimmed) 0 else FADE_MS).start()
        if (!dimmed) Log.i(TAG, "dim: brightness=${settings.windowBrightness} overlayAlpha=${settings.overlayAlpha}")
        dimmed = true
    }

    private fun undim() {
        if (!dimmed) return
        dimmed = false
        overlay?.apply {
            animate().cancel()
            alpha = 0f
        }
        val params = activity.window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = params
        Log.i(TAG, "wake")
    }

    private fun overlayView(): View = overlay ?: View(activity).apply {
        setBackgroundColor(0xFF000000.toInt())
        alpha = 0f
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        (activity.window.decorView as ViewGroup).addView(
            this,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        this@IdleDimmer.overlay = this
    }

    private companion object {
        const val TAG = "IdleDimmer"
        const val FADE_MS = 400L
    }
}
