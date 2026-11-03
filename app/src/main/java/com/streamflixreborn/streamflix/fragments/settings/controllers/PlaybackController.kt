package com.streamflixreborn.streamflix.fragments.settings.controllers

import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainMobileActivity
import com.streamflixreborn.streamflix.utils.IdleDimSettings
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.UserPreferences.BufferProfile
import com.streamflixreborn.streamflix.utils.UserPreferences.QualityMode
import com.streamflixreborn.streamflix.utils.UserPreferences.SkipSegmentsMode

/**
 * Settings > Playback. Preference keys equal the UserPreferences key names, so the
 * stored type (String for lists, Boolean for switches) matches what UserPreferences reads.
 */
class PlaybackController(private val host: SettingsHost) : SettingsController {

    override fun bind() {
        host.bindEnumList("QUALITY_MODE", { UserPreferences.qualityMode }, { enumOr(it, QualityMode.MAX) }) {
            UserPreferences.qualityMode = it
        }
        host.bindList("MAX_RESOLUTION", { UserPreferences.maxResolution.toString() }) {
            UserPreferences.maxResolution = it.toIntOrNull() ?: 0
        }
        host.bindSwitch("DATA_SAVER_ON_MOBILE", { UserPreferences.dataSaverOnMobile }) {
            UserPreferences.dataSaverOnMobile = it
        }
        host.bindEnumList("BUFFER_PROFILE", { UserPreferences.bufferProfile }, { enumOr(it, BufferProfile.AUTO) }) {
            UserPreferences.bufferProfile = it
        }

        host.bindSwitch("AUTOPLAY", { UserPreferences.autoplay }) { UserPreferences.autoplay = it }
        host.bindList("UP_NEXT_SECONDS", { UserPreferences.upNextSeconds.toString() }) {
            UserPreferences.upNextSeconds = it.toIntOrNull() ?: 10
        }
        host.find<EditTextPreference>("p_settings_autoplay_buffer")?.apply {
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                "${pref.text?.toLongOrNull() ?: 3L} s"
            }
            setOnPreferenceChangeListener { _, newValue ->
                UserPreferences.autoplayBuffer = (newValue as String).toLongOrNull() ?: 3L
                true
            }
        }
        host.bindEnumList(
            "SKIP_SEGMENTS_MODE",
            { UserPreferences.skipSegmentsMode },
            { enumOr(it, SkipSegmentsMode.BUTTON) },
        ) { UserPreferences.skipSegmentsMode = it }

        // TV only
        host.bindSwitch("MATCH_FRAME_RATE", { UserPreferences.matchFrameRate }) { UserPreferences.matchFrameRate = it }
        host.bindSwitch("TUNNELED_PLAYBACK", { UserPreferences.tunneledPlayback }) { UserPreferences.tunneledPlayback = it }

        // Phone only
        host.bindSwitch("PLAYER_GESTURES", { UserPreferences.playerGestures }) { UserPreferences.playerGestures = it }
        host.bindSwitch("KEEP_SCREEN_ON_WHEN_PAUSED", { UserPreferences.keepScreenOnWhenPaused }) {
            UserPreferences.keepScreenOnWhenPaused = it
        }
        host.bindSwitch("IMMERSIVE_MODE", { UserPreferences.immersiveMode }) {
            UserPreferences.immersiveMode = it
            (host.fragment.activity as? MainMobileActivity)?.updateImmersiveMode()
        }

        host.bindSwitch("IDLE_DIM_ENABLED", { UserPreferences.idleDimEnabled }) { UserPreferences.idleDimEnabled = it }
        host.bindList("IDLE_DIM_DELAY_SECONDS", { UserPreferences.idleDimDelaySeconds.toString() }) {
            UserPreferences.idleDimDelaySeconds = it.toIntOrNull() ?: IdleDimSettings.DEFAULT_DELAY_SECONDS
        }
        host.bindList("IDLE_DIM_LEVEL_PERCENT", { UserPreferences.idleDimLevelPercent.toString() }) {
            UserPreferences.idleDimLevelPercent = it.toIntOrNull() ?: IdleDimSettings.DEFAULT_LEVEL_PERCENT
        }

        host.onClick("preferred_player_reset") {
            UserPreferences.removeProfilePreference("preferred_smarttube_package")
            host.toast(R.string.settings_trailer_player_reset)
        }
    }

    override fun refresh() {
        host.refreshList("QUALITY_MODE") { UserPreferences.qualityMode.name }
        host.refreshList("MAX_RESOLUTION") { UserPreferences.maxResolution.toString() }
        host.refreshList("BUFFER_PROFILE") { UserPreferences.bufferProfile.name }
        host.refreshList("UP_NEXT_SECONDS") { UserPreferences.upNextSeconds.toString() }
        host.refreshList("SKIP_SEGMENTS_MODE") { UserPreferences.skipSegmentsMode.name }
        host.refreshList("IDLE_DIM_DELAY_SECONDS") { UserPreferences.idleDimDelaySeconds.toString() }
        host.refreshList("IDLE_DIM_LEVEL_PERCENT") { UserPreferences.idleDimLevelPercent.toString() }
        host.refreshSwitch("IDLE_DIM_ENABLED") { UserPreferences.idleDimEnabled }
        host.refreshSwitch("AUTOPLAY") { UserPreferences.autoplay }
        host.refreshSwitch("DATA_SAVER_ON_MOBILE") { UserPreferences.dataSaverOnMobile }
        host.refreshSwitch("PLAYER_GESTURES") { UserPreferences.playerGestures }
        host.refreshSwitch("KEEP_SCREEN_ON_WHEN_PAUSED") { UserPreferences.keepScreenOnWhenPaused }
    }
}

internal inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
    runCatching { enumValueOf<E>(name) }.getOrDefault(fallback)
