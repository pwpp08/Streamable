package com.streamflixreborn.streamflix.sync

import android.content.Context
import kotlinx.coroutines.CancellationException

/**
 * What the last cloud synchronisation did, so a device that "doesn't sync" can say why: when it last
 * worked, the last failure (never a token or link), and how many local changes are still waiting.
 */
object CloudSyncStatus {
    private const val PREFS = "cloud_sync_status"

    data class Snapshot(
        val lastSuccessAtMillis: Long?,
        val lastErrorAtMillis: Long?,
        val lastError: String?,
        val waiting: Int,
    )

    fun succeeded(context: Context, profileId: String) {
        prefs(context).edit()
            .putLong("ok_$profileId", System.currentTimeMillis())
            .remove("err_$profileId").remove("err_at_$profileId")
            .apply()
    }

    fun failed(context: Context, profileId: String, error: Throwable) {
        if (error is CancellationException) return
        val reason = buildString {
            append(error.javaClass.simpleName)
            error.message?.lineSequence()?.firstOrNull()?.take(140)?.let { append(": ").append(it) }
        }
        prefs(context).edit()
            .putString("err_$profileId", reason)
            .putLong("err_at_$profileId", System.currentTimeMillis())
            .apply()
    }

    fun snapshot(context: Context, profileId: String, userId: String?): Snapshot {
        val p = prefs(context)
        return Snapshot(
            lastSuccessAtMillis = p.getLong("ok_$profileId", 0L).takeIf { it > 0 },
            lastErrorAtMillis = p.getLong("err_at_$profileId", 0L).takeIf { it > 0 },
            lastError = p.getString("err_$profileId", null),
            waiting = userId?.let { runCatching { CloudMutationStore.pendingForUser(context, profileId, it).size }.getOrNull() } ?: 0,
        )
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
