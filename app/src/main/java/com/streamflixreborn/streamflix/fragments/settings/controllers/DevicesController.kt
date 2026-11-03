package com.streamflixreborn.streamflix.fragments.settings.controllers

import androidx.preference.Preference
import com.streamflixreborn.streamflix.R

/** Sync & devices → Devices: QR pairing (phone and TV side) and the phone remote. */
class DevicesController(private val host: SettingsHost) : SettingsController {

    override fun bind() {
        link("sync_pair_tv", R.id.pair_phone)
        link("sync_remote", R.id.remote)
        link("sync_pair_phone", R.id.pair_tv)
    }

    private fun link(key: String, destinationId: Int) {
        host.find<Preference>(key)?.setOnPreferenceClickListener {
            host.navigate(destinationId)
            true
        }
    }
}
