package com.streamflixreborn.streamflix.fragments.player.engine

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.IntroDbClient

/** Small view helpers for the overlays in `view_player_overlays_*.xml`. */
object OverlayUi {

    private const val MESSAGE_DURATION_MS = 3_000L
    private const val FADE_MS = 200L

    /** Short, non-blocking message at the top of the player (e.g. "Switched to …"). */
    fun showMessage(card: View, text: TextView, message: CharSequence, durationMs: Long = MESSAGE_DURATION_MS) {
        text.text = message
        card.animate().cancel()
        card.removeCallbacks(card.getTag(R.id.card_player_message) as? Runnable)
        if (!card.isVisible) {
            card.alpha = 0f
            card.isVisible = true
        }
        card.animate().alpha(1f).setDuration(FADE_MS).start()
        val hide = Runnable {
            card.animate().alpha(0f).setDuration(FADE_MS).withEndAction { card.isVisible = false }.start()
        }
        card.setTag(R.id.card_player_message, hide)
        card.postDelayed(hide, durationMs)
    }

    fun clearMessage(card: View) {
        card.removeCallbacks(card.getTag(R.id.card_player_message) as? Runnable)
        card.animate().cancel()
        card.isVisible = false
    }

    fun skipLabel(segment: IntroDbClient.Segment): Int = when (segment.type) {
        IntroDbClient.SegmentType.RECAP -> R.string.player_skip_recap
        else -> R.string.player_skip_intro
    }

    fun skippedLabel(segment: IntroDbClient.Segment): Int = when (segment.type) {
        IntroDbClient.SegmentType.RECAP -> R.string.player_skipped_recap
        else -> R.string.player_skipped_intro
    }

    fun showSkipButton(button: MaterialButton, segment: IntroDbClient.Segment, requestFocus: Boolean) {
        button.setText(skipLabel(segment))
        if (!button.isVisible) {
            button.alpha = 0f
            button.isVisible = true
            button.animate().alpha(1f).setDuration(FADE_MS).start()
        }
        if (requestFocus) button.post { if (button.isVisible) button.requestFocus() }
    }

    fun hideSkipButton(button: MaterialButton) {
        if (!button.isVisible) return
        button.animate().cancel()
        button.isVisible = false
    }
}
