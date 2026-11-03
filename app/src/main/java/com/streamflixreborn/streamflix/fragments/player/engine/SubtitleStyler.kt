package com.streamflixreborn.streamflix.fragments.player.engine

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.streamflixreborn.streamflix.fragments.player.settings.PlayerSettingsView
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp

/**
 * Subtitle look = [UserPreferences.subtitlePreset], unless the user customised the caption
 * style in the player menu (any CAPTION_* value differing from the default), in which case the
 * custom style wins. "Reset style" in the menu therefore goes back to the preset.
 * The text size from the menu is multiplied by the preset's size factor.
 */
@OptIn(UnstableApi::class)
object SubtitleStyler {

    private val DEFAULT get() = PlayerSettingsView.Settings.Subtitle.Style.DEFAULT

    fun presetStyle(preset: UserPreferences.SubtitlePreset): CaptionStyleCompat = when (preset) {
        UserPreferences.SubtitlePreset.CLASSIC -> DEFAULT
        UserPreferences.SubtitlePreset.YELLOW -> CaptionStyleCompat(
            Color.YELLOW, Color.TRANSPARENT, Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null,
        )
        UserPreferences.SubtitlePreset.OUTLINE,
        UserPreferences.SubtitlePreset.LARGE -> CaptionStyleCompat(
            Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null,
        )
    }

    fun sizeMultiplier(preset: UserPreferences.SubtitlePreset): Float =
        if (preset == UserPreferences.SubtitlePreset.LARGE) 1.5f else 1f

    fun isCustomised(style: CaptionStyleCompat = UserPreferences.captionStyle): Boolean {
        val default = DEFAULT
        return style.foregroundColor != default.foregroundColor ||
            style.backgroundColor != default.backgroundColor ||
            style.windowColor != default.windowColor ||
            style.edgeType != default.edgeType ||
            style.edgeColor != default.edgeColor
    }

    /** The style actually shown; menu edits start from this. */
    fun effectiveStyle(): CaptionStyleCompat {
        val custom = UserPreferences.captionStyle
        return if (isCustomised(custom)) custom else presetStyle(UserPreferences.subtitlePreset)
    }

    fun apply(subtitleView: SubtitleView?) {
        subtitleView ?: return
        subtitleView.setStyle(effectiveStyle())
        subtitleView.setFractionalTextSize(
            SubtitleView.DEFAULT_TEXT_SIZE_FRACTION *
                UserPreferences.captionTextSize *
                sizeMultiplier(UserPreferences.subtitlePreset)
        )
        subtitleView.setPadding(0, 0, 0, UserPreferences.captionMargin.dp(subtitleView.context))
    }
}
