package com.streamflixreborn.streamflix.nextui.state

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

@Composable
fun rememberRotationState(): RotationState? {
    val activity = LocalActivity.current ?: return null
    return remember(activity) { RotationState(activity = activity) }
}

@Stable
class RotationState(private val activity: Activity) {
    fun rotate() {
        activity.requestedOrientation = when (activity.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
}
