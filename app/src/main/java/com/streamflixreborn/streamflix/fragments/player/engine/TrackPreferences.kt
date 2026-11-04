package com.streamflixreborn.streamflix.fragments.player.engine

import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.streamflixreborn.streamflix.utils.LanguageMatcher
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Initial track selection rules: spoken-language audio/subtitles, the subtitle mode and the
 * quality cap. Manual choices made from the player settings menu still override these.
 */
@OptIn(UnstableApi::class)
object TrackPreferences {

    private const val DATA_SAVER_HEIGHT = 720

    /** Widest picture (width / height) still counted as part of its height class. */
    private const val MAX_ASPECT = 3

    fun apply(context: Context, builder: TrackSelectionParameters.Builder): TrackSelectionParameters.Builder {
        val spoken = UserPreferences.spokenLanguages
        builder.setPreferredAudioLanguages(*spoken.toTypedArray())
        builder.setSelectUndeterminedTextLanguage(false)
        when (UserPreferences.subtitleMode) {
            UserPreferences.SubtitleMode.ALWAYS -> builder.enableText(spoken)
            UserPreferences.SubtitleMode.OFF,
            UserPreferences.SubtitleMode.AUTO -> builder.disableText()
        }
        applyQuality(context, builder)
        return builder
    }

    /** Subtitles in the spoken languages are picked automatically. */
    fun TrackSelectionParameters.Builder.enableText(spoken: List<String>): TrackSelectionParameters.Builder =
        setPreferredTextLanguages(*spoken.toTypedArray())
            .setIgnoredTextSelectionFlags(0)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)

    /** Only forced (narrative) subtitles can still be picked automatically. */
    fun TrackSelectionParameters.Builder.disableText(): TrackSelectionParameters.Builder =
        setPreferredTextLanguages()
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)

    /**
     * A subtitle the user just loaded (local file, OpenSubtitles, SubDL) is added with the
     * DEFAULT flag; make sure it is shown whatever the subtitle mode is.
     */
    fun preferSideloadedSubtitle(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredTextLanguages()
            .setIgnoredTextSelectionFlags(0)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }

    fun applyQuality(context: Context, builder: TrackSelectionParameters.Builder) {
        val display = Util.getCurrentDisplayModeSize(context)
        val displayLong = max(display.x, display.y)
        val displayShort = min(display.x, display.y)
        val (capWidth, capHeight) = videoSizeCap(
            displayLong = displayLong,
            displayShort = displayShort,
            maxResolution = UserPreferences.maxResolution,
            dataSaver = UserPreferences.dataSaverOnMobile && isMetered(context),
        )
        builder.setViewportSize(displayLong, displayShort, true)
        builder.setMaxVideoSize(capWidth, capHeight)
        builder.setForceHighestSupportedBitrate(UserPreferences.qualityMode == UserPreferences.QualityMode.MAX)
    }

    /**
     * Largest video size worth decoding. A small tolerance keeps encodes such as 1920x1088
     * eligible on a 1080p display.
     */
    fun videoSizeCap(displayLong: Int, displayShort: Int, maxResolution: Int, dataSaver: Boolean): Pair<Int, Int> {
        var height = if (displayShort > 0) displayShort else Int.MAX_VALUE
        if (maxResolution > 0) height = min(height, maxResolution)
        if (dataSaver) height = min(height, DATA_SAVER_HEIGHT)
        // The quality class is the height: a 2.39:1 film's 1080p rung is 2592x1080, which a
        // 16:9 width cap would wrongly drop, leaving the 720p rung.
        val width = if (height == Int.MAX_VALUE) Int.MAX_VALUE else height * MAX_ASPECT
        fun tolerant(value: Int) = if (value == Int.MAX_VALUE) value else (value * 1.02f).roundToInt()
        return tolerant(width) to tolerant(height)
    }

    fun isMetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.isActiveNetworkMetered
    }.getOrDefault(false)

    /** ISO-639-1 language of the currently selected audio track, or null when unknown. */
    fun selectedAudioLanguage(tracks: Tracks): String? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    val format = group.getTrackFormat(i)
                    return LanguageMatcher.detect(format.language, format.label)
                }
            }
        }
        return null
    }

    fun hasSelectedAudio(tracks: Tracks): Boolean =
        tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }

    /**
     * Implements [UserPreferences.SubtitleMode.AUTO]: subtitles are switched on only when the
     * selected audio track is in a language the user doesn't speak. Stops adjusting as soon
     * as the user picks a subtitle (or "off") manually.
     */
    class AutoSubtitles(private val player: Player) {
        private var lastDecision: Boolean? = null
        private var lastApplied: TextState = TextState.of(player.trackSelectionParameters)

        fun onTracksChanged(tracks: Tracks) {
            if (UserPreferences.subtitleMode != UserPreferences.SubtitleMode.AUTO) return
            if (TextState.of(player.trackSelectionParameters) != lastApplied) return
            if (!hasSelectedAudio(tracks)) return

            val audioLanguage = selectedAudioLanguage(tracks)
            val wantText = audioLanguage != null &&
                !LanguageMatcher.isSpoken(audioLanguage, UserPreferences.spokenLanguages)
            if (wantText == lastDecision) return
            lastDecision = wantText

            val builder = player.trackSelectionParameters.buildUpon()
            if (wantText) builder.enableText(UserPreferences.spokenLanguages) else builder.disableText()
            player.trackSelectionParameters = builder.build()
            lastApplied = TextState.of(player.trackSelectionParameters)
        }
    }

    private data class TextState(
        val preferredText: List<String>,
        val ignoredFlags: Int,
        val disabled: Boolean,
        val overrides: Set<Any>,
    ) {
        companion object {
            fun of(params: TrackSelectionParameters) = TextState(
                preferredText = params.preferredTextLanguages,
                ignoredFlags = params.ignoredTextSelectionFlags,
                disabled = C.TRACK_TYPE_TEXT in params.disabledTrackTypes,
                overrides = params.overrides.values.filter { it.type == C.TRACK_TYPE_TEXT }.toSet(),
            )
        }
    }
}
