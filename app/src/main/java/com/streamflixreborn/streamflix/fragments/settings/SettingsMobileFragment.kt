package com.streamflixreborn.streamflix.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.FragmentSettingsMobileBinding
import com.streamflixreborn.streamflix.fragments.settings.controllers.BackupController
import com.streamflixreborn.streamflix.fragments.settings.controllers.SettingsScreenDelegate

/** Phone settings. The sections are bound by the controllers in `controllers/`. */
class SettingsMobileFragment : PreferenceFragmentCompat() {

    private val backup = BackupController(this, isTv = false)
    private val screens = SettingsScreenDelegate(this, isTv = false, xmlRes = R.xml.settings_mobile, backup = backup)

    private var chrome: FragmentSettingsMobileBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screens.onCreate()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        screens.onCreatePreferences(rootKey)
    }

    /** Wraps the preference list in the large collapsing top app bar. */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val list = super.onCreateView(inflater, container, savedInstanceState)
        val binding = FragmentSettingsMobileBinding.inflate(inflater, container, false)
        binding.flSettingsList.addView(list, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        binding.tbSettings.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        chrome = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        SettingsListStyler.attach(this, isTv = false)
        screens.onScreenChanged = { title, isNested ->
            chrome?.apply {
                val changed = ctlSettings.title?.toString() != title
                ctlSettings.title = title
                tbSettings.navigationIcon = if (isNested) {
                    androidx.appcompat.content.res.AppCompatResources.getDrawable(requireContext(), R.drawable.ic_sf_arrow_back)
                } else {
                    null
                }
                tbSettings.navigationContentDescription = if (isNested) getString(R.string.ui_misc_navigate_up) else null
                if (changed) ablSettings.setExpanded(true, false)
            }
        }
    }

    override fun onDestroyView() {
        screens.onScreenChanged = null
        chrome = null
        super.onDestroyView()
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
