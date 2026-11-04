package com.streamflixreborn.streamflix.sync

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.navOptions
import com.streamflixreborn.streamflix.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Executes commands from [DeviceCommandBus] on the TV while MainTvActivity is
 * started: Play opens the player, Search opens the search screen with the
 * text, Control is forwarded to [RemoteControl] for the player.
 */
class TvCommandHandler(
    private val activity: FragmentActivity,
    private val navController: () -> NavController?,
) {
    fun attach() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Backstop for realtime: look for commands every few seconds while the app is on screen.
                launch {
                    var round = 0
                    while (isActive) {
                        DeviceCommandBus.poll()
                        if (++round % RESUBSCRIBE_EVERY == 0) DeviceCommandBus.ensureListening()
                        delay(POLL_INTERVAL_MS)
                    }
                }
                DeviceCommandBus.commands.collect { handle(it) }
            }
        }
    }

    private suspend fun handle(received: DeviceCommandBus.ReceivedCommand) {
        val nav = navController() ?: return
        when (val command = received.command) {
            is DeviceCommand.Play -> {
                toastSender(received)
                when (PlaybackLauncher.launch(activity, nav, command)) {
                    PlaybackLauncher.Outcome.UNKNOWN_PROVIDER -> toast(
                        activity.getString(R.string.sync_unknown_provider, command.provider),
                    )
                    else -> Unit
                }
            }
            is DeviceCommand.Search -> {
                toastSender(received)
                RemoteSearch.request(command.text)
                if (nav.currentDestination?.id != R.id.search) {
                    nav.navigate(R.id.search, null, navOptions { launchSingleTop = true })
                }
            }
            is DeviceCommand.Control -> RemoteControl.emit(command)
        }
    }

    private fun toastSender(received: DeviceCommandBus.ReceivedCommand) {
        val name = received.sourceName
            ?: DeviceCommandBus.knownDeviceName(activity, received.sourceDeviceId)
        toast(
            if (name != null) activity.getString(R.string.sync_sent_from, name)
            else activity.getString(R.string.sync_sent_from_unknown),
        )
    }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val RESUBSCRIBE_EVERY = 10
    }
}
