package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.SecureRandom

/**
 * Stable identity of this installation, shared by all profiles.
 *
 * The id is random (not derived from hardware identifiers) and generated on
 * first use. It only changes when the app data is cleared.
 */
object DeviceIdentity {
    private const val PREFS = "device_identity"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"

    enum class Kind(val wireName: String) {
        TV("tv"),
        PHONE("phone"),
        TABLET("tablet");

        companion object {
            fun fromWire(value: String?): Kind = entries.firstOrNull { it.wireName == value } ?: PHONE
        }
    }

    @Volatile
    private var cachedId: String? = null

    fun id(context: Context): String {
        cachedId?.let { return it }
        return synchronized(this) {
            cachedId ?: run {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.getString(KEY_ID, null) ?: newId().also { prefs.edit().putString(KEY_ID, it).apply() }
            }.also { cachedId = it }
        }
    }

    fun name(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: defaultName()

    /** Stores a user-chosen device name. Blank restores the default model name. */
    fun setName(context: Context, name: String?) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, name?.trim()?.take(60)?.takeIf { it.isNotEmpty() })
            .apply()
    }

    fun kind(context: Context): Kind {
        val pm = context.packageManager
        return when {
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> Kind.TV
            context.resources.configuration.smallestScreenWidthDp >= 600 -> Kind.TABLET
            else -> Kind.PHONE
        }
    }

    fun isTv(context: Context): Boolean = kind(context) == Kind.TV

    fun defaultName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"

    private fun newId(): String {
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
