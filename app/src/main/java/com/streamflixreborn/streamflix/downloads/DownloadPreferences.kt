package com.streamflixreborn.streamflix.downloads

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.scheduler.Requirements

/** Download settings, kept in their own SharedPreferences file. */
@OptIn(UnstableApi::class)
object DownloadPreferences {

    private const val PREFS = "downloads_prefs"
    private const val KEY_WIFI_ONLY = "downloadsWifiOnly"
    private const val KEY_SAVE_SPACE = "downloadsSaveSpace"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun wifiOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_WIFI_ONLY, true)

    fun setWifiOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY, value).apply()
    }

    /** Caps adaptive downloads at 720p instead of 1080p. */
    fun saveSpace(context: Context): Boolean = prefs(context).getBoolean(KEY_SAVE_SPACE, false)

    fun setSaveSpace(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAVE_SPACE, value).apply()
    }

    fun requirements(context: Context): Requirements =
        Requirements(if (wifiOnly(context)) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
}
