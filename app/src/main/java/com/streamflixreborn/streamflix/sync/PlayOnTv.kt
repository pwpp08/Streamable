package com.streamflixreborn.streamflix.sync

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Phone helper: "Play on TV" for a movie or episode, continuing at the local
 * position. One TV seen in the last 24 h receives it directly, several show a
 * picker, none shows an explanation.
 */
object PlayOnTv {
    private const val TV_WINDOW_MS = 24 * 60 * 60_000L

    /** True when the action should be offered (not on a TV; signing in is asked for on use). */
    fun isAvailable(activity: FragmentActivity): Boolean = !DeviceIdentity.isTv(activity)

    fun play(activity: FragmentActivity, item: AppAdapter.Item) {
        AuthGate.require(activity, R.string.auth_reason_play_on_tv) { playSignedIn(activity, item) }
    }

    private fun playSignedIn(activity: FragmentActivity, item: AppAdapter.Item) {
        activity.lifecycleScope.launch {
            val command = withContext(Dispatchers.IO) { commandFor(activity, item) } ?: return@launch
            val tv = chooseTv(activity, TV_WINDOW_MS) ?: return@launch
            sendTo(activity, tv, command)
        }
    }

    suspend fun sendTo(activity: FragmentActivity, tv: DeviceCommandBus.SyncDevice, command: DeviceCommand): Boolean =
        try {
            DeviceCommandBus.send(activity, tv.id, command)
            if (command !is DeviceCommand.Control) {
                toast(activity, activity.getString(R.string.sync_sent_to_tv, tv.name))
            }
            true
        } catch (error: Exception) {
            toast(activity, activity.getString(R.string.sync_send_failed, error.message ?: error.javaClass.simpleName))
            false
        }

    /**
     * TVs of the account seen within [windowMs], or null after telling the
     * user why none can be used. Shows a picker when there are several.
     */
    suspend fun chooseTv(activity: FragmentActivity, windowMs: Long = TV_WINDOW_MS): DeviceCommandBus.SyncDevice? {
        val tvs = try {
            listTvs(activity, windowMs)
        } catch (error: Exception) {
            toast(activity, activity.getString(R.string.sync_devices_failed, error.message ?: error.javaClass.simpleName))
            return null
        }
        return when (tvs.size) {
            0 -> {
                SyncUi.dialog(activity)
                    .setTitle(R.string.sync_no_tv_title)
                    .setMessage(R.string.sync_no_tv_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                null
            }
            1 -> tvs.first()
            else -> pick(activity, tvs)
        }
    }

    suspend fun listTvs(activity: FragmentActivity, windowMs: Long): List<DeviceCommandBus.SyncDevice> {
        val now = System.currentTimeMillis()
        return DeviceCommandBus.listDevices(activity).filter {
            it.kind == DeviceIdentity.Kind.TV && !it.isThisDevice && now - it.lastSeenAtMillis <= windowMs
        }
    }

    suspend fun pick(
        activity: FragmentActivity,
        devices: List<DeviceCommandBus.SyncDevice>,
    ): DeviceCommandBus.SyncDevice? = suspendCancellableCoroutine { cont ->
        var chosen: DeviceCommandBus.SyncDevice? = null
        val dialog = SyncUi.dialog(activity)
            .setTitle(R.string.sync_choose_tv)
            .setItems(devices.map { it.name }.toTypedArray()) { _, which -> chosen = devices[which] }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (cont.isActive) cont.resume(chosen) }
            .show()
        cont.invokeOnCancellation { dialog.dismiss() }
    }

    private fun commandFor(activity: FragmentActivity, item: AppAdapter.Item): DeviceCommand.Play? {
        val profileId = ProfileManager.activeProfileId ?: return null
        return when (item) {
            is Movie -> {
                val providerName = item.providerName ?: UserPreferences.currentProvider?.name ?: return null
                val stored = readDb(activity, providerName, profileId) { it.movieDao().getById(item.id) }
                val history = stored?.watchHistory ?: item.watchHistory
                PlaybackLauncher.playFor(
                    providerName,
                    item,
                    history?.lastPlaybackPositionMillis ?: 0L,
                    history?.durationMillis?.takeIf { it > 0 },
                )
            }
            is Episode -> {
                val providerName = item.tvShow?.providerName ?: UserPreferences.currentProvider?.name ?: return null
                val enriched = readDb(activity, providerName, profileId) { db ->
                    val stored = db.episodeDao().getById(item.id)
                    val showId = item.tvShow?.id ?: stored?.tvShow?.id
                    val show = item.tvShow?.takeIf { it.title.isNotBlank() }
                        ?: showId?.let { db.tvShowDao().getById(it) }
                    val season = item.season?.takeIf { it.number > 0 }
                        ?: (item.season?.id ?: stored?.season?.id)?.let { db.seasonDao().getById(it) }
                    item.copy(tvShow = show ?: item.tvShow, season = season ?: item.season).also {
                        it.watchHistory = stored?.watchHistory ?: item.watchHistory
                    }
                } ?: item
                val history = enriched.watchHistory
                PlaybackLauncher.playFor(
                    providerName,
                    enriched,
                    history?.lastPlaybackPositionMillis ?: 0L,
                    history?.durationMillis?.takeIf { it > 0 },
                )
            }
            else -> null
        }
    }

    private fun <T> readDb(
        activity: FragmentActivity,
        providerName: String,
        profileId: String,
        block: (AppDatabase) -> T?,
    ): T? = runCatching {
        val db = AppDatabase.getInstanceForProvider(providerName, activity, profileId)
        try {
            block(db)
        } finally {
            db.close()
        }
    }.getOrNull()

    private fun toast(activity: FragmentActivity, text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
