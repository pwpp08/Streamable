package com.streamflixreborn.streamflix.fragments.settings.controllers

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference

/**
 * What a settings controller needs from the screen hosting it. Both the phone
 * (PreferenceFragmentCompat) and the TV (LeanbackPreferenceFragmentCompat) fragments provide one.
 */
class SettingsHost(val fragment: PreferenceFragmentCompat, val isTv: Boolean) {

    val context: Context get() = fragment.requireContext()
    val scope get() = fragment.lifecycleScope

    fun <T : Preference> find(key: String): T? = fragment.findPreference(key)

    fun string(resId: Int, vararg args: Any): String = fragment.getString(resId, *args)

    fun toast(resId: Int, long: Boolean = false) = toast(string(resId), long)

    fun toast(message: String, long: Boolean = false) {
        if (!fragment.isAdded) return
        Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /**
     * AppCompat dialogs work on both form factors: every TV theme sets an AppCompat
     * `alertDialogTheme`, and AlertDialog lists are D-pad navigable.
     */
    fun dialog(): AlertDialog.Builder = AlertDialog.Builder(context)

    fun navigate(destinationId: Int) {
        runCatching { fragment.findNavController().navigate(destinationId) }
    }

    /** Restarts the hosting activity, e.g. after a theme, language or provider change. */
    fun restartActivity(clearTask: Boolean = false) {
        val activity = fragment.activity ?: return
        activity.finish()
        val flags = if (clearTask) {
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        } else {
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        activity.startActivity(Intent(activity, activity::class.java).addFlags(flags))
        if (isTv) {
            activity.overridePendingTransition(0, 0)
        }
    }

    // --- Binding helpers shared by the controllers ---

    fun bindSwitch(key: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        find<TwoStatePreference>(key)?.apply {
            isChecked = get()
            setOnPreferenceChangeListener { _, newValue ->
                set(newValue as Boolean)
                true
            }
        }
    }

    fun refreshSwitch(key: String, get: () -> Boolean) {
        find<TwoStatePreference>(key)?.isChecked = get()
    }

    /** Binds a ListPreference whose entry values are the names of [E]. */
    fun <E : Enum<E>> bindEnumList(key: String, get: () -> E, parse: (String) -> E, set: (E) -> Unit) {
        bindList(key, { get().name }) { set(parse(it)) }
    }

    fun bindList(key: String, get: () -> String, set: (String) -> Unit) {
        find<ListPreference>(key)?.apply {
            if (summaryProvider == null) summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            value = get()
            setOnPreferenceChangeListener { _, newValue ->
                set(newValue as String)
                true
            }
        }
    }

    fun refreshList(key: String, get: () -> String) {
        find<ListPreference>(key)?.value = get()
    }

    fun onClick(key: String, action: () -> Unit) {
        find<Preference>(key)?.setOnPreferenceClickListener {
            action()
            true
        }
    }
}

/** Binds one settings section. [bind] runs whenever a (sub)screen is inflated. */
interface SettingsController {
    fun bind()

    /** Re-reads values that may have changed elsewhere (called from onResume). */
    fun refresh() = Unit
}
