package com.streamflixreborn.streamflix.fragments.settings.controllers

import androidx.preference.Preference
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.sync.AuthGate

/** Sync & devices → Devices: QR pairing (phone and TV side) and the phone remote. */
class DevicesController(private val host: SettingsHost) : SettingsController {

    override fun bind() {
        link("sync_pair_tv", R.id.pair_phone, R.string.auth_reason_pair_tv)
        link("sync_remote", R.id.remote, R.string.auth_reason_remote)
        link("sync_pair_phone", R.id.pair_tv)
    }

    private fun link(key: String, destinationId: Int, reasonRes: Int? = null) {
        host.find<Preference>(key)?.setOnPreferenceClickListener {
            val activity = host.fragment.activity
            if (reasonRes != null && activity != null) {
                AuthGate.require(activity, reasonRes) { host.navigate(destinationId) }
            } else {
                host.navigate(destinationId)
            }
            true
        }
    }
}
