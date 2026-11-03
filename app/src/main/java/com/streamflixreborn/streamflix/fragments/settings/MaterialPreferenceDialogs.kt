package com.streamflixreborn.streamflix.fragments.settings

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.ListPreference
import androidx.preference.ListPreferenceDialogFragmentCompat
import androidx.preference.MultiSelectListPreference
import androidx.preference.MultiSelectListPreferenceDialogFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The androidx preference dialogs (list, multi-select, text) built with
 * [MaterialAlertDialogBuilder], so they get the M3 Expressive dialog surface, shape and buttons
 * on phone and TV. Behaviour is the stock one: the subclasses only replace `onCreateDialog`.
 */
internal object MaterialPreferenceDialogs {

    /** Same tag PreferenceFragmentCompat uses, so a double tap never opens two dialogs. */
    private const val DIALOG_TAG = "androidx.preference.PreferenceFragment.DIALOG"

    /** PreferenceDialogFragmentCompat.ARG_KEY (protected in Java). */
    private const val ARG_KEY = "key"

    /** Shows the Material dialog for [preference]; false when it is not a dialog preference we handle. */
    fun show(fragment: PreferenceFragmentCompat, preference: Preference): Boolean {
        val manager = fragment.parentFragmentManager
        if (manager.findFragmentByTag(DIALOG_TAG) != null) return true
        val dialog: PreferenceDialogFragmentCompat = when (preference) {
            is EditTextPreference -> EditText()
            is ListPreference -> SingleChoice()
            is MultiSelectListPreference -> MultiChoice()
            else -> return false
        }
        dialog.arguments = bundleOf(ARG_KEY to preference.key)
        // PreferenceDialogFragmentCompat still finds its preference through the target fragment.
        @Suppress("DEPRECATION")
        dialog.setTargetFragment(fragment, 0)
        dialog.show(manager, DIALOG_TAG)
        return true
    }

    class SingleChoice : ListPreferenceDialogFragmentCompat() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            build(this, ::onCreateDialogView, ::onBindDialogView, ::onPrepareDialogBuilder)
                ?: super.onCreateDialog(savedInstanceState)
    }

    class MultiChoice : MultiSelectListPreferenceDialogFragmentCompat() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            build(this, ::onCreateDialogView, ::onBindDialogView, ::onPrepareDialogBuilder)
                ?: super.onCreateDialog(savedInstanceState)
    }

    class EditText : EditTextPreferenceDialogFragmentCompat() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            build(this, ::onCreateDialogView, ::onBindDialogView, ::onPrepareDialogBuilder, showKeyboard = true)
                ?: super.onCreateDialog(savedInstanceState)
    }

    /** Mirrors PreferenceDialogFragmentCompat.onCreateDialog with a Material builder. */
    private fun build(
        fragment: PreferenceDialogFragmentCompat,
        createView: (Context) -> View?,
        bindView: (View) -> Unit,
        prepare: (AlertDialog.Builder) -> Unit,
        showKeyboard: Boolean = false,
    ): Dialog? {
        val preference = runCatching { fragment.preference }.getOrNull() ?: return null
        val builder = MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(preference.dialogTitle)
            .setIcon(preference.dialogIcon)
            .setPositiveButton(preference.positiveButtonText, fragment)
            .setNegativeButton(preference.negativeButtonText, fragment)

        val content = createView(builder.context)
        if (content != null) {
            bindView(content)
            builder.setView(content)
        } else {
            builder.setMessage(preference.dialogMessage)
        }
        prepare(builder)

        val dialog = builder.create()
        if (showKeyboard) {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        return dialog
    }
}
