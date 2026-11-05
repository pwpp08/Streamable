package com.streamflixreborn.streamflix.fragments.queue

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil

/** A row of pills, one per episode of the season (grouped when there are many); watched ones filled. */
class SegmentedProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var segments: List<Boolean> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    fun setWatched(watched: List<Boolean>) {
        segments = if (watched.size <= MAX_SEGMENTS) {
            watched
        } else {
            // Group so a 22-episode season still fits: a group is filled once all of it is watched.
            val size = ceil(watched.size / MAX_SEGMENTS.toFloat()).toInt()
            watched.chunked(size).map { group -> group.all { it } }
        }
        contentDescription = "${watched.count { it }} / ${watched.size}"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (8 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (segments.isEmpty()) return
        val gap = 4f * density
        val w = (width - gap * (segments.size - 1)) / segments.size
        val filled = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val empty = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer)
        segments.forEachIndexed { i, watched ->
            val left = i * (w + gap)
            rect.set(left, 0f, left + w, height.toFloat())
            paint.color = if (watched) filled else empty
            canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
        }
    }

    private companion object {
        const val MAX_SEGMENTS = 12
    }
}
