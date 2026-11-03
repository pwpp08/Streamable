package com.streamflixreborn.streamflix.sync

import android.content.Context

/**
 * Remembers which device last changed each media item (`last_device_id`), per
 * profile, so hand-off resume works without a Room migration. Updated when
 * cloud state is applied locally and when this device queues a local change.
 */
internal object LastDeviceStore {
    private const val PREFS_PREFIX = "sync_last_device_"

    fun key(provider: String, mediaType: String, mediaId: String): String =
        listOf(provider, mediaType, mediaId).joinToString("\u001f")

    fun record(context: Context, profileId: String, states: List<RemoteMediaState>) {
        val relevant = states.filter {
            !it.lastDeviceId.isNullOrBlank() && (it.mediaType == "movie" || it.mediaType == "episode")
        }
        if (relevant.isEmpty()) return
        val editor = prefs(context, profileId).edit()
        relevant.forEach { editor.putString(key(it.provider, it.mediaType, it.mediaId), it.lastDeviceId) }
        editor.apply()
    }

    fun lastDeviceId(context: Context, profileId: String, provider: String, mediaType: String, mediaId: String): String? =
        prefs(context, profileId).getString(key(provider, mediaType, mediaId), null)

    fun clearProfile(context: Context, profileId: String) {
        prefs(context, profileId).edit().clear().apply()
    }

    private fun prefs(context: Context, profileId: String) =
        context.applicationContext.getSharedPreferences(PREFS_PREFIX + profileId, Context.MODE_PRIVATE)
}
