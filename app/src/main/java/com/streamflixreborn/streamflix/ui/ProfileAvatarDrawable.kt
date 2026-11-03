package com.streamflixreborn.streamflix.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapes
import com.streamflixreborn.streamflix.models.Profile
import com.streamflixreborn.streamflix.utils.ThemeManager
import kotlin.math.abs

/**
 * Expressive profile avatar: one of the M3 Expressive [MaterialShapes] polygons (cookie, clover,
 * burst, ...) filled with a tonal container colour derived from the profile's colour.
 *
 * With [focusRing] the shape is drawn slightly inset, and a 3dp `colorPrimary` ring that follows
 * the polygon appears while the drawable is focused (TV, D-pad). The host view must forward its
 * state, for example with `android:duplicateParentState="true"`.
 */
class ProfileAvatarDrawable(
    polygon: RoundedPolygon,
    @ColorInt fillColor: Int,
    @ColorInt private val ringColor: Int = 0,
    private val ringWidthPx: Float = 0f,
    private val focusRing: Boolean = false,
) : Drawable() {

    private val unitPath: Path = pathFor(polygon)
    private val fillPath = Path()
    private val ringPath = Path()
    private val matrix = Matrix()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidthPx
        color = ringColor
    }
    private var focused = false

    override fun onBoundsChange(bounds: Rect) {
        val ringInset = if (focusRing) ringWidthPx * 2.2f else 0f
        transform(bounds, ringInset, fillPath)
        if (focusRing) transform(bounds, ringWidthPx / 2f, ringPath)
    }

    private fun transform(bounds: Rect, inset: Float, out: Path) {
        val w = bounds.width() - inset * 2
        val h = bounds.height() - inset * 2
        matrix.reset()
        matrix.setScale(w, h)
        matrix.postTranslate(bounds.left + inset, bounds.top + inset)
        unitPath.transform(matrix, out)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(fillPath, fillPaint)
        if (focusRing && focused) canvas.drawPath(ringPath, ringPaint)
    }

    override fun isStateful(): Boolean = focusRing

    override fun onStateChange(state: IntArray): Boolean {
        val now = state.contains(android.R.attr.state_focused)
        if (now == focused) return false
        focused = now
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        ringPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** Tonal colours of an avatar: the shape fill and the initial drawn on it. */
    data class Colors(@ColorInt val container: Int, @ColorInt val onContainer: Int)

    companion object {
        /** Expressive shapes handed out to profiles, chosen so neighbours look clearly different. */
        @SuppressLint("RestrictedApi")
        private val SHAPES: List<RoundedPolygon> = listOf(
            MaterialShapes.COOKIE_9,
            MaterialShapes.CLOVER_4,
            MaterialShapes.SOFT_BURST,
            MaterialShapes.PENTAGON,
            MaterialShapes.COOKIE_6,
            MaterialShapes.SUNNY,
            MaterialShapes.CLOVER_8,
            MaterialShapes.PUFFY_DIAMOND,
            MaterialShapes.GEM,
            MaterialShapes.FLOWER,
            MaterialShapes.COOKIE_12,
            MaterialShapes.ARCH,
        )

        private val paths = HashMap<RoundedPolygon, Path>()

        private fun pathFor(polygon: RoundedPolygon): Path =
            paths.getOrPut(polygon) { polygon.normalized().toPath() }

        /** The same profile always gets the same shape (stable across runs and devices). */
        fun shapeFor(profileId: String): RoundedPolygon = SHAPES[abs(profileId.hashCode() % SHAPES.size)]

        /** The profile colour, harmonised with the palette, as an M3 tonal container pair. */
        fun colorsFor(context: Context, @ColorInt profileColor: Int): Colors {
            val harmonized = MaterialColors.harmonizeWithPrimary(context, profileColor)
            val roles = MaterialColors.getColorRoles(context, harmonized)
            return Colors(roles.accentContainer, roles.onAccentContainer)
        }

        fun create(context: Context, profile: Profile, focusRing: Boolean = false): ProfileAvatarDrawable {
            val density = context.resources.displayMetrics.density
            return ProfileAvatarDrawable(
                polygon = shapeFor(profile.id),
                fillColor = colorsFor(context, profile.avatarColor).container,
                ringColor = ThemeManager.color(context, androidx.appcompat.R.attr.colorPrimary),
                ringWidthPx = 3f * density,
                focusRing = focusRing,
            )
        }
    }
}
