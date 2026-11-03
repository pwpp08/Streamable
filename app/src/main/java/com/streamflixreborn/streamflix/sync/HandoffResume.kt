package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.ui.UserDataNotifier
import com.streamflixreborn.streamflix.utils.ProfileManager
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * "Continue on this device" prompt.
 *
 * On resume of either main activity, and whenever synced data changes while it
 * is resumed, looks at the newest in-progress item of the active profile
 * across all providers. If it was engaged within 24 h, sits between 60 s and
 * 95 % of its duration, and its last device (LastDeviceStore, fed by
 * `last_device_id`) is another device, a dialog offers to resume it. Each
 * item/progress pair is offered once; dismissals are remembered. Never shown
 * while the player is open.
 */
object HandoffResume {
    private const val PREFS = "handoff_resume"
    private const val KEY_SEEN = "seen"
    private const val MAX_SEEN = 100
    private const val WINDOW_MS = 24 * 60 * 60_000L
    private const val MIN_POSITION_MS = 60_000L
    private const val MAX_FRACTION = 0.95

    @Volatile
    private var dialogOpen = false

    fun attach(activity: FragmentActivity, navController: () -> NavController?) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                maybeShow(activity, navController)
                UserDataNotifier.updates.collect { maybeShow(activity, navController) }
            }
        }
    }

    internal fun isCandidate(
        lastEngagementMillis: Long,
        positionMs: Long,
        durationMs: Long,
        lastDeviceId: String?,
        thisDeviceId: String,
        nowMillis: Long,
    ): Boolean {
        if (nowMillis - lastEngagementMillis > WINDOW_MS) return false
        if (positionMs <= MIN_POSITION_MS || durationMs <= 0) return false
        if (positionMs >= durationMs * MAX_FRACTION) return false
        return !lastDeviceId.isNullOrBlank() && lastDeviceId != thisDeviceId
    }

    private suspend fun maybeShow(activity: FragmentActivity, navControllerProvider: () -> NavController?) {
        if (dialogOpen) return
        val profileId = ProfileManager.activeProfileId ?: return
        CloudSyncManager.currentUserId(profileId) ?: return
        val nav = navControllerProvider() ?: return
        if (nav.currentDestination?.id in setOf(R.id.player, R.id.profiles, null)) return

        val newest = runCatching { ContinueWatchingAggregator.inProgress(activity, profileId) }
            .getOrNull()?.firstOrNull() ?: return
        val lastDevice = LastDeviceStore.lastDeviceId(
            activity, profileId, newest.providerName, newest.mediaType, newest.mediaId,
        )
        val myId = DeviceIdentity.id(activity)
        if (!isCandidate(newest.lastEngagementMillis, newest.positionMs, newest.durationMs, lastDevice, myId, System.currentTimeMillis())) {
            return
        }
        val seenKey = listOf(newest.providerName, newest.mediaType, newest.mediaId, newest.lastEngagementMillis)
            .joinToString("|")
        if (seen(activity).contains(seenKey)) return

        val deviceName = DeviceCommandBus.knownDeviceName(activity, lastDevice)
            ?: runCatching { DeviceCommandBus.listDevices(activity) }.getOrNull()
                ?.firstOrNull { it.id == lastDevice }?.name
            ?: activity.getString(R.string.handoff_other_device)
        val play = ContinueWatchingAggregator.toPlay(newest) ?: return

        // Re-check after the suspending calls above.
        if (dialogOpen || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (nav.currentDestination?.id == R.id.player) return
        markSeen(activity, seenKey)
        dialogOpen = true
        SyncUi.dialog(activity)
            .setTitle(R.string.handoff_title)
            .setMessage(
                activity.getString(
                    R.string.handoff_message,
                    describe(activity, newest),
                    formatPosition(newest.positionMs),
                    deviceName,
                ),
            )
            .setPositiveButton(R.string.handoff_resume) { _, _ ->
                activity.lifecycleScope.launch {
                    val outcome = PlaybackLauncher.launch(activity, nav, play)
                    if (outcome == PlaybackLauncher.Outcome.UNKNOWN_PROVIDER) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.sync_unknown_provider, play.provider),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.handoff_dismiss, null)
            .setOnDismissListener { dialogOpen = false }
            .show()
    }

    internal fun describe(context: Context, entry: ContinueWatchingAggregator.Entry): String =
        when (val item = entry.item) {
            is Movie -> item.title
            is Episode -> context.getString(
                R.string.sync_episode_label,
                item.tvShow?.title.orEmpty(),
                item.season?.number ?: 0,
                item.number,
            ).trim()
            else -> ""
        }

    internal fun formatPosition(positionMs: Long): String {
        val totalSeconds = positionMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    private fun seen(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SEEN, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    private fun markSeen(context: Context, key: String) {
        val updated = (seen(context) - key + key).takeLast(MAX_SEEN)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SEEN, updated.joinToString("\n")).apply()
    }
}
