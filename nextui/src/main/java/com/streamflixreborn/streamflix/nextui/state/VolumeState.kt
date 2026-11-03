package com.streamflixreborn.streamflix.nextui.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun rememberVolumeState(showVolumePanelIfHeadsetIsOn: Boolean = false): VolumeState {
    val context = LocalContext.current
    val volumeState = remember { VolumeState(context, showVolumePanelIfHeadsetIsOn) }
    DisposableEffect(key1 = context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                volumeState.syncWithSystem()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return volumeState
}

private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
private const val MAX_VOLUME_PERCENTAGE = 100

/** System music-stream volume (no loudness boost: the host plays through a plain ExoPlayer). */
@Stable
class VolumeState(context: Context, private val showVolumePanelIfHeadsetIsOn: Boolean) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val systemMaxVolume: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    val maxVolumePercentage: Int = MAX_VOLUME_PERCENTAGE

    var currentVolume: Int by mutableIntStateOf(audioManager.currentStreamVolume)
        private set

    var volumePercentage: Int by mutableIntStateOf(calculateVolumePercentage())
        private set

    fun syncWithSystem() {
        val systemVolume = audioManager.currentStreamVolume
        if (systemVolume != currentVolume) {
            currentVolume = systemVolume
            volumePercentage = calculateVolumePercentage()
        }
    }

    fun updateVolumePercentage(percentage: Int) {
        val clamped = percentage.coerceIn(0, MAX_VOLUME_PERCENTAGE)
        setVolume((clamped * systemMaxVolume) / MAX_VOLUME_PERCENTAGE)
    }

    fun increaseVolume(showVolumePanel: Boolean = false) = setVolume(currentVolume + 1, showVolumePanel)

    fun decreaseVolume(showVolumePanel: Boolean = false) = setVolume(currentVolume - 1, showVolumePanel)

    private fun setVolume(volume: Int, showVolumePanel: Boolean = false) {
        val clamped = volume.coerceIn(0, systemMaxVolume)
        currentVolume = clamped
        volumePercentage = calculateVolumePercentage()
        val showUi = showVolumePanel || (showVolumePanelIfHeadsetIsOn && audioManager.isHeadsetOn)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            clamped,
            if (showUi) AudioManager.FLAG_SHOW_UI else 0,
        )
    }

    private fun calculateVolumePercentage(): Int =
        (currentVolume.toFloat() / systemMaxVolume * MAX_VOLUME_PERCENTAGE).toInt()

    private val AudioManager.currentStreamVolume: Int
        get() = getStreamVolume(AudioManager.STREAM_MUSIC)

    private val AudioManager.isHeadsetOn: Boolean
        get() = getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
}
