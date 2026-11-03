package com.streamflixreborn.streamflix.fragments.player.engine

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Switches the display to a refresh rate that is an integer multiple of the video frame rate
 * (24p film on 24/48/120 Hz instead of 60 Hz) and restores the original mode afterwards.
 */
class FrameRateMatcher(private val activity: Activity) {

    private var originalModeId: Int? = null
    private var appliedForFps: Float = 0f

    fun onVideoFrameRate(frameRate: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (frameRate <= 0f || abs(frameRate - appliedForFps) < 0.01f) return
        runCatching { apply(frameRate) }.onFailure { Log.w(TAG, "frame rate matching failed", it) }
    }

    fun restore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val original = originalModeId ?: return
        runCatching {
            val window = activity.window
            window.attributes = window.attributes.apply { preferredDisplayModeId = original }
        }
        originalModeId = null
        appliedForFps = 0f
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun apply(frameRate: Float) {
        val window = activity.window
        @Suppress("DEPRECATION")
        val display: Display = window.decorView.display ?: activity.windowManager.defaultDisplay
        val modes = display.supportedModes
        if (modes.size <= 1) return

        val current = display.mode
        val best = pickMode(
            modes.map { ModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) },
            ModeInfo(current.modeId, current.physicalWidth, current.physicalHeight, current.refreshRate),
            frameRate,
        ) ?: return

        appliedForFps = frameRate
        if (originalModeId == null) originalModeId = window.attributes.preferredDisplayModeId
        if (best.id == current.modeId) return
        Log.d(TAG, "fps=$frameRate -> mode ${best.id} (${best.width}x${best.height}@${best.refreshRate})")
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.id }
    }

    data class ModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    companion object {
        private const val TAG = "FrameRateMatcher"
        private const val TOLERANCE = 0.0015f

        /** Relative error between [refreshRate] and the closest integer multiple of [fps]. */
        fun multipleError(refreshRate: Float, fps: Float): Float {
            val ratio = refreshRate / fps
            val multiple = ratio.roundToInt()
            if (multiple < 1) return Float.MAX_VALUE
            return abs(ratio - multiple) / multiple
        }

        /**
         * Same resolution as [current]; exact multiples only. If the current mode already
         * matches it is kept, otherwise the lowest matching refresh rate wins.
         */
        fun pickMode(modes: List<ModeInfo>, current: ModeInfo, fps: Float): ModeInfo? {
            if (multipleError(current.refreshRate, fps) <= TOLERANCE) return current
            return modes
                .filter { it.width == current.width && it.height == current.height }
                .filter { multipleError(it.refreshRate, fps) <= TOLERANCE }
                .minByOrNull { it.refreshRate }
        }
    }
}
