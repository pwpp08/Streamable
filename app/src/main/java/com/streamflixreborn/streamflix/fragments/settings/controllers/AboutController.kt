package com.streamflixreborn.streamflix.fragments.settings.controllers

import android.content.Intent
import android.net.Uri
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R

/** Settings > About: version, help and community links. */
class AboutController(private val host: SettingsHost) : SettingsController {

    override fun bind() {
        // Upstream update checks are permanently disabled in this fork (MainViewModel
        // UPSTREAM_UPDATES_DISABLED): upstream releases would replace it, so the toggle is hidden.
        host.find<TwoStatePreference>("UPDATE_CHECK_ENABLED")?.isVisible = false

        host.find<Preference>("p_settings_about")?.apply {
            title = host.string(if (host.isTv) R.string.settings_version_tv else R.string.settings_version_mobile)
            summary = BuildConfig.VERSION_NAME
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
