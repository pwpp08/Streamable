package com.streamflixreborn.streamflix.fragments.settings.controllers

import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import com.streamflixreborn.streamflix.activities.main.MainTvActivity
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences

/** Settings > Appearance: theme, plus screen padding on TV. */
class AppearanceController(private val host: SettingsHost) : SettingsController {

    override fun bind() {
        host.find<ListPreference>(KEY_THEME)?.apply {
            // "Wallpaper colours" only where Material You is available (Android 12+ phones).
            if (!ThemeManager.supportsWallpaperColors(host.isTv)) {
                val keep = entryValues.indices.filter { entryValues[it] != ThemeManager.WALLPAPER }
                entries = keep.map { entries[it] }.toTypedArray()
                entryValues = keep.map { entryValues[it] }.toTypedArray()
            }
            summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                host.string(ThemeManager.titleRes(ThemeManager.resolve(pref.value, host.isTv)))
            }
            value = ThemeManager.current(host.isTv)
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.selectedTheme = newValue as String
                host.restartActivity()
                true
            }
        }

        host.find<SeekBarPreference>("scale_x")?.apply {
            seekBarIncrement = 1
            value = UserPreferences.paddingX
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.paddingX = newValue as Int
                (host.fragment.activity as? MainTvActivity)?.adjustLayoutDelta(UserPreferences.paddingX, null)
                true
            }
        }
        host.find<SeekBarPreference>("scale_y")?.apply {
            seekBarIncrement = 1
            value = UserPreferences.paddingY
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.paddingY = newValue as Int
                (host.fragment.activity as? MainTvActivity)?.adjustLayoutDelta(null, UserPreferences.paddingY)
                true
            }
        }
    }

    private companion object {
        const val KEY_THEME = "SELECTED_THEME"
    }
}
