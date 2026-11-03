package com.streamflixreborn.streamflix.fragments.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.preference.Preference
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.fragments.settings.controllers.BackupController
import com.streamflixreborn.streamflix.fragments.settings.controllers.SettingsScreenDelegate

/** TV settings (D-pad). The sections are bound by the controllers in `controllers/`. */
class SettingsTvFragment : LeanbackPreferenceFragmentCompat() {

    private val backup = BackupController(this, isTv = true)
    private val screens = SettingsScreenDelegate(this, isTv = true, xmlRes = R.xml.settings_tv, backup = backup)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screens.onCreate()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        screens.onCreatePreferences(rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        styleTitle(view)
        SettingsListStyler.attach(this, isTv = true)
        view.post { listView?.requestFocus() }
    }

    /** Leanback's title bar becomes a plain headline on the surface, aligned with the rows. */
    private fun styleTitle(view: View) {
        val density = resources.displayMetrics.density
        view.findViewById<View>(androidx.leanback.preference.R.id.decor_title_container)?.apply {
            background = null
            elevation = 0f
        }
        view.findViewById<TextView>(androidx.leanback.preference.R.id.decor_title)?.apply {
            setTextAppearance(R.style.TextAppearance_StreamFlix_Settings_TvScreenTitle)
            setPaddingRelative((48 * density).toInt(), (28 * density).toInt(), (48 * density).toInt(), (12 * density).toInt())
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean =
        screens.onPreferenceTreeClick(preference) || super.onPreferenceTreeClick(preference)

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (screens.shouldSuppressDialog(preference)) return
        if (MaterialPreferenceDialogs.show(this, preference)) return
        super.onDisplayPreferenceDialog(preference)
    }

    override fun onResume() {
        super.onResume()
        screens.onResume()
    }
}
