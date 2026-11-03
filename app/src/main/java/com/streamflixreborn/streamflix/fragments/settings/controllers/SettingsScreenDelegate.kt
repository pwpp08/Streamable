package com.streamflixreborn.streamflix.fragments.settings.controllers

import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.fragments.settings.SupabaseSettingsController
import com.streamflixreborn.streamflix.utils.ProfileManager

/**
 * Everything the phone and TV settings fragments have in common: the section controllers,
 * in-place navigation between nested PreferenceScreens (with Back support), titles and refresh.
 */
class SettingsScreenDelegate(
    private val fragment: PreferenceFragmentCompat,
    private val isTv: Boolean,
    private val xmlRes: Int,
    backup: BackupController,
) {
    private data class ScreenState(val rootKey: String?, val title: String?)

    private var current = ScreenState(rootKey = null, title = null)
    private val backStack = ArrayDeque<ScreenState>()
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val previous = backStack.removeLastOrNull() ?: return
            current = previous
            isEnabled = backStack.isNotEmpty()
            render()
        }
    }

    private val host = SettingsHost(fragment, isTv)
    private val parental = ParentalControlController(host)
    private val controllers: List<SettingsController> = listOf(
        LanguagesController(host),
        PlaybackController(host),
        SubtitlesController(host),
        SourcesController(host, parental),
        parental,
        ProfilesController(host),
        HomeScreenController(host),
        AppearanceController(host),
        backup.attach(host),
        AboutController(host),
        DevicesController(host),
        object : SettingsController {
            override fun bind() {
                SupabaseSettingsController.bind(fragment, fragment.lifecycleScope) { key -> fragment.findPreference(key) }
            }
        },
    )

    /** Call from Fragment.onCreate, after super.onCreate. */
    fun onCreate() {
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment, backCallback)
        backCallback.isEnabled = backStack.isNotEmpty()
    }

    /** Call from onCreatePreferences. */
    fun onCreatePreferences(rootKey: String?) {
        if (backStack.isEmpty()) current = ScreenState(rootKey = rootKey, title = null)
        render()
    }

    /** Returns true when [preference] opened a nested screen. */
    fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference !is PreferenceScreen || preference.key.isNullOrBlank()) return false
        backStack.addLast(current)
        current = ScreenState(rootKey = preference.key, title = preference.title?.toString())
        backCallback.isEnabled = true
        render()
        return true
    }

    fun shouldSuppressDialog(preference: Preference): Boolean = ParentalControlController.handlesDialog(preference.key)

    fun onResume() {
        applyTitle()
        controllers.forEach { it.refresh() }
    }

    private fun render() {
        fragment.preferenceManager.sharedPreferencesName =
            ProfileManager.profilePreferencesName(ProfileManager.activeProfileId ?: "default")
        fragment.setPreferencesFromResource(xmlRes, current.rootKey)
        controllers.forEach { it.bind() }
        applyTitle()
        if (isTv) fragment.view?.post { fragment.listView?.requestFocus() }
    }

    private fun applyTitle() {
        fragment.activity?.title = current.title ?: fragment.getString(R.string.player_settings_title)
    }
}
