package com.streamflixreborn.streamflix.fragments.settings.controllers

import androidx.appcompat.app.AlertDialog
import androidx.core.os.ConfigurationCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.MetadataLanguage
import com.streamflixreborn.streamflix.utils.UserPreferences
import java.util.Locale

/** Settings > Languages: spoken languages (ordered), metadata language, app language. */
class LanguagesController(private val host: SettingsHost) : SettingsController {

    override fun bind() {
        host.onClick(KEY_SPOKEN) { showSpokenLanguagesDialog() }
        host.onClick(KEY_PRIMARY) { showPrimaryLanguageDialog() }
        bindMetadataLanguage()
        bindAppLanguage()
        refresh()
    }

    override fun refresh() {
        val order = SpokenLanguages.sanitize(UserPreferences.spokenLanguages)
        host.find<Preference>(KEY_SPOKEN)?.summary = SpokenLanguages.summary(order, ::displayName) {
            host.string(R.string.settings_language_primary_suffix, it)
        }
        host.find<Preference>(KEY_PRIMARY)?.summary =
            host.string(R.string.settings_primary_language_summary, displayName(order.first()))
        host.refreshList(KEY_METADATA) { MetadataLanguage.get() }
        host.find<ListPreference>(KEY_APP_LANGUAGE)?.value = AppLanguageManager.getSelectedLanguage(host.context)
    }

    private fun displayName(code: String): String {
        val appLocale = ConfigurationCompat.getLocales(host.context.resources.configuration)[0] ?: Locale.getDefault()
        return Locale.forLanguageTag(code).getDisplayLanguage(appLocale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(appLocale) else it.toString() }
            .ifBlank { code }
    }

    private fun showSpokenLanguagesDialog() {
        val current = SpokenLanguages.sanitize(UserPreferences.spokenLanguages)
        // Current languages first, in priority order, so the list reads like the summary.
        val codes = current.filter { it in SpokenLanguages.choices } +
            SpokenLanguages.choices.filterNot { it in current }.sortedBy { displayName(it) }
        val labels = codes.map(::displayName).toTypedArray()
        val checked = BooleanArray(codes.size) { codes[it] in current }
        var order = current.filter { it in SpokenLanguages.choices }

        fun orderTitle() = if (order.isEmpty()) host.string(R.string.settings_spoken_languages_empty)
        else order.joinToString(" → ") { displayName(it) }

        val dialog = host.dialog()
            .setTitle(orderTitle())
            .setMultiChoiceItems(labels, checked) { dialog, which, isChecked ->
                order = SpokenLanguages.toggle(order, codes[which], isChecked)
                (dialog as AlertDialog).setTitle(orderTitle())
            }
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (order.isEmpty()) {
                    host.toast(R.string.settings_spoken_languages_empty)
                } else {
                    // Keep languages outside the picker (set elsewhere) after the picked ones.
                    UserPreferences.spokenLanguages = order + current.filterNot { it in SpokenLanguages.choices }
                    refresh()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showPrimaryLanguageDialog() {
        val order = SpokenLanguages.sanitize(UserPreferences.spokenLanguages)
        host.dialog()
            .setTitle(R.string.settings_primary_language_title)
            .setSingleChoiceItems(order.map(::displayName).toTypedArray(), 0) { dialog, which ->
                UserPreferences.spokenLanguages = SpokenLanguages.makePrimary(order, order[which])
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun bindMetadataLanguage() {
        host.find<ListPreference>(KEY_METADATA)?.apply {
            summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                host.string(R.string.settings_metadata_language_summary, pref.entry ?: "")
            }
            value = MetadataLanguage.get()
            setOnPreferenceChangeListener { _, newValue ->
                if (MetadataLanguage.set(newValue as String)) {
                    host.restartActivity(clearTask = true)
                } else {
                    host.toast(R.string.settings_metadata_language_saved)
                }
                true
            }
        }
    }

    private fun bindAppLanguage() {
        host.find<ListPreference>(KEY_APP_LANGUAGE)?.apply {
            entries = AppLanguageManager.buildLanguageEntries(host.context)
            entryValues = AppLanguageManager.buildLanguageValues(host.context)
            value = AppLanguageManager.getSelectedLanguage(host.context)
            summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                pref.entries.getOrNull(pref.findIndexOfValue(pref.value))
                    ?: host.string(R.string.settings_app_language_system)
            }
            setOnPreferenceChangeListener { _, newValue ->
                AppLanguageManager.setSelectedLanguage(newValue as String)
                host.restartActivity()
                true
            }
        }
    }

    companion object {
        const val KEY_SPOKEN = "spoken_languages"
        const val KEY_PRIMARY = "primary_language"
        const val KEY_METADATA = "metadata_language"
        const val KEY_APP_LANGUAGE = "APP_LANGUAGE"
    }
}
