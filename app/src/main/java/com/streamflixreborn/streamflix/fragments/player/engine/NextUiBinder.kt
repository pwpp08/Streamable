package com.streamflixreborn.streamflix.fragments.player.engine

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
import com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView.Settings
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.nextui.NextPlayerOverlayView
import com.streamflixreborn.streamflix.nextui.Picker
import com.streamflixreborn.streamflix.nextui.PickerAction
import com.streamflixreborn.streamflix.nextui.PickerItem
import com.streamflixreborn.streamflix.nextui.UpNext
import com.streamflixreborn.streamflix.utils.UserPreferences

/**
 * Glue between the NextPlayer Compose controls ([NextPlayerOverlayView]) and what the player
 * fragments already own. The audio, subtitle, quality and server lists are the lists the legacy
 * settings panel keeps (already filtered to the user's spoken languages), so both UIs agree.
 */
class NextUiBinder(
    private val ui: NextPlayerOverlayView,
    private val settings: PlayerSettingsView,
    isTv: Boolean,
) {
    private val context: Context get() = ui.context
    private val model get() = ui.model

    /** Set while a skip pill is only the fixed "+85 s" fallback (no segment data for this title). */
    var skipIsFallback: Boolean = false

    init {
        model.isTv = isTv
        model.seekIncrementMs = 10_000L
        model.gesturesEnabled = UserPreferences.playerGestures
        // Every video opens in Auto fit; a change from the player's button lasts for this video only.
        UserPreferences.playerResize = UserPreferences.PlayerResize.Auto
        model.initialContentScale = UserPreferences.playerResize.contentScale
        model.isPictureInPictureSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !isTv &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    fun refreshGestureSetting() {
        model.gesturesEnabled = UserPreferences.playerGestures
    }

    fun setHeader(title: String, subtitle: String) {
        model.title = title
        model.subtitle = subtitle
    }

    fun setEpisodeNavigation(hasPrevious: Boolean, hasNext: Boolean) {
        model.hasPrevious = hasPrevious
        model.hasNext = hasNext
    }

    fun setServers(servers: List<Video.Server>, current: Video.Server?) {
        model.showServerButton = servers.size > 1
        model.serverName = current?.name
    }

    fun showMessage(text: String) = model.showMessage(text)

    fun showSkip(label: String?) {
        model.skipLabel = label
    }

    fun showUpNext(next: Video.Type.Episode, secondsLeft: Int?) {
        val wasShowing = model.upNext != null
        model.upNext = UpNext(
            label = context.getString(R.string.player_next_episode_up_next),
            meta = context.getString(
                R.string.tv_show_item_season_number_episode_number,
                next.season.number,
                next.number,
            ),
            title = next.title ?: context.getString(R.string.episode_number, next.number),
            countdownText = if (secondsLeft != null) {
                context.getString(R.string.player_next_episode_autoplay_in, secondsLeft)
            } else {
                context.getString(R.string.player_next_episode_ready)
            },
            secondsLeft = secondsLeft,
            totalSeconds = UserPreferences.upNextSeconds,
            playLabel = context.getString(R.string.player_next_episode_action),
            cancelLabel = context.getString(R.string.player_up_next_cancel),
        )
        if (!wasShowing) loadPoster(next.poster ?: next.tvShow.poster)
    }

    fun hideUpNext() {
        model.upNext = null
        model.upNextPoster = null
    }

    val isUpNextShowing: Boolean get() = model.upNext != null

    private fun loadPoster(url: String?) {
        model.upNextPoster = null
        if (url.isNullOrBlank()) return
        Glide.with(context.applicationContext)
            .asBitmap()
            .load(url)
            .override(240, 135)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (model.upNext != null) model.upNextPoster = resource
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    // Pickers ------------------------------------------------------------------------------

    fun showServers() {
        model.picker = Picker(
            title = context.getString(R.string.player_settings_servers_title),
            items = Settings.Server.list.map { server ->
                PickerItem(label = server.name, selected = server.isSelected) {
                    settings.selectServer(server)
                }
            },
        )
    }

    fun showQuality() {
        val auto = Settings.Quality.Auto
        val items = Settings.Quality.list.map { quality ->
            when (quality) {
                is Settings.Quality.Auto -> {
                    val playing = auto.currentTrack
                    PickerItem(
                        label = if (playing != null && auto.isSelected) {
                            context.getString(R.string.player_settings_quality_auto_selected, playing.height)
                        } else {
                            context.getString(R.string.player_settings_quality_auto)
                        },
                        selected = auto.isSelected,
                    ) { settings.selectQuality(quality) }
                }

                is Settings.Quality.VideoTrackInformation -> PickerItem(
                    label = context.getString(R.string.player_settings_quality, quality.height),
                    selected = quality.isSelected,
                ) { settings.selectQuality(quality) }
            }
        }
        model.picker = Picker(
            title = context.getString(R.string.player_settings_quality_title),
            items = items,
        )
    }

    fun showAudio() {
        val tracks = Settings.Audio.list.filterIsInstance<Settings.Audio.AudioTrackInformation>()
        val hasToggle = Settings.Audio.list.contains(Settings.Audio.ShowAllLanguages)
        model.picker = Picker(
            title = context.getString(R.string.player_settings_audio_title),
            items = tracks.map { track ->
                PickerItem(label = track.name, selected = track.isSelected) {
                    settings.selectAudio(track)
                }
            },
            actions = buildList {
                if (hasToggle) add(languageFilterAction { showAudio() })
            },
        )
    }

    fun showSubtitles() {
        val list = Settings.Subtitle.list
        val tracks = list.filterIsInstance<Settings.Subtitle.TextTrackInformation>()
        val none = Settings.Subtitle.None
        val items = buildList {
            add(
                PickerItem(
                    label = context.getString(R.string.player_settings_subtitles_off),
                    selected = none.isSelected,
                ) { settings.selectSubtitle(none) },
            )
            tracks.forEach { track ->
                add(
                    PickerItem(
                        label = listOfNotNull(track.language, track.label.takeIf { it.isNotBlank() })
                            .distinct()
                            .joinToString(" • ")
                            .ifBlank { track.name },
                        selected = track.isSelected,
                    ) { settings.selectSubtitle(track) },
                )
            }
        }
        model.picker = Picker(
            title = context.getString(R.string.player_settings_subtitles_title),
            items = items,
            actions = buildList {
                if (list.contains(Settings.Subtitle.ShowAllLanguages)) {
                    add(languageFilterAction { showSubtitles() })
                }
                // More subtitles, then every subtitle setting (look, timing, local file) in one page.
                add(PickerAction(context.getString(R.string.player_settings_open_subtitles_title)) { settings.showOpenSubtitles() })
                if (UserPreferences.subdlApiKey.isNotEmpty()) {
                    add(PickerAction(context.getString(R.string.player_settings_subdl_title)) { settings.showSubDL() })
                }
                add(PickerAction(context.getString(R.string.player_subtitle_settings)) { settings.showSubtitles() })
            },
        )
    }

    private fun languageFilterAction(reopen: () -> Unit) = PickerAction(
        label = context.getString(R.string.player_settings_show_all_languages),
        dismiss = false,
    ) {
        settings.toggleLanguageFilter()
        reopen()
    }
}
