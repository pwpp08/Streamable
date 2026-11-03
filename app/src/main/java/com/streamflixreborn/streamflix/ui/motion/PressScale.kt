package com.streamflixreborn.streamflix.ui.motion

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.streamflixreborn.streamflix.R

/**
 * Phone press feedback for cards: shrinks to [pressedScale] (0.96) while the finger is down and
 * springs back on release/cancel, using [Spring.SpatialFast]. Ripples and clicks still work
 * because the touch event is never consumed.
 *
 * ```
 * PressScale.attach(binding.root)            // in a mobile ViewHolder
 * ```
 * Note: this sets the view's OnTouchListener (replacing any other one).
 */
class PressScale(private val pressedScale: Float) : View.OnTouchListener {

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.springScale(pressedScale, Spring.SpatialFast)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.springScale(1f, Spring.SpatialFast)
        }
        return false
    }

    companion object {
        @SuppressLint("ClickableViewAccessibility")
        @JvmStatic
        @JvmOverloads
        fun attach(
            view: View,
            pressedScale: Float = ResourcesCompat.getFloat(view.resources, R.dimen.sf_press_scale),
        ): PressScale = PressScale(pressedScale).also { view.setOnTouchListener(it) }
    }
}
