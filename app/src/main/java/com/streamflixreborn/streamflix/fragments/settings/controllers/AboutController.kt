package com.streamflixreborn.streamflix.fragments.settings.controllers

import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences

/** Settings > About: version, update check, help and community links. */
class AboutController(private val host: SettingsHost) : SettingsController {

    override fun bind() {
        host.find<TwoStatePreference>("UPDATE_CHECK_ENABLED")?.apply {
            isChecked = UserPreferences.updateCheckEnabled
            // Stored globally, not per profile, so the preference itself must not persist it.
            setOnPreferenceChangeListener { pref, newValue ->
                UserPreferences.updateCheckEnabled = newValue as Boolean
                (pref as TwoStatePreference).isChecked = newValue
                false
            }
        }

        host.find<Preference>("p_settings_about")?.apply {
            val palette = ThemeManager.palette(host.fragment.requireContext())
            val titleText = host.string(if (host.isTv) R.string.settings_version_tv else R.string.settings_version_mobile)
            title = SpannableString(titleText).apply {
                setSpan(ForegroundColorSpan(palette.tvHeaderPrimary), 0, titleText.length, 0)
            }
            summary = SpannableString(BuildConfig.VERSION_NAME).apply {
                setSpan(ForegroundColorSpan(palette.tvHeaderSecondary), 0, length, 0)
            }
            isSelectable = false
            onPreferenceClickListener = null
        }

        host.onClick("p_settings_help") { open("https://git.gay/streamflix-reborn2/streamflix") }
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
