package com.streamflixreborn.streamflix.fragments.queue

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.PathInterpolator
import com.google.android.material.color.MaterialColors
import kotlin.math.min
import kotlin.random.Random

/** The decision wheel: one slice per title, a pointer at the top, spun with a long ease-out. */
class SpinWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var labels: List<String> = emptyList()
    private var rotationDeg = 0f
    private var animator: ValueAnimator? = null
    private var lastTickIndex = -1

    private val density = resources.displayMetrics.density
    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointerPath = Path()
    private val oval = RectF()

    private fun color(attr: Int) = MaterialColors.getColor(this, attr)

    private val sliceColors by lazy {
        listOf(
            color(com.google.android.material.R.attr.colorPrimaryContainer) to color(com.google.android.material.R.attr.colorOnPrimaryContainer),
            color(com.google.android.material.R.attr.colorSecondaryContainer) to color(com.google.android.material.R.attr.colorOnSecondaryContainer),
            color(com.google.android.material.R.attr.colorTertiaryContainer) to color(com.google.android.material.R.attr.colorOnTertiaryContainer),
            color(com.google.android.material.R.attr.colorSurfaceContainerHighest) to color(com.google.android.material.R.attr.colorOnSurface),
        )
    }

    val isSpinning: Boolean get() = animator?.isRunning == true

    fun setLabels(values: List<String>) {
        labels = values
        invalidate()
    }

    /** Spins several turns and stops with slice [index] under the pointer. */
    fun spin(index: Int, onEnd: () -> Unit) {
        if (labels.isEmpty() || isSpinning) return
        val sweep = 360f / labels.size
        val jitter = (Random.nextFloat() - 0.5f) * sweep * 0.7f
        val landing = -((index + 0.5f) * sweep + jitter)
        val start = rotationDeg % 360f
        var end = landing
        while (end < start + 360f * 5) end += 360f
        animator = ValueAnimator.ofFloat(start, end).apply {
            duration = 4200
            interpolator = PathInterpolator(0.12f, 0.9f, 0.18f, 1f)
            addUpdateListener {
                rotationDeg = it.animatedValue as Float
                tickIfSliceChanged()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onEnd()
                }
            })
            start()
        }
    }

    private fun tickIfSliceChanged() {
        if (labels.isEmpty()) return
        val sweep = 360f / labels.size
        val underPointer = ((((-rotationDeg) % 360f) + 360f) % 360f / sweep).toInt()
        if (underPointer != lastTickIndex) {
            lastTickIndex = underPointer
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        if (labels.isEmpty()) return
        val pointerSize = 18f * density
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - pointerSize * 0.6f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val sweep = 360f / labels.size
        strokePaint.color = color(com.google.android.material.R.attr.colorSurface)

        canvas.save()
        canvas.rotate(rotationDeg, cx, cy)
        labels.forEachIndexed { i, label ->
            val (bg, fg) = sliceColors[i % sliceColors.size].let {
                // Avoid two equal neighbours when the last slice wraps onto the first.
                if (i == labels.lastIndex && i % sliceColors.size == 0 && labels.size > 1) sliceColors[1] else it
            }
            val startAngle = -90f + i * sweep
            slicePaint.color = bg
            canvas.drawArc(oval, startAngle, sweep, true, slicePaint)
            canvas.drawArc(oval, startAngle, sweep, true, strokePaint)

            canvas.save()
            val mid = startAngle + sweep / 2f
            canvas.rotate(mid, cx, cy)
            textPaint.color = fg
            val textStart = radius * 0.28f
            val available = radius * 0.66f
            val text = TextUtils.ellipsize(label, textPaint, available, TextUtils.TruncateAt.END).toString()
            // Labels on the left half would read upside down; turn them around.
            val screenAngle = ((mid + rotationDeg) % 360f + 360f) % 360f
            if (screenAngle in 90f..270f) {
                canvas.rotate(180f, cx + textStart + available / 2f, cy)
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(text, cx + textStart + available, cy + textPaint.textSize / 3f, textPaint)
            } else {
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(text, cx + textStart, cy + textPaint.textSize / 3f, textPaint)
            }
            canvas.restore()
        }
        canvas.restore()

        hubPaint.color = color(com.google.android.material.R.attr.colorSurface)
        canvas.drawCircle(cx, cy, radius * 0.18f, hubPaint)
        hubPaint.color = color(androidx.appcompat.R.attr.colorPrimary)
        canvas.drawCircle(cx, cy, radius * 0.09f, hubPaint)

        // Pointer, pointing down into the wheel at 12 o'clock.
        pointerPaint.color = color(androidx.appcompat.R.attr.colorPrimary)
        val top = cy - radius - pointerSize * 0.5f
        pointerPath.reset()
        pointerPath.moveTo(cx - pointerSize * 0.6f, top)
        pointerPath.lineTo(cx + pointerSize * 0.6f, top)
        pointerPath.lineTo(cx, top + pointerSize * 1.1f)
        pointerPath.close()
        canvas.drawPath(pointerPath, pointerPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
