package com.streamflixreborn.streamflix.nextui.state

import android.app.Activity
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun rememberBrightnessState(): BrightnessState? {
    val activity = LocalActivity.current ?: return null
    val state = remember(activity) { BrightnessState(activity) }
    // The window override must not outlive the player screen.
    DisposableEffect(state) { onDispose { state.release() } }
    return state
}

/** Window brightness override driven by the vertical swipe gesture. */
@Stable
class BrightnessState(private val activity: Activity) {
    val maxBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
    private val initialOverride = activity.window.attributes.screenBrightness

    var currentBrightness: Float by mutableFloatStateOf(readBrightness())
        private set

    var brightnessPercentage: Int by mutableIntStateOf((currentBrightness / maxBrightness * 100).toInt())
        private set

    fun updateBrightnessPercentage(percentage: Int) {
        setBrightness(percentage.coerceIn(0, 100) * maxBrightness / 100)
    }

    fun setBrightness(brightness: Float) {
        val value = brightness.coerceIn(0f, maxBrightness)
        val attributes = activity.window.attributes
        attributes.screenBrightness = value
        activity.window.attributes = attributes
        currentBrightness = value
        brightnessPercentage = (value / maxBrightness * 100).toInt()
    }

    fun release() {
        val attributes = activity.window.attributes
        attributes.screenBrightness = initialOverride
        activity.window.attributes = attributes
    }

    private fun readBrightness(): Float {
        val override = activity.window.attributes.screenBrightness
        if (override in WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF..maxBrightness) return override
        return runCatching {
            Settings.System.getFloat(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255
        }.getOrDefault(0.5f)
    }
}
