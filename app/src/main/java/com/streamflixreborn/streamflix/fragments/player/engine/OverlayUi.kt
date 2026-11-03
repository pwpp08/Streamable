package com.streamflixreborn.streamflix.fragments.player.engine

import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.IntroDbClient

/** Label helpers for the skip pill and the "skipped" message of the NextPlayer controls. */
object OverlayUi {

    fun skipLabel(segment: IntroDbClient.Segment): Int = when (segment.type) {
        IntroDbClient.SegmentType.RECAP -> R.string.player_skip_recap
        else -> R.string.player_skip_intro
    }

    fun skippedLabel(segment: IntroDbClient.Segment): Int = when (segment.type) {
        IntroDbClient.SegmentType.RECAP -> R.string.player_skipped_recap
        else -> R.string.player_skipped_intro
    }
}
