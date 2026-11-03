package com.streamflixreborn.streamflix.ui.player

import androidx.core.graphics.ColorUtils
import androidx.media3.ui.DefaultTimeBar
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.google.android.material.R as M

/** Theme-driven styling for the Media3 controller pieces that only take colours in code. */
object PlayerChrome {

    /** Played/scrubber in primary; buffered and track as translucent onSurface. */
    fun styleTimeBar(timeBar: DefaultTimeBar) {
        val context = timeBar.context
        val primary = ThemeManager.color(context, androidx.appcompat.R.attr.colorPrimary)
        val onSurface = ThemeManager.color(context, M.attr.colorOnSurface)
        timeBar.setPlayedColor(primary)
        timeBar.setScrubberColor(primary)
        timeBar.setBufferedColor(ColorUtils.setAlphaComponent(onSurface, 0x66))
        timeBar.setUnplayedColor(ColorUtils.setAlphaComponent(onSurface, 0x33))
    }

    /** Up Next countdown ring: [secondsLeft] of [totalSeconds]; animates after the first tick. */
    fun setCountdown(ring: CircularProgressIndicator, secondsLeft: Int, totalSeconds: Int, animate: Boolean) {
        val total = totalSeconds.coerceAtLeast(1)
        if (ring.max != total) ring.max = total
        ring.setProgressCompat(secondsLeft.coerceIn(0, total), animate)
    }
}
