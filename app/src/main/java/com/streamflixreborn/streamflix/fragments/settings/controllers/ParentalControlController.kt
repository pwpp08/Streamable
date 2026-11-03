package com.streamflixreborn.streamflix.fragments.settings.controllers

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.UserPreferences

/** Parental control PIN, admin PIN, age limit and lockout handling. */
class ParentalControlController(private val host: SettingsHost) : SettingsController {

    companion object {
        private const val KEY_PIN = "PARENTAL_CONTROL_PIN"
        private const val KEY_ADMIN_PIN = "PARENTAL_CONTROL_ADMIN_PIN"
        private const val KEY_REMOVE_PIN = "PARENTAL_CONTROL_REMOVE_PIN"
        private const val KEY_REMOVE_ADMIN_PIN = "PARENTAL_CONTROL_REMOVE_ADMIN_PIN"
        private const val KEY_MAX_AGE = "PARENTAL_CONTROL_MAX_AGE"
        private const val KEY_UNLOCK = "PARENTAL_CONTROL_UNLOCK"

        /** The PIN preferences open custom dialogs; the fragment must not show the default one. */
        fun handlesDialog(key: String?): Boolean = key == KEY_PIN || key == KEY_ADMIN_PIN
    }

    override fun bind() {
        host.onClick(KEY_PIN) { showParentalPinEditor() }
        host.onClick(KEY_ADMIN_PIN) { showAdminPinEditor() }
        host.onClick(KEY_REMOVE_PIN) {
            val removePin = { removeParentalPin() }
            if (UserPreferences.parentalControlAdminPin.isNotBlank()) changeAdminSettingWithPinCheck(removePin)
            else changeParentalSettingWithPinCheck(removePin)
        }
        host.onClick(KEY_REMOVE_ADMIN_PIN) {
            changeAdminSettingWithPinCheck {
                UserPreferences.parentalControlAdminPin = ""
                host.toast(R.string.settings_parental_admin_pin_removed)
                refresh()
            }
        }
        host.find<ListPreference>(KEY_MAX_AGE)?.setOnPreferenceChangeListener { pref, newValue ->
            if (!UserPreferences.enableTmdb) return@setOnPreferenceChangeListener false
            if (UserPreferences.parentalControlPin.isBlank()) {
                host.toast(R.string.settings_parental_set_pin_first)
                return@setOnPreferenceChangeListener false
            }
            val newMaxAgeValue = newValue as String
            changeParentalSettingWithPinCheck {
                UserPreferences.parentalControlMaxAge = newMaxAgeValue.toIntOrNull()
                (pref as ListPreference).value = newMaxAgeValue
                host.toast(R.string.settings_parental_max_age_saved)
                ProviderChangeNotifier.notifyProviderChanged()
                refresh()
            }
            false
        }
        host.onClick(KEY_UNLOCK) {
            if (UserPreferences.parentalControlAdminPin.isBlank()) {
                host.toast(R.string.settings_parental_set_admin_pin_first)
            } else {
                promptForAdminPin {
                    UserPreferences.unlockParentalControls()
                    host.toast(R.string.settings_parental_unlocked)
                    refresh()
                }
            }
        }
        refresh()
    }

    override fun refresh() {
        val tmdbEnabled = UserPreferences.enableTmdb
        val hardLocked = UserPreferences.parentalControlHardLocked
        val tempLocked = UserPreferences.isParentalControlTemporarilyLocked
        val isLocked = tempLocked || hardLocked
        val pin = UserPreferences.parentalControlPin
        val adminPin = UserPreferences.parentalControlAdminPin

        host.find<EditTextPreference>(KEY_PIN)?.apply {
            isEnabled = tmdbEnabled && !isLocked
            summary = when {
                !tmdbEnabled -> host.string(R.string.settings_parental_requires_tmdb)
                hardLocked -> host.string(R.string.settings_parental_locked_hard)
                tempLocked -> host.string(R.string.settings_parental_locked_temporary, lockRemainingMinutes())
                pin.isBlank() -> host.string(R.string.settings_parental_pin_not_set)
                else -> host.string(R.string.settings_parental_pin_set)
            }
        }
        host.find<EditTextPreference>(KEY_ADMIN_PIN)?.apply {
            isEnabled = tmdbEnabled
            summary = when {
                !tmdbEnabled -> host.string(R.string.settings_parental_requires_tmdb)
                adminPin.isBlank() -> host.string(R.string.settings_parental_admin_pin_not_set)
                else -> host.string(R.string.settings_parental_admin_pin_set)
            }
        }
        host.find<Preference>(KEY_REMOVE_PIN)?.apply {
            isVisible = tmdbEnabled && pin.isNotBlank()
            isEnabled = !isLocked
        }
        host.find<Preference>(KEY_REMOVE_ADMIN_PIN)?.apply {
            isVisible = tmdbEnabled && adminPin.isNotBlank()
            isEnabled = true
        }
        host.find<ListPreference>(KEY_MAX_AGE)?.apply {
            isEnabled = tmdbEnabled && !isLocked
            value = UserPreferences.parentalControlMaxAge?.toString().orEmpty()
            summary = when {
                !tmdbEnabled -> host.string(R.string.settings_parental_requires_tmdb)
                hardLocked -> host.string(R.string.settings_parental_locked_hard)
                tempLocked -> host.string(R.string.settings_parental_locked_temporary, lockRemainingMinutes())
                pin.isBlank() -> host.string(R.string.settings_parental_set_pin_first)
                UserPreferences.parentalControlMaxAge == null -> host.string(R.string.settings_parental_max_age_disabled)
                else -> "${UserPreferences.parentalControlMaxAge}+"
            }
        }
        host.find<Preference>(KEY_UNLOCK)?.apply {
            isVisible = isLocked
            isEnabled = tmdbEnabled && adminPin.isNotBlank()
            summary = when {
                adminPin.isBlank() -> host.string(R.string.settings_parental_set_admin_pin_first)
                hardLocked -> host.string(R.string.settings_parental_locked_hard)
                tempLocked -> host.string(R.string.settings_parental_locked_temporary, lockRemainingMinutes())
                else -> host.string(R.string.settings_parental_unlock_summary)
            }
        }
    }

    /** Runs [onVerified] after the parental PIN was entered (immediately when none is set). */
    fun changeParentalSettingWithPinCheck(onVerified: () -> Unit) {
        when {
            UserPreferences.parentalControlHardLocked -> {
                host.toast(R.string.settings_parental_locked_hard)
                refresh()
                return
            }
            UserPreferences.isParentalControlTemporarilyLocked -> {
                host.toast(host.string(R.string.settings_parental_locked_temporary, lockRemainingMinutes()))
                refresh()
                return
            }
        }
        val currentPin = UserPreferences.parentalControlPin
        if (currentPin.isBlank()) {
            onVerified()
            return
        }
        promptForPin(R.string.settings_parental_enter_current_pin_title, R.string.settings_parental_enter_current_pin_message) { entered ->
            if (entered == currentPin) {
                UserPreferences.registerParentalPinSuccess()
                onVerified()
                null
            } else {
                UserPreferences.registerParentalPinFailure()
                refresh()
                when {
                    UserPreferences.parentalControlHardLocked -> host.string(R.string.settings_parental_locked_hard)
                    UserPreferences.isParentalControlTemporarilyLocked ->
                        host.string(R.string.settings_parental_locked_temporary, lockRemainingMinutes())
                    else -> host.string(R.string.settings_parental_invalid_pin)
                }
            }
        }
    }

    private fun changeAdminSettingWithPinCheck(onVerified: () -> Unit) {
        if (UserPreferences.parentalControlAdminPin.isBlank()) onVerified() else promptForAdminPin(onVerified)
    }

    private fun promptForAdminPin(onVerified: () -> Unit) {
        val currentAdminPin = UserPreferences.parentalControlAdminPin
        if (currentAdminPin.isBlank()) {
            host.toast(R.string.settings_parental_set_admin_pin_first)
            return
        }
        promptForPin(R.string.settings_parental_enter_admin_pin_title, R.string.settings_parental_enter_admin_pin_message) { entered ->
            if (entered == currentAdminPin) {
                UserPreferences.unlockParentalControls()
                onVerified()
                null
            } else {
                host.string(R.string.settings_parental_invalid_admin_pin)
            }
        }
    }

    private fun removeParentalPin() {
        if (UserPreferences.saveParentalControlPin("")) {
            UserPreferences.parentalControlMaxAge = null
            host.find<ListPreference>(KEY_MAX_AGE)?.value = ""
            UserPreferences.unlockParentalControls()
            host.toast(R.string.settings_parental_pin_removed)
            ProviderChangeNotifier.notifyProviderChanged()
            refresh()
        }
    }

    private fun showParentalPinEditor() {
        if (!UserPreferences.enableTmdb) {
            host.toast(R.string.settings_parental_requires_tmdb)
            return
        }
        val showEditor = {
            val hasPin = UserPreferences.parentalControlPin.isNotBlank()
            promptForPin(
                R.string.settings_parental_pin_title,
                if (hasPin) R.string.settings_parental_change_pin_message else R.string.settings_parental_set_new_pin_message,
                allowBlank = hasPin,
            ) { newPin ->
                when {
                    newPin.isBlank() -> {
                        removeParentalPin()
                        null
                    }
                    newPin.length < 4 -> host.string(R.string.settings_parental_pin_too_short)
                    else -> {
                        if (UserPreferences.saveParentalControlPin(newPin)) {
                            host.toast(R.string.settings_parental_pin_saved)
                            ProviderChangeNotifier.notifyProviderChanged()
                            refresh()
                        }
                        null
                    }
                }
            }
        }
        if (UserPreferences.parentalControlAdminPin.isNotBlank()) changeAdminSettingWithPinCheck(showEditor)
        else changeParentalSettingWithPinCheck(showEditor)
    }

    private fun showAdminPinEditor() {
        changeAdminSettingWithPinCheck {
            val hasPin = UserPreferences.parentalControlAdminPin.isNotBlank()
            promptForPin(
                R.string.settings_parental_admin_pin_title,
                if (hasPin) R.string.settings_parental_change_admin_pin_message else R.string.settings_parental_set_new_admin_pin_message,
                allowBlank = hasPin,
            ) { newPin ->
                when {
                    newPin.isBlank() -> {
                        UserPreferences.parentalControlAdminPin = ""
                        host.toast(R.string.settings_parental_admin_pin_removed)
                        refresh()
                        null
                    }
                    newPin.length < 4 -> host.string(R.string.settings_parental_pin_too_short)
                    else -> {
                        UserPreferences.parentalControlAdminPin = newPin
                        host.toast(R.string.settings_parental_admin_pin_saved)
                        refresh()
                        null
                    }
                }
            }
        }
    }

    /**
     * Numeric PIN dialog. [onSubmit] returns an error to show, or null to close the dialog.
     * Blank input is passed to [onSubmit] only when [allowBlank] (or for a verification prompt).
     */
    private fun promptForPin(
        titleRes: Int,
        messageRes: Int,
        allowBlank: Boolean = true,
        onSubmit: (String) -> String?,
    ) {
        val input = EditText(host.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = host.string(R.string.settings_parental_pin_hint)
        }
        val dialog = host.dialog()
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                input.error = null
                val value = input.text?.toString()?.trim().orEmpty()
                val error = if (value.isBlank() && !allowBlank) {
                    host.string(R.string.settings_parental_pin_too_short)
                } else {
                    onSubmit(value)
                }
                if (error == null) {
                    dialog.dismiss()
                } else {
                    input.setText("")
                    input.error = error
                    input.requestFocus()
                }
            }
        }
        dialog.show()
    }

    private fun lockRemainingMinutes(): Int {
        val millis = UserPreferences.parentalControlLockRemainingMillis
        return ((millis + 60_000L - 1L) / 60_000L).toInt().coerceAtLeast(1)
    }
}
