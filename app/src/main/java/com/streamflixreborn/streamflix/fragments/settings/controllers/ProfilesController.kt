package com.streamflixreborn.streamflix.fragments.settings.controllers

import android.text.InputType
import android.widget.EditText
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainMobileActivity
import com.streamflixreborn.streamflix.activities.main.MainTvActivity
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Profile
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.ProfileColorPicker
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.ProfileSwitchPinGuard
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Profiles (switch, rename, colour, delete, create) and the per-profile watch history. */
class ProfilesController(private val host: SettingsHost) : SettingsController {

    override fun bind() {
        host.find<TwoStatePreference>("MULTIPLE_PROFILES")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.multipleProfiles = newValue as Boolean
            applyProfileVisibility(newValue)
            true
        }
        host.onClick("key_profile_edit") { ProfileManager.activeProfile?.let(::showRenameDialog) }
        host.onClick("key_profile_manage") { showManagementDialog() }
        host.onClick("key_profile_add") { showCreateDialog() }

        host.find<TwoStatePreference>("ENABLE_RECENTLY_WATCHED")?.setOnPreferenceChangeListener { _, newValue ->
            UserPreferences.setRecentlyWatchedEnabled(newValue as Boolean)
            true
        }
        host.onClick("CLEAR_RECENTLY_WATCHED") {
            host.dialog()
                .setTitle(R.string.settings_clear_recently_watched_title)
                .setMessage(R.string.settings_clear_recently_watched_dialog_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val context = host.context.applicationContext
                    host.scope.launch {
                        withContext(Dispatchers.IO) {
                            AppDatabase.getInstance(context).movieDao().clearAllRecentlyWatched()
                            AppDatabase.getInstance(context).tvShowDao().clearAllRecentlyWatched()
                        }
                        host.toast(R.string.settings_clear_recently_watched_done)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        refresh()
    }

    private fun applyProfileVisibility(multiple: Boolean) {
        listOf("key_profile_manage", "key_profile_add").forEach { host.find<Preference>(it)?.isVisible = multiple }
    }

    override fun refresh() {
        applyProfileVisibility(UserPreferences.multipleProfiles)
        host.find<Preference>("key_profile_edit")?.summary =
            ProfileManager.activeProfile?.name ?: host.string(R.string.profile_edit_btn)
    }

    private fun nameInput(initial: String? = null) = EditText(host.context).apply {
        hint = host.string(R.string.profile_name_hint)
        inputType = InputType.TYPE_CLASS_TEXT
        initial?.let(::setText)
    }

    private fun showCreateDialog() {
        val input = nameInput()
        host.dialog()
            .setTitle(R.string.profile_create_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    host.scope.launch {
                        ProfileManager.createProfile(name)
                        host.toast(R.string.profile_created)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(profile: Profile) {
        val input = nameInput(profile.name)
        host.dialog()
            .setTitle(R.string.profile_rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    host.scope.launch {
                        ProfileManager.renameProfile(profile.id, newName)
                        host.toast(R.string.profile_renamed)
                        refresh()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showManagementDialog() {
        host.scope.launch {
            val profiles = ProfileManager.getAllProfiles()
            host.dialog()
                .setTitle(R.string.profile_manage_title)
                .setItems(profiles.map { it.name }.toTypedArray()) { _, which ->
                    profiles.getOrNull(which)?.let { showActionsDialog(it, profiles.size) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showActionsDialog(profile: Profile, profileCount: Int) {
        val actions = buildList<Pair<Int, () -> Unit>> {
            add(R.string.profile_action_switch to {
                if (profile.id == ProfileManager.activeProfileId) {
                    switchTo(profile)
                } else {
                    ProfileSwitchPinGuard.verifyCurrentProfile(host.context) { switchTo(profile) }
                }
            })
            add(R.string.profile_action_rename to { showRenameDialog(profile) })
            add(R.string.profile_action_color to { showColorDialog(profile) })
            if (profileCount > 1) add(R.string.profile_action_delete to { showDeleteDialog(profile) })
        }
        host.dialog()
            .setTitle(profile.name)
            .setItems(actions.map { host.string(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun switchTo(profile: Profile) {
        if (!host.fragment.isAdded) return
        val context = host.context
        val oldLang = ProfileManager.activeProfileId?.let { AppLanguageManager.getProfileLanguage(context, it) }
        val oldTheme = UserPreferences.selectedTheme
        host.scope.launch {
            ProfileManager.switchToProfile(profile.id)
            val newLang = AppLanguageManager.getProfileLanguage(context, profile.id)
            if (newLang != (oldLang ?: AppLanguageManager.SYSTEM_LANGUAGE) || UserPreferences.selectedTheme != oldTheme) {
                host.restartActivity()
            } else {
                when (val activity = host.fragment.activity) {
                    is MainMobileActivity -> activity.recreateProviderHome()
                    is MainTvActivity -> activity.recreateProviderHome()
                }
            }
        }
    }

    private fun showColorDialog(profile: Profile) {
        ProfileColorPicker.show(host.context, profile.avatarColor) { color ->
            host.scope.launch {
                if (ProfileManager.setProfileColor(profile.id, color)) {
                    host.toast(R.string.profile_color_changed)
                    if (profile.id == ProfileManager.activeProfileId) host.fragment.activity?.recreate()
                }
            }
        }
    }

    private fun showDeleteDialog(profile: Profile) {
        host.dialog()
            .setTitle(R.string.profile_delete_title)
            .setMessage(host.string(R.string.profile_delete_message, profile.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                host.scope.launch {
                    if (ProfileManager.deleteProfile(profile.id)) {
                        host.toast(R.string.profile_deleted)
                        if (ProfileManager.activeProfile?.id == profile.id) host.restartActivity()
                    } else {
                        host.toast(R.string.profile_delete_error)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
