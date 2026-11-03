package com.streamflixreborn.streamflix.ui.motion

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.annotation.Px
import com.google.android.material.card.MaterialCardView
import androidx.core.content.res.ResourcesCompat
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.google.android.material.R as M

/**
 * TV focus pattern for cards and posters (D-pad): on focus the view springs from 1.0 to
 * [focusedScale] (1.08) with [Spring.SpatialFast], lifts by [liftPx] and shows a focus ring in
 * `?attr/colorPrimary`; on blur it springs back.
 *
 * - [MaterialCardView]: the ring is the card stroke, so it follows the card's shape.
 * - Any other view: the ring is a rounded-rect foreground (API 23+) with [ringCornerRadiusPx].
 *
 * Keeps an existing OnFocusChangeListener working by calling it after the animation.
 *
 * ```
 * // in a TV ViewHolder / adapter bind:
 * FocusScaleAnimator.attach(binding.root)                         // card or poster
 * FocusScaleAnimator.attach(binding.ivPoster, ringCornerRadiusPx = 16.dp)
 * ```
 * Give the parent `android:clipChildren="false"` (and `clipToPadding="false"`) so the scaled
 * card is not cut off. Keep the focused item's neighbours unscaled; scale only, no blur/shadow
 * stacks, to stay cheap on 1 GB TV devices.
 */
class FocusScaleAnimator(
    private val focusedScale: Float,
    @Px private val liftPx: Float,
    @Px private val ringWidthPx: Int,
    @Px private val ringCornerRadiusPx: Float,
    private val previous: View.OnFocusChangeListener?,
) : View.OnFocusChangeListener {

    private var ring: GradientDrawable? = null
    private var originalStrokeWidth = -1
    private var originalStrokeColor: ColorStateList? = null

    override fun onFocusChange(v: View, hasFocus: Boolean) {
        v.springScale(if (hasFocus) focusedScale else 1f, Spring.SpatialFast)
        if (liftPx > 0f) v.springTranslationZ(if (hasFocus) liftPx else 0f, Spring.SpatialFast)
        if (ringWidthPx > 0) showRing(v, hasFocus)
        previous?.onFocusChange(v, hasFocus)
    }

    private fun showRing(v: View, show: Boolean) {
        val color = ThemeManager.color(v.context, androidx.appcompat.R.attr.colorPrimary)
        if (v is MaterialCardView) {
            if (originalStrokeWidth < 0) {
                originalStrokeWidth = v.strokeWidth
                originalStrokeColor = v.strokeColorStateList
            }
            if (show) {
                v.setStrokeColor(ColorStateList.valueOf(color))
                v.strokeWidth = ringWidthPx
            } else {
                v.setStrokeColor(originalStrokeColor)
                v.strokeWidth = originalStrokeWidth
            }
            return
        }
        val drawable = ring ?: GradientDrawable().apply {
            cornerRadius = ringCornerRadiusPx
            setColor(0)
        }.also { ring = it }
        drawable.setStroke(ringWidthPx, color)
        v.foreground = if (show) drawable else null
    }

    companion object {
        /**
         * Installs the focus animation on [view]. Values default to the design tokens
         * (`@dimen/sf_focus_scale` = 1.08, 6dp lift, 3dp ring, 16dp ring radius).
         */
        @JvmStatic
        @JvmOverloads
        fun attach(
            view: View,
            focusedScale: Float = ResourcesCompat.getFloat(view.resources, R.dimen.sf_focus_scale),
            @Px liftPx: Float = 6f * view.resources.displayMetrics.density,
            @Px ringWidthPx: Int = (3f * view.resources.displayMetrics.density).toInt(),
            @Px ringCornerRadiusPx: Float = 16f * view.resources.displayMetrics.density,
        ): FocusScaleAnimator {
            val current = view.onFocusChangeListener
            val previous = (current as? FocusScaleAnimator)?.previous ?: current
            return FocusScaleAnimator(focusedScale, liftPx, ringWidthPx, ringCornerRadiusPx, previous)
                .also { view.onFocusChangeListener = it }
        }
    }
}
