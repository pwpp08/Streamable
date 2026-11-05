package com.streamflixreborn.streamflix.fragments.settings.controllers

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainViewModel
import com.streamflixreborn.streamflix.utils.UserPreferences

/** Settings > About: version, help and community links. */
class AboutController(private val host: SettingsHost) : SettingsController {

    override fun bind() {
        host.find<TwoStatePreference>("UPDATE_CHECK_ENABLED")?.apply {
            isChecked = UserPreferences.updateCheckEnabled
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.updateCheckEnabled = newValue as Boolean
                true
            }
        }
        host.onClick("p_check_updates") {
            host.toast(R.string.update_checking)
            ViewModelProvider(host.fragment.requireActivity())[MainViewModel::class.java].checkUpdate(manual = true)
        }

        host.find<Preference>("p_settings_about")?.apply {
            title = host.string(if (host.isTv) R.string.settings_version_tv else R.string.settings_version_mobile)
            summary = BuildConfig.VERSION_NAME
            isSelectable = false
            onPreferenceClickListener = null
        }

        host.onClick("p_settings_help") { open("https://github.com/pwpp08/Streamable/releases") }
        host.onClick("p_settings_telegram") {
            runCatching {
                host.fragment.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=streamflixreborn")))
            }.onFailure {
                host.toast("Telegram not found.")
                open("https://t.me/streamflixreborn")
            }
        }
    }

    private fun open(url: String) {
        runCatching { host.fragment.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
