package com.streamflixreborn.streamflix.fragments.queue

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.withSave
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The spin wheel: 2:3 poster cards fanned along an arc, overlapping, with the front card (the
 * pick) in the middle and its neighbours tucked behind it, smaller and dimmer. The titles repeat
 * so the wheel can turn forever. Drag or flick to spin (D-pad left/right steps, centre spins);
 * [onSelected] reports the title in front as it changes, [onSettled] when it stops.
 */
class PosterWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onSelected: ((Int) -> Unit)? = null
    var onSettled: ((Int) -> Unit)? = null

    private var titles: List<String> = emptyList()
    private val posters = mutableMapOf<Int, Bitmap>()
    private val targets = mutableListOf<CustomTarget<Bitmap>>()

    /** Slots on the wheel (the titles repeated); [offset] is the slot in front, fractional while moving. */
    private var slotCount = 1
    private var offset = 0f
    private var selectedSlot = 0
    private var animator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }
    private val rect = RectF()
    private val clip = Path()

    init {
        isFocusable = true
        isClickable = true
    }

    private fun color(attr: Int) = MaterialColors.getColor(this, attr)

    val isSpinning: Boolean get() = animator?.isRunning == true

    val selectedIndex: Int get() = if (titles.isEmpty()) 0 else selectedSlot % titles.size

    // Card geometry: the front card's size, the spacing between neighbours and the arc they sit on.
    private val cardW get() = (width * 0.40f).coerceAtMost(height * 0.80f / 1.5f)
    private val cardH get() = cardW * 1.5f
    private val step get() = cardW * 0.56f
    private val stepAngle = 11f

    fun setItems(titles: List<String>, posterUrls: List<String?>) {
        animator?.cancel()
        targets.forEach { Glide.with(this).clear(it) }
        targets.clear()
        posters.clear()
        this.titles = titles
        // Repeat the titles so there are always cards on both sides of the front one.
        slotCount = if (titles.isEmpty()) 1 else titles.size * ceil(MIN_SLOTS / titles.size.toFloat()).toInt()
        offset = 0f
        selectedSlot = 0
        posterUrls.forEachIndexed { index, url ->
            if (url.isNullOrBlank()) return@forEachIndexed
            val target = object : CustomTarget<Bitmap>(342, 513) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    posters[index] = resource
                    invalidate()
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    posters.remove(index)
                }
            }
            targets += target
            Glide.with(this).asBitmap().load(url).centerCrop().into(target)
        }
        invalidate()
    }

    /** Spins a few rounds and stops on a random card; [direction] follows a flick. */
    fun spin(direction: Int = 1) {
        if (titles.isEmpty() || isSpinning) return
        val distance = slotCount * (2 + Random.nextInt(2)) + Random.nextInt(slotCount)
        val end = offset.roundToInt() + direction * distance
        animateTo(end.toFloat(), 4200L, PathInterpolator(0.12f, 0.8f, 0.2f, 1f))
    }

    private fun animateTo(end: Float, duration: Long, interpolator: TimeInterpolator) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(offset, end).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                offset = it.animatedValue as Float
                updateSelection()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    invalidate()
                    if (!cancelled) {
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        onSettled?.invoke(selectedIndex)
                    }
                }
            })
            start()
        }
    }

    private fun snap() = animateTo(offset.roundToInt().toFloat(), 240L, DecelerateInterpolator())

    private fun stepBy(slots: Int) {
        if (isSpinning) return
        animateTo((offset.roundToInt() + slots).toFloat(), 220L, DecelerateInterpolator())
    }

    private fun updateSelection() {
        if (titles.isEmpty()) return
        val slot = Math.floorMod(offset.roundToInt(), slotCount)
        if (slot != selectedSlot) {
            selectedSlot = slot
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onSelected?.invoke(selectedIndex)
        }
    }

    /** Signed distance (in slots) of [slot] from the front, wrapped to the nearer side. */
    private fun distance(slot: Int): Float {
        var d = (slot - offset) % slotCount
        if (d > slotCount / 2f) d -= slotCount
        if (d < -slotCount / 2f) d += slotCount
        return d
    }

    override fun onDraw(canvas: Canvas) {
        if (titles.isEmpty() || width == 0) return
        val cx = width / 2f
        val cy = height * 0.48f
        val radius = step / sin(Math.toRadians(stepAngle.toDouble())).toFloat()
        val corner = 18f * density
        val surface = color(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val outline = color(com.google.android.material.R.attr.colorOutline)
        val onSurface = color(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val shadow = android.graphics.Color.BLACK

        // Back to front: the farthest cards first, the front card last.
        val visible = (0 until slotCount)
            .map { it to distance(it) }
            .filter { abs(it.second) <= VISIBLE_SIDE }
            .sortedByDescending { abs(it.second) }

        for ((slot, d) in visible) {
            val angle = d * stepAngle
            val a = Math.toRadians(angle.toDouble())
            val x = cx + radius * sin(a).toFloat()
            val y = cy + radius * (1 - cos(a).toFloat())
            val scale = (1f - 0.11f * abs(d)).coerceAtLeast(0.55f)
            val w = cardW * scale
            val h = cardH * scale
            val index = slot % titles.size
            canvas.withSave {
                translate(x, y)
                rotate(angle)
                // Soft drop shadow.
                paint.color = shadow
                paint.alpha = 90
                rect.set(-w / 2, -h / 2 + 6 * density, w / 2, h / 2 + 10 * density)
                drawRoundRect(rect, corner, corner, paint)
                paint.alpha = 255

                rect.set(-w / 2, -h / 2, w / 2, h / 2)
                clip.reset()
                clip.addRoundRect(rect, corner * scale, corner * scale, Path.Direction.CW)
                withSave {
                    clipPath(clip)
                    paint.color = surface
                    drawRect(rect, paint)
                    val bitmap = posters[index]
                    if (bitmap != null) {
                        drawBitmap(bitmap, null, rect, null)
                    } else {
                        textPaint.color = onSurface
                        val label = TextUtils.ellipsize(titles[index], textPaint, w - 16 * density, TextUtils.TruncateAt.END)
                        drawText(label.toString(), 0f, textPaint.textSize / 3f, textPaint)
                    }
                    // Cards further back fade into the background.
                    val dim = (abs(d) * 0.22f).coerceAtMost(0.7f)
                    if (dim > 0.01f) {
                        paint.color = shadow
                        paint.alpha = (dim * 255).toInt()
                        drawRect(rect, paint)
                        paint.alpha = 255
                    }
                }
                if (abs(d) < 0.5f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = (if (isFocused) 3f else 1.5f) * density
                    paint.color = if (isFocused) color(androidx.appcompat.R.attr.colorPrimary) else outline
                    drawRoundRect(rect, corner * scale, corner * scale, paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }
    }

    // ---- Touch: drag turns the wheel, a flick or a tap spins it ----------------------------

    private var velocity: VelocityTracker? = null
    private var lastX = 0f
    private var downX = 0f
    private var dragging = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (titles.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isSpinning) animator?.cancel()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                lastX = event.x
                downX = event.x
                dragging = false
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val dx = event.x - lastX
                lastX = event.x
                if (abs(event.x - downX) > 8 * density) dragging = true
                if (dragging) {
                    offset -= dx / step
                    updateSelection()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                val tracker = velocity
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                tracker?.recycle()
                velocity = null
                when {
                    event.actionMasked == MotionEvent.ACTION_CANCEL -> snap()
                    abs(vx) > 450 * density -> spin(if (vx > 0) -1 else 1)
                    dragging -> snap()
                    else -> { performClick(); spin() }
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { stepBy(-1); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { stepBy(1); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { spin(); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        targets.forEach { runCatching { Glide.with(this).clear(it) } }
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MIN_SLOTS = 11
        const val VISIBLE_SIDE = 4.2f
    }
}
