package com.streamflixreborn.streamflix.sync.pairing

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.sync.CloudAccountDataConflictException
import com.streamflixreborn.streamflix.sync.CloudSyncManager
import com.streamflixreborn.streamflix.sync.SupabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Applies credentials received from a phone on the TV.
 *
 * Profile mapping: the account is always signed in on the TV's *active*
 * profile; `profileName` from the phone is informational only (shown in the
 * status). Choose the profile on the TV before pairing. If the account is
 * already linked to another local profile, or this profile has local data of
 * a different account, sign-in fails with the usual conflict error and nothing
 * is changed.
 *
 * The password is only held in memory: it is never logged or persisted, the
 * CharArray is wiped by the caller, and the String copy that supabase-kt's
 * API requires is dropped right after the auth request.
 */
object PairingApplier {
    private const val TAG = "PairingApplier"

    sealed class Status {
        data object Idle : Status()
        data class Syncing(val email: String) : Status()
        data class Done(val email: String) : Status()
        data class Failed(val message: String) : Status()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun reset() {
        _status.value = Status.Idle
    }

    /**
     * Saves the Supabase connection when it differs, then signs in. Returns
     * the account e-mail. Runs inside the pairing HTTP request.
     */
    suspend fun authenticate(context: Context, credentials: PairingProtocol.Credentials): String {
        val appContext = context.applicationContext
        val url = credentials.supabaseUrl.trim().trimEnd('/')
        val key = credentials.publicKey.trim()
        require(url.startsWith("https://") && key.isNotEmpty()) { "The phone sent an invalid Supabase connection" }
        require(credentials.email.isNotBlank() && credentials.password.isNotEmpty()) { "Missing e-mail or password" }

        if (SupabaseProvider.getUrl(appContext) != url || SupabaseProvider.getPublicKey(appContext) != key) {
            SupabaseProvider.saveConfig(appContext, url, key)
        }
        CloudSyncManager.authenticate(appContext, credentials.email.trim(), String(credentials.password))
        return CloudSyncManager.currentUserEmail() ?: credentials.email.trim()
    }

    /** Merges and downloads the library after the phone got its answer. */
    fun finishInBackground(context: Context, email: String) {
        val appContext = context.applicationContext
        _status.value = Status.Syncing(email)
        scope.launch {
            _status.value = try {
                CloudSyncManager.finishSignIn(appContext)
                CloudSyncManager.syncNow(appContext)
                Status.Done(email)
            } catch (error: CloudAccountDataConflictException) {
                Status.Failed(error.message ?: "This profile already has data of another account")
            } catch (error: Exception) {
                Log.w(TAG, "Pairing sync failed", error)
                Status.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }
}
