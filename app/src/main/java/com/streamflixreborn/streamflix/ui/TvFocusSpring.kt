package com.streamflixreborn.streamflix.ui

import android.view.View
import com.streamflixreborn.streamflix.ui.motion.FocusScaleAnimator

/**
 * Spring focus scale for TV controls that already show focus themselves (tonal buttons with a
 * primary focused fill, rows with a ring in their background): scale only, no lift or extra ring.
 */
fun View.tvFocusSpring(scale: Float = 1.05f) {
    FocusScaleAnimator.attach(this, focusedScale = scale, liftPx = 0f, ringWidthPx = 0)
}
