package com.streamflixreborn.streamflix.sync.settings

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.material.color.MaterialColors
import com.streamflixreborn.streamflix.fragments.home.HomeRowsStore
import com.streamflixreborn.streamflix.sync.CloudSyncManager
import com.streamflixreborn.streamflix.sync.DeviceIdentity
import com.streamflixreborn.streamflix.sync.SupabaseProvider
import com.streamflixreborn.streamflix.sync.SyncTime
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Keeps the user's settings the same on every signed-in device: colour theme, home-screen rows,
 * languages, subtitle and playback preferences. The newest change wins. Wallpaper colours cannot
 * be applied on a TV, so a phone on wallpaper colours publishes the closest named palette.
 *
 * Needs the `user_settings` table (res/raw/supabase_settings.sql). Without it every call fails
 * quietly and the app behaves as before.
 */
object SettingsSync {
    private const val TAG = "SettingsSync"
    private const val TABLE = "user_settings"
    private const val STATE_PREFS = "settings_sync_state"
    private const val PULL_INTERVAL_MS = 30_000L

    private const val META_UPDATED = "_updated_at"
    private const val META_DEVICE = "_device"

    /** What changed on this device after settings arrived from another one. */
    data class Applied(val themeChanged: Boolean, val homeRowsChanged: Boolean)

    @Serializable
    private data class Row(
        @SerialName("user_id") val userId: String? = null,
        val settings: JsonObject = JsonObject(emptyMap()),
        @SerialName("updated_at") val updatedAt: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _applied = MutableSharedFlow<Applied>(extraBufferCapacity = 4)

    /** Emits when settings from another device were applied here. */
    val applied: SharedFlow<Applied> = _applied.asSharedFlow()

    @Volatile
    private var lastPullAt = 0L

    private class Entry(val key: String, val read: () -> String?, val write: (String) -> Unit)

    private fun entries(themeColor: () -> Int): List<Entry> = listOf(
        Entry("theme", { publishedTheme(themeColor) }, ::applyTheme),
        Entry("home_rows", { UserPreferences.homeRowsConfig.orEmpty() }, { UserPreferences.homeRowsConfig = it.ifBlank { null } }),
        Entry("spoken_languages", { UserPreferences.spokenLanguages.joinToString(",") },
            { value -> UserPreferences.spokenLanguages = value.split(',').map { it.trim() }.filter { it.isNotEmpty() } }),
        Entry("subtitle_mode", { UserPreferences.subtitleMode.name },
            { runCatching { UserPreferences.SubtitleMode.valueOf(it) }.getOrNull()?.let { m -> UserPreferences.subtitleMode = m } }),
        Entry("subtitle_preset", { UserPreferences.subtitlePreset.name },
            { runCatching { UserPreferences.SubtitlePreset.valueOf(it) }.getOrNull()?.let { m -> UserPreferences.subtitlePreset = m } }),
        Entry("caption_text_size", { UserPreferences.captionTextSize.toString() },
            { it.toFloatOrNull()?.let { size -> UserPreferences.captionTextSize = size } }),
        Entry("quality_mode", { UserPreferences.qualityMode.name },
            { runCatching { UserPreferences.QualityMode.valueOf(it) }.getOrNull()?.let { m -> UserPreferences.qualityMode = m } }),
        Entry("max_resolution", { UserPreferences.maxResolution.toString() },
            { it.toIntOrNull()?.let { v -> UserPreferences.maxResolution = v } }),
        Entry("up_next_seconds", { UserPreferences.upNextSeconds.toString() },
            { it.toIntOrNull()?.let { v -> UserPreferences.upNextSeconds = v } }),
        Entry("skip_segments_mode", { UserPreferences.skipSegmentsMode.name },
            { runCatching { UserPreferences.SkipSegmentsMode.valueOf(it) }.getOrNull()?.let { m -> UserPreferences.skipSegmentsMode = m } }),
        Entry("autoplay", { UserPreferences.autoplay.toString() }, { UserPreferences.autoplay = it.toBoolean() }),
        Entry("show_all_track_languages", { UserPreferences.showAllTrackLanguages.toString() },
            { UserPreferences.showAllTrackLanguages = it.toBoolean() }),
        Entry("torrent_enabled", { UserPreferences.torrentEnabled.toString() }, { UserPreferences.torrentEnabled = it.toBoolean() }),
    )

    /** The theme id other devices should use: wallpaper colours become the nearest named palette. */
    private fun publishedTheme(themeColor: () -> Int): String {
        val current = ThemeManager.current(DeviceIdentity.isTv(com.streamflixreborn.streamflix.StreamFlixApp.instance))
        return if (current == ThemeManager.WALLPAPER) ThemeSync.nearestPalette(themeColor()) else current
    }

    private fun applyTheme(value: String) {
        if (value in ThemeManager.ALL && value != ThemeManager.WALLPAPER) UserPreferences.selectedTheme = value
    }

    private fun snapshot(activity: Activity): Map<String, String> {
        val color = { MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, 0xFF808080.toInt()) }
        return entries(color).mapNotNull { e -> e.read()?.let { e.key to it } }.toMap()
    }

    private fun state(context: Context) = context.applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun profileId() = com.streamflixreborn.streamflix.utils.ProfileManager.activeProfileId ?: "default"

    private fun stateKey(name: String) = "${name}_${profileId()}"

    /**
     * Fetches the account's settings and applies them when another device changed them since this
     * one last synced. Throttled; pass [force] after signing in or pairing.
     */
    fun pull(activity: Activity, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastPullAt < PULL_INTERVAL_MS) return
        if (CloudSyncManager.currentUserId() == null) return
        lastPullAt = System.currentTimeMillis()
        scope.launch { runCatching { pullNow(activity) }.onFailure { Log.w(TAG, "pull failed: ${it.message}") } }
    }

    private suspend fun pullNow(activity: Activity) = mutex.withLock {
        val profile = profileId()
        val client = SupabaseProvider.clientFor(activity, profile)
        val row = client.from(TABLE).select().decodeSingleOrNull<Row>()
        if (row == null) {
            // Nothing stored yet: this device may publish its settings.
            state(activity).edit().putLong(stateKey("remote_updated"), 0L).apply()
            return@withLock
        }
        val remoteUpdated = row.settings[META_UPDATED]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val remoteDevice = row.settings[META_DEVICE]?.jsonPrimitive?.contentOrNull
        val prefs = state(activity)
        val seen = prefs.getLong(stateKey("remote_updated"), 0L)
        if (remoteUpdated <= seen) return@withLock

        val remote = row.settings
            .filterKeys { !it.startsWith("_") }
            .mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
        val local = snapshot(activity)
        var themeChanged = false
        var homeRowsChanged = false
        if (remoteDevice != DeviceIdentity.id(activity)) {
            val color = { MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, 0xFF808080.toInt()) }
            val byKey = entries(color).associateBy { it.key }
            for ((key, value) in SettingsPayload(local).changesFrom(remote)) {
                val entry = byKey[key] ?: continue
                entry.write(value)
                if (key == "theme") themeChanged = true
                if (key == "home_rows") homeRowsChanged = true
            }
        }
        // Remember what this device now holds, so applying is not echoed back as a local change.
        prefs.edit()
            .putLong(stateKey("remote_updated"), remoteUpdated)
            .putString(stateKey("fingerprint"), SettingsPayload(snapshot(activity)).fingerprint())
            .apply()
        if (homeRowsChanged) HomeRowsStore.notifyChanged()
        if (themeChanged || homeRowsChanged) _applied.tryEmit(Applied(themeChanged, homeRowsChanged))
        Log.i(TAG, "pulled settings from ${remoteDevice ?: "?"} (theme=$themeChanged homeRows=$homeRowsChanged)")
    }

    /** Uploads this device's settings if they changed since the last sync. Call when the app is left. */
    fun push(activity: Activity) {
        if (CloudSyncManager.currentUserId() == null) return
        scope.launch { runCatching { pushNow(activity) }.onFailure { Log.w(TAG, "push failed: ${it.message}") } }
    }

    private suspend fun pushNow(activity: Activity) = mutex.withLock {
        val userId = CloudSyncManager.currentUserId() ?: return@withLock
        val prefs = state(activity)
        // A device that never pulled adopts the account's settings first instead of overwriting them.
        if (!prefs.contains(stateKey("remote_updated"))) return@withLock
        val current = snapshot(activity)
        val fingerprint = SettingsPayload(current).fingerprint()
        if (fingerprint == prefs.getString(stateKey("fingerprint"), null)) return@withLock

        val now = System.currentTimeMillis()
        val payload = buildJsonObject {
            current.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            put(META_UPDATED, JsonPrimitive(now.toString()))
            put(META_DEVICE, JsonPrimitive(DeviceIdentity.id(activity)))
        }
        SupabaseProvider.clientFor(activity, profileId()).from(TABLE).upsert(
            Row(userId = userId, settings = payload, updatedAt = SyncTime.iso(now)),
        ) { onConflict = "user_id" }
        prefs.edit()
            .putLong(stateKey("remote_updated"), now)
            .putString(stateKey("fingerprint"), fingerprint)
            .apply()
        Log.i(TAG, "pushed ${current.size} settings")
    }
}
