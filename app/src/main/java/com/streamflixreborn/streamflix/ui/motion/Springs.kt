package com.streamflixreborn.streamflix.ui.motion

import android.content.Context
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.streamflixreborn.streamflix.R
import com.google.android.material.R as M

/**
 * M3 Expressive spring presets, read from the MDC 1.14 theme attributes
 * `motionSpring{Fast,Default,Slow}{Spatial,Effects}` (set in Theme.StreamFlix, values/motion.xml).
 *
 * - **Spatial** springs move things (position, scale, size). Slight overshoot (damping 0.8).
 * - **Effects** springs change colour/alpha. Never overshoot (damping 1.0).
 * - Fast = small elements and focus/press feedback; Default = most transitions;
 *   Slow = large surfaces (sheets, full-screen hero changes).
 *
 * The view helpers below reuse one [SpringAnimation] per view+property, so calling them again
 * while an animation runs retargets it smoothly (interruptible, velocity preserved).
 *
 * ```
 * poster.springScale(1.08f)                       // spatialFast by default
 * hero.springTranslationY(0f, Spring.SpatialSlow)
 * scrim.springAlpha(0f)                           // effectsDefault by default
 * ```
 *
 * Reduced motion: when the system animator duration scale is 0 (Developer options or
 * accessibility "Remove animations"), every helper snaps straight to the target.
 */
enum class Spring(
    @AttrRes internal val attr: Int,
    internal val fallbackStiffness: Float,
    internal val fallbackDamping: Float,
) {
    SpatialFast(M.attr.motionSpringFastSpatial, 1400f, 0.8f),
    SpatialDefault(M.attr.motionSpringDefaultSpatial, 700f, 0.8f),
    SpatialSlow(M.attr.motionSpringSlowSpatial, 300f, 0.8f),
    EffectsFast(M.attr.motionSpringFastEffects, 3800f, 1f),
    EffectsDefault(M.attr.motionSpringDefaultEffects, 1600f, 1f),
    EffectsSlow(M.attr.motionSpringSlowEffects, 800f, 1f),
}

object Springs {

    /** A new [SpringForce] for [spec], resolved from [context]'s theme. */
    fun force(context: Context, spec: Spring): SpringForce {
        var stiffness = spec.fallbackStiffness
        var damping = spec.fallbackDamping
        val tv = TypedValue()
        if (context.theme.resolveAttribute(spec.attr, tv, true) && tv.resourceId != 0) {
            val a = context.obtainStyledAttributes(tv.resourceId, M.styleable.MaterialSpring)
            try {
                stiffness = a.getFloat(M.styleable.MaterialSpring_stiffness, stiffness)
                damping = a.getFloat(M.styleable.MaterialSpring_damping, damping)
            } finally {
                a.recycle()
            }
        }
        return SpringForce().setStiffness(stiffness).setDampingRatio(damping)
    }

    fun spatialFast(context: Context) = force(context, Spring.SpatialFast)
    fun spatialDefault(context: Context) = force(context, Spring.SpatialDefault)
    fun spatialSlow(context: Context) = force(context, Spring.SpatialSlow)
    fun effectsFast(context: Context) = force(context, Spring.EffectsFast)
    fun effectsDefault(context: Context) = force(context, Spring.EffectsDefault)
    fun effectsSlow(context: Context) = force(context, Spring.EffectsSlow)

    /** True when the user turned animations off (animator duration scale 0). */
    fun isReducedMotion(context: Context): Boolean = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

    /**
     * Animates [property] of [view] to [target] with [spec], reusing the animation stored under
     * [tagKey]. Snaps when reduced motion is on.
     */
    internal fun animate(
        view: View,
        tagKey: Int,
        property: FloatPropertyCompat<View>,
        target: Float,
        spec: Spring,
        minVisibleChange: Float,
    ) {
        @Suppress("UNCHECKED_CAST")
        val existing = view.getTag(tagKey) as? Pair<SpringAnimation, Spring>
        if (isReducedMotion(view.context)) {
            existing?.first?.cancel()
            property.setValue(view, target)
            return
        }
        val animation = if (existing != null && existing.second == spec) {
            existing.first
        } else {
            existing?.first?.cancel()
            SpringAnimation(view, property).apply {
                spring = force(view.context, spec)
                setMinimumVisibleChange(minVisibleChange)
            }.also { view.setTag(tagKey, it to spec) }
        }
        animation.animateToFinalPosition(target)
    }
}

/** Springs scaleX and scaleY to [target]. Default: [Spring.SpatialFast]. */
fun View.springScale(target: Float, spec: Spring = Spring.SpatialFast) {
    Springs.animate(this, R.id.sf_spring_scale_x, DynamicAnimation.SCALE_X, target, spec, DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
    Springs.animate(this, R.id.sf_spring_scale_y, DynamicAnimation.SCALE_Y, target, spec, DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
}

/** Springs alpha to [target] (0..1). Default: [Spring.EffectsDefault] (no overshoot). */
fun View.springAlpha(target: Float, spec: Spring = Spring.EffectsDefault) {
    Springs.animate(this, R.id.sf_spring_alpha, DynamicAnimation.ALPHA, target.coerceIn(0f, 1f), spec, DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA)
}

/** Springs translationY to [target] px. Default: [Spring.SpatialDefault]. */
fun View.springTranslationY(target: Float, spec: Spring = Spring.SpatialDefault) {
    Springs.animate(this, R.id.sf_spring_translation_y, DynamicAnimation.TRANSLATION_Y, target, spec, DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS)
}

/** Springs translationZ (elevation lift) to [target] px. Default: [Spring.SpatialFast]. */
fun View.springTranslationZ(target: Float, spec: Spring = Spring.SpatialFast) {
    Springs.animate(this, R.id.sf_spring_translation_z, DynamicAnimation.TRANSLATION_Z, target, spec, DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS)
}
