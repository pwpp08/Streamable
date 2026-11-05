package com.streamflixreborn.streamflix.activities.main

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.utils.InAppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.streamflixreborn.streamflix.utils.UserPreferences
import java.io.File

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow<State>(State.CheckingUpdate)
    val state: Flow<State> = _state

    sealed class State {
        data object CheckingUpdate : State()
        /** Only after a manual check: nothing newer than the installed version. */
        data object UpToDate : State()
        data class SuccessCheckingUpdate(val newReleases: List<InAppUpdater.AppRelease>, val asset: InAppUpdater.AppAsset) : State()

        data object DownloadingUpdate : State()
        data class SuccessDownloadingUpdate(val apk: File) : State()

        data object InstallingUpdate : State()

        data class FailedUpdate(val error: Exception) : State()
    }


    private var autoChecked = false
    private var lastHandled: State? = null

    /** True the first time [state] is seen; a state replayed after a screen restart is not new. */
    fun firstTime(state: State): Boolean {
        if (lastHandled === state) return false
        lastHandled = state
        return true
    }

    /** [manual] checks even when the startup check is off and reports "up to date". */
    fun checkUpdate(manual: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        if (!manual) {
            // Startup check: once per launch, and never when switched off.
            if (!UserPreferences.updateCheckEnabled || autoChecked) return@launch
            autoChecked = true
        }
        // A check on every resume must not clobber a download or install in progress.
        if (_state.value is State.DownloadingUpdate || _state.value is State.InstallingUpdate) return@launch
        _state.emit(State.CheckingUpdate)

        try {
            val newReleases = InAppUpdater.getNewReleases()
            if (newReleases.isEmpty()) {
                if (manual) _state.emit(State.UpToDate)
                return@launch
            }

            val asset = InAppUpdater.pickAsset(
                assets = newReleases.first().assets,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                layout = BuildConfig.APP_LAYOUT,
            ) ?: throw Exception("Can't find update APK")

            _state.emit(State.SuccessCheckingUpdate(newReleases, asset))
        } catch (e: Exception) {
            Log.e("MainViewModel", "checkUpdate: ", e)
            // A failed background check (offline, rate limited) is not worth a message.
            if (manual) _state.emit(State.FailedUpdate(e))
        }
    }

    fun downloadUpdate(
        context: Context,
        asset: InAppUpdater.AppAsset,
    ) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.DownloadingUpdate)

        try {
            val apk = InAppUpdater.downloadApk(context, asset)

            _state.emit(State.SuccessDownloadingUpdate(apk))
        } catch (e: Exception) {
            Log.e("MainViewModel", "downloadUpdate: ", e)
            _state.emit(State.FailedUpdate(e))
        }
    }

    fun installUpdate(
        context: Context,
        apk: File,
    ) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.InstallingUpdate)

        try {
            InAppUpdater.installApk(context, Uri.fromFile(apk))
            // The system installer has the screen now; leave "installing" so later checks can run.
            _state.emit(State.CheckingUpdate)
        } catch (e: Exception) {
            Log.e("MainViewModel", "installUpdate: ", e)
            _state.emit(State.FailedUpdate(e))
        }
    }
}

