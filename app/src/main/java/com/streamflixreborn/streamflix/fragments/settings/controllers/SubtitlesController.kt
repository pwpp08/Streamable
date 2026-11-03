package com.streamflixreborn.streamflix.fragments.settings.controllers

import androidx.preference.EditTextPreference
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.UserPreferences.SubtitleMode
import com.streamflixreborn.streamflix.utils.UserPreferences.SubtitlePreset

/** Settings > Subtitles: when to show them, style preset, track languages, SubDL key. */
class SubtitlesController(private val host: SettingsHost) : SettingsController {

    override fun bind() {
        host.bindEnumList("SUBTITLE_MODE", { UserPreferences.subtitleMode }, { enumOr(it, SubtitleMode.AUTO) }) {
            UserPreferences.subtitleMode = it
        }
        host.bindEnumList(
            "SUBTITLE_PRESET",
            { UserPreferences.subtitlePreset },
            { enumOr(it, SubtitlePreset.CLASSIC) },
        ) { UserPreferences.subtitlePreset = it }
        host.bindSwitch("SHOW_ALL_TRACK_LANGUAGES", { UserPreferences.showAllTrackLanguages }) {
            UserPreferences.showAllTrackLanguages = it
        }

        host.find<EditTextPreference>(KEY_SUBDL)?.setOnPreferenceChangeListener { pref, newValue ->
            val newKey = (newValue as String).trim()
            UserPreferences.subdlApiKey = newKey
            (pref as EditTextPreference).text = newKey
            updateSubdlSummary()
            host.toast(if (newKey.isEmpty()) R.string.settings_subdl_api_key_reset else R.string.settings_subdl_api_key_success)
            false
        }
        refresh()
    }

    override fun refresh() {
        host.refreshList("SUBTITLE_MODE") { UserPreferences.subtitleMode.name }
        host.refreshList("SUBTITLE_PRESET") { UserPreferences.subtitlePreset.name }
        host.refreshSwitch("SHOW_ALL_TRACK_LANGUAGES") { UserPreferences.showAllTrackLanguages }
        host.find<EditTextPreference>(KEY_SUBDL)?.text = UserPreferences.subdlApiKey
        updateSubdlSummary()
    }

    private fun updateSubdlSummary() {
        host.find<EditTextPreference>(KEY_SUBDL)?.summary =
            UserPreferences.subdlApiKey.ifEmpty { host.string(R.string.settings_subdl_api_key_summary) }
    }

    private companion object {
        const val KEY_SUBDL = "SUBDL_API_KEY"
    }
}
