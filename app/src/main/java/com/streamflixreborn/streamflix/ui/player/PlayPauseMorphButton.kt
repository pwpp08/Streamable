package com.streamflixreborn.streamflix.ui.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import com.google.android.material.shape.MaterialShapeDrawable
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.ui.motion.Spring
import com.streamflixreborn.streamflix.ui.motion.Springs
import com.streamflixreborn.streamflix.ui.motion.springScale
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.google.android.material.R as M

/**
 * The player's big play/pause control (`@id/exo_play_pause`). It stays an ImageView because
 * Media3's PlayerControlView casts that id to ImageView and swaps the play/pause drawable itself.
 *
 * M3 Expressive shape morph: a circle while paused, a rounded square while playing (read from the
 * content description Media3 sets with the icon), squeezed to a tighter square while pressed.
 * Only this small view redraws during the morph; press and focus also spring the scale.
 */
class PlayPauseMorphButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val shape = MaterialShapeDrawable()
    private val animated: Boolean
    private val pauseDescription: String =
        context.getString(androidx.media3.ui.R.string.exo_controls_pause_description)

    /** Corner size as a fraction of the button's smaller side (0.5 = circle). */
    private var cornerFraction = CIRCLE
    private val cornerHolder = FloatValueHolder(CIRCLE)
    private val cornerSpring = SpringAnimation(cornerHolder).apply {
        spring = Springs.force(context, Spring.SpatialFast).setFinalPosition(CIRCLE)
        addUpdateListener { _, value, _ -> applyCorner(value) }
    }

    private var isPlayingState = false
    private var isPressedState = false

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PlayPauseMorphButton, defStyleAttr, 0)
        shape.fillColor = a.getColorStateList(R.styleable.PlayPauseMorphButton_morphFillColor)
            ?: ColorStateList.valueOf(ThemeManager.color(context, M.attr.colorPrimaryContainer))
        animated = a.getBoolean(R.styleable.PlayPauseMorphButton_morphAnimated, true)
        a.recycle()

        val ripple = ColorStateList.valueOf(
            ThemeManager.color(context, androidx.appcompat.R.attr.colorControlHighlight)
        )
        background = RippleDrawable(ripple, shape, null)
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCorner(cornerFraction)
    }

    override fun setContentDescription(contentDescription: CharSequence?) {
        super.setContentDescription(contentDescription)
        // Media3 shows the pause icon (and its description) while playing.
        val playing = contentDescription?.toString() == pauseDescription
        if (playing != isPlayingState) {
            isPlayingState = playing
            morphTo(targetFraction())
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val pressed = isPressed
        if (pressed != isPressedState) {
            isPressedState = pressed
            morphTo(targetFraction())
            springScale(if (pressed) PRESSED_SCALE else if (isFocused) FOCUSED_SCALE else 1f, Spring.SpatialFast)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        springScale(if (gainFocus) FOCUSED_SCALE else 1f, Spring.SpatialFast)
    }

    override fun onDetachedFromWindow() {
        cornerSpring.cancel()
        super.onDetachedFromWindow()
    }

    private fun targetFraction() = when {
        isPressedState -> PRESSED
        isPlayingState -> PLAYING
        else -> CIRCLE
    }

    private fun morphTo(fraction: Float) {
        if (!animated || !isAttachedToWindow || Springs.isReducedMotion(context)) {
            cornerSpring.cancel()
            cornerHolder.value = fraction
            applyCorner(fraction)
            return
        }
        cornerSpring.animateToFinalPosition(fraction)
    }

    private fun applyCorner(fraction: Float) {
        cornerFraction = fraction
        val side = minOf(width, height)
        if (side > 0) shape.setCornerSize(side * fraction)
    }

    private companion object {
        const val CIRCLE = 0.5f
        const val PLAYING = 0.3f
        const val PRESSED = 0.2f
        const val PRESSED_SCALE = 0.92f
        const val FOCUSED_SCALE = 1.08f
    }
}
