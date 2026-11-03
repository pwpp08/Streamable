package com.streamflixreborn.streamflix.fragments.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.ui.motion.Spring
import com.streamflixreborn.streamflix.ui.motion.springScale
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.google.android.material.R as M

/**
 * Android 16-style grouped settings list.
 *
 * Consecutive rows between two [PreferenceCategory] headers form a group. Each row gets a tonal
 * `surfaceContainer` background whose corners depend on its place in the group: 20dp (TV 24dp)
 * on the outer edges of the first and last row, 4dp between rows, with a 2dp gap. Category
 * headers and untitled categories (group gaps) get no background.
 *
 * The group position is computed from the adapter on every layout pass, so rows that appear,
 * disappear or are recycled always get the right shape. On TV the background also carries the
 * focus state (`surfaceContainerHighest` plus a 3dp `colorPrimary` ring) and the row springs to
 * a slight scale when focused.
 */
internal class SettingsGroupDecoration(context: Context, private val isTv: Boolean) : RecyclerView.ItemDecoration() {

    enum class Slot { SINGLE, FIRST, MIDDLE, LAST }

    private class Applied(val slot: Slot, val drawable: Drawable)

    private val density = context.resources.displayMetrics.density
    private val gap = px(2)
    private val sideInset = px(if (isTv) 48 else 16)
    private val maxRowWidth = px(820)
    private val outerRadius = px(if (isTv) 24 else 20).toFloat()
    private val innerRadius = px(4).toFloat()
    private val ringWidth = px(3).toFloat()

    private var observedAdapter: RecyclerView.Adapter<*>? = null
    private var observedList: RecyclerView? = null
    private val observer = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = invalidate()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = invalidate()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = invalidate()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = invalidate()
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        watch(parent)
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        val holder = parent.getChildViewHolder(view) ?: return
        val position = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
            ?: holder.layoutPosition
        val preference = adapter.itemAt(position) ?: return

        val (start, end) = sideInsets(parent)
        val rtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
        outRect.left = if (rtl) end else start
        outRect.right = if (rtl) start else end
        if (preference is PreferenceCategory) return

        val slot = slotOf(adapter, position)
        outRect.top = if (slot == Slot.MIDDLE || slot == Slot.LAST) gap else 0
        applyBackground(view, slot)
    }

    /** Rows sit in a column: 16dp gutters on phones; on TV a left-aligned column of at most 820dp. */
    private fun sideInsets(parent: RecyclerView): Pair<Int, Int> {
        if (!isTv) return sideInset to sideInset
        val width = parent.width - parent.paddingLeft - parent.paddingRight
        val end = if (width > 0) maxOf(sideInset, width - sideInset - maxRowWidth) else sideInset
        return sideInset to end
    }

    private fun slotOf(adapter: PreferenceGroupAdapter, position: Int): Slot {
        val previousIsRow = isRow(adapter.itemAt(position - 1))
        val nextIsRow = isRow(adapter.itemAt(position + 1))
        return when {
            previousIsRow && nextIsRow -> Slot.MIDDLE
            previousIsRow -> Slot.LAST
            nextIsRow -> Slot.FIRST
            else -> Slot.SINGLE
        }
    }

    private fun isRow(preference: Preference?) = preference != null && preference !is PreferenceCategory

    private fun applyBackground(view: View, slot: Slot) {
        val applied = view.getTag(R.id.settings_row_defaults) as? Applied
        // PreferenceViewHolder resets the background on every bind, so compare by identity too.
        if (applied != null && applied.slot == slot && view.background === applied.drawable) return
        val drawable = applied?.takeIf { it.slot == slot }?.drawable ?: createBackground(view.context, slot)
        view.background = drawable
        view.setTag(R.id.settings_row_defaults, Applied(slot, drawable))
        if (isTv) attachFocusSpring(view)
    }

    private fun createBackground(context: Context, slot: Slot): Drawable {
        val top = if (slot == Slot.FIRST || slot == Slot.SINGLE) outerRadius else innerRadius
        val bottom = if (slot == Slot.LAST || slot == Slot.SINGLE) outerRadius else innerRadius
        val shape = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(top)
            .setTopRightCornerSize(top)
            .setBottomLeftCornerSize(bottom)
            .setBottomRightCornerSize(bottom)
            .build()

        fun fill(attr: Int) = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(ThemeManager.color(context, attr))
        }

        if (!isTv) {
            val ripple = ThemeManager.color(context, androidx.appcompat.R.attr.colorControlHighlight)
            return RippleDrawable(ColorStateList.valueOf(ripple), fill(M.attr.colorSurfaceContainer), null)
        }

        val focused = fill(M.attr.colorSurfaceContainerHighest).apply {
            setStroke(ringWidth, ThemeManager.color(context, androidx.appcompat.R.attr.colorPrimary))
        }
        return StateListDrawable().apply {
            setEnterFadeDuration(90)
            setExitFadeDuration(90)
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), fill(M.attr.colorSurfaceContainer))
        }
    }

    private fun attachFocusSpring(view: View) {
        if (view.getTag(R.id.settings_list_styler_tag) == true) return
        view.setTag(R.id.settings_list_styler_tag, true)
        val previous = view.onFocusChangeListener
        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            v.springScale(if (hasFocus) FOCUS_SCALE else 1f, Spring.SpatialFast)
            previous?.onFocusChange(v, hasFocus)
        }
    }

    /** Neighbours change shape when rows are added or hidden, so recompute every row's offsets. */
    private fun watch(parent: RecyclerView) {
        val adapter = parent.adapter
        if (adapter === observedAdapter) return
        observedAdapter?.let { runCatching { it.unregisterAdapterDataObserver(observer) } }
        observedAdapter = adapter
        observedList = parent
        adapter?.registerAdapterDataObserver(observer)
    }

    private fun invalidate() {
        val list = observedList ?: return
        list.post { if (!list.isComputingLayout) list.invalidateItemDecorations() }
    }

    @SuppressLint("RestrictedApi")
    private fun PreferenceGroupAdapter.itemAt(position: Int): Preference? =
        if (position in 0 until itemCount) getItem(position) else null

    private fun px(dp: Int) = (dp * density + 0.5f).toInt()

    private companion object {
        const val FOCUS_SCALE = 1.02f
    }
}
