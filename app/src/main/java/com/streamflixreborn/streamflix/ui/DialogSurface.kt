package com.streamflixreborn.streamflix.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.google.android.material.R as M

/**
 * Window background for custom (non-MaterialAlertDialog) dialogs: the M3 dialog surface, a
 * 28dp rounded `surfaceContainerHigh` shape inset from the screen edges.
 */
object DialogSurface {
    fun create(context: Context, insetDp: Int = 24): Drawable {
        val density = context.resources.displayMetrics.density
        val shape = MaterialShapeDrawable(
            ShapeAppearanceModel.builder().setAllCornerSizes(28f * density).build()
        ).apply {
            fillColor = ColorStateList.valueOf(ThemeManager.color(context, M.attr.colorSurfaceContainerHigh))
        }
        return InsetDrawable(shape, (insetDp * density).toInt())
    }
}
