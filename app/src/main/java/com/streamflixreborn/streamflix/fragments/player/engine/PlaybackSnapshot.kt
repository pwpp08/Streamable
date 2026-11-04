package com.streamflixreborn.streamflix.fragments.player.engine

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.streamflixreborn.streamflix.utils.LanguageMatcher

/**
 * What the viewer had set up when a stream is replaced by another server's stream: the
 * position, speed, chosen subtitle and audio track, and subtitles they loaded themselves
 * (local file, OpenSubtitles, SubDL). Taken from the old player state, then applied to the
 * new media item before it is prepared, so switching servers never resets any of it.
 */
@OptIn(UnstableApi::class)
class PlaybackSnapshot private constructor(
    val positionMs: Long,
    val speed: Float,
    private val text: Text,
    private val audioLanguage: String?,
    /** Subtitles the viewer added, as configurations ready for the next media item. */
    val sideloaded: List<MediaItem.SubtitleConfiguration>,
) {

    sealed interface Text {
        /** The old stream had no subtitle tracks, so there is nothing to carry over. */
        data object Unchanged : Text

        /** The viewer turned subtitles off. */
        data object Off : Text

        /** A subtitle in [language] (ISO-639-1) was showing. */
        data class Language(val language: String) : Text

        /** A subtitle the viewer loaded themselves was showing. */
        data object Sideloaded : Text
    }

    /** Applies speed and track preferences; call after the new media item is set, before prepare. */
    fun restore(player: Player) {
        if (speed > 0f && speed != player.playbackParameters.speed) player.setPlaybackSpeed(speed)

        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        audioLanguage?.let { builder.setPreferredAudioLanguage(it) }
        when (text) {
            Text.Unchanged -> Unit
            Text.Off -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            is Text.Language -> builder
                .setPreferredTextLanguage(text.language)
                .setIgnoredTextSelectionFlags(0)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            Text.Sideloaded -> builder
                .setPreferredTextLanguages()
                .setIgnoredTextSelectionFlags(0)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        }
        player.trackSelectionParameters = builder.build()
        Log.i(TAG, "restored position=${positionMs}ms speed=$speed text=$text audio=$audioLanguage sideloaded=${sideloaded.size}")
    }

    companion object {
        private const val TAG = "SFSwitch"

        /**
         * Reads the state of [player] while the old stream is still loaded. [serverSubtitleUris] are
         * the subtitle files that came with the old server's video; anything else on the media
         * item was added by the viewer and is carried to the next stream.
         */
        fun capture(player: Player, serverSubtitleUris: Set<String>): PlaybackSnapshot {
            val tracks = player.currentTracks
            val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

            val sideloaded = sideloadedOnly(
                player.currentMediaItem?.localConfiguration?.subtitleConfigurations.orEmpty(),
                serverSubtitleUris,
            )
            val sideloadedLabels = sideloaded.mapNotNull { it.label?.toString() }.toSet()

            var selectedLabel: String? = null
            var selectedLanguage: String? = null
            var anySelected = false
            for (group in textGroups) {
                for (i in 0 until group.length) {
                    if (!group.isTrackSelected(i)) continue
                    anySelected = true
                    val format = group.getTrackFormat(i)
                    selectedLabel = format.label
                    selectedLanguage = LanguageMatcher.detect(format.language, format.label)
                }
            }
            val disabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
            val text = when {
                textGroups.isEmpty() -> Text.Unchanged
                disabled || !anySelected -> Text.Off
                selectedLabel != null && selectedLabel in sideloadedLabels -> Text.Sideloaded
                selectedLanguage != null -> Text.Language(selectedLanguage)
                else -> Text.Unchanged
            }

            val audio = TrackPreferences.selectedAudioLanguage(tracks)

            // The sideloaded subtitle that was showing keeps the DEFAULT flag, so it is selected again.
            val carried = sideloaded.map { config ->
                val wasSelected = text == Text.Sideloaded && config.label?.toString() == selectedLabel
                config.buildUpon()
                    .setSelectionFlags(if (wasSelected) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }

            return PlaybackSnapshot(
                positionMs = player.currentPosition.coerceAtLeast(0),
                speed = player.playbackParameters.speed,
                text = text,
                audioLanguage = audio,
                sideloaded = carried,
            ).also {
                Log.i(TAG, "captured position=${it.positionMs}ms speed=${it.speed} text=$text audio=$audio sideloaded=${carried.size}")
            }
        }

        /** Subtitle configurations whose file did not come with the server's video. */
        fun sideloadedOnly(
            configurations: List<MediaItem.SubtitleConfiguration>,
            serverSubtitleUris: Set<String>,
        ): List<MediaItem.SubtitleConfiguration> =
            configurations.filter { it.uri.toString() !in serverSubtitleUris }
    }
}
