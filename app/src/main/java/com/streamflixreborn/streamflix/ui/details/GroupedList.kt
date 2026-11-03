package com.streamflixreborn.streamflix.ui.details

import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import com.streamflixreborn.streamflix.R

/**
 * M3 Expressive grouped (segmented) list rows: the first and last visible rows of a group get
 * the large outer corners, the rows in between small inner ones (`ud_list_row_*`).
 */
object GroupedList {

    fun backgroundFor(position: Int, count: Int): Int = when {
        count <= 1 -> R.drawable.ud_list_row_single
        position == 0 -> R.drawable.ud_list_row_top
        position == count - 1 -> R.drawable.ud_list_row_bottom
        else -> R.drawable.ud_list_row_middle
    }

    /** Applies the row shapes to the visible children of [container], in order. */
    fun apply(container: ViewGroup) {
        val visible = container.children.filter { it.visibility == View.VISIBLE }.toList()
        visible.forEachIndexed { index, row ->
            setBackgroundKeepingPadding(row, backgroundFor(index, visible.size))
        }
    }

    /** One row of a group: [first] / [last] say whether it opens / closes its group. */
    fun applyRow(row: View, first: Boolean, last: Boolean) {
        val res = when {
            first && last -> R.drawable.ud_list_row_single
            first -> R.drawable.ud_list_row_top
            last -> R.drawable.ud_list_row_bottom
            else -> R.drawable.ud_list_row_middle
        }
        if (row.getTag(R.id.ud_list_row_background) == res) return
        row.setTag(R.id.ud_list_row_background, res)
        setBackgroundKeepingPadding(row, res)
    }

    private fun setBackgroundKeepingPadding(row: View, res: Int) {
        val start = row.paddingStart
        val top = row.paddingTop
        val end = row.paddingEnd
        val bottom = row.paddingBottom
        row.setBackgroundResource(res)
        row.setPaddingRelative(start, top, end, bottom)
    }
}
