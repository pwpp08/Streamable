package com.streamflixreborn.streamflix.nextui

import android.content.Context
import android.util.TypedValue
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** True when the host is the Android TV layout: D-pad focus rings and key handling are enabled. */
val LocalIsTv = staticCompositionLocalOf { false }

/**
 * Dark Material 3 scheme built from the colour roles of the host app's (Material Components)
 * theme, so the player follows the palette the user picked.
 */
@Composable
fun NextUiTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = remember(context) { context.hostColorScheme() }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

private fun Context.hostColorScheme(): ColorScheme {
    val base = darkColorScheme()
    fun role(name: String, fallback: Color) = themeColor(name, fallback)
    return darkColorScheme(
        primary = role("colorPrimary", base.primary),
        onPrimary = role("colorOnPrimary", base.onPrimary),
        primaryContainer = role("colorPrimaryContainer", base.primaryContainer),
        onPrimaryContainer = role("colorOnPrimaryContainer", base.onPrimaryContainer),
        secondary = role("colorSecondary", base.secondary),
        onSecondary = role("colorOnSecondary", base.onSecondary),
        secondaryContainer = role("colorSecondaryContainer", base.secondaryContainer),
        onSecondaryContainer = role("colorOnSecondaryContainer", base.onSecondaryContainer),
        tertiary = role("colorTertiary", base.tertiary),
        onTertiary = role("colorOnTertiary", base.onTertiary),
        error = role("colorError", base.error),
        onError = role("colorOnError", base.onError),
        background = role("colorSurface", base.background),
        onBackground = role("colorOnSurface", base.onBackground),
        surface = role("colorSurface", base.surface),
        onSurface = role("colorOnSurface", base.onSurface),
        surfaceVariant = role("colorSurfaceVariant", base.surfaceVariant),
        onSurfaceVariant = role("colorOnSurfaceVariant", base.onSurfaceVariant),
        outline = role("colorOutline", base.outline),
        outlineVariant = role("colorOutlineVariant", base.outlineVariant),
        surfaceContainerLowest = role("colorSurfaceContainerLowest", base.surfaceContainerLowest),
        surfaceContainerLow = role("colorSurfaceContainerLow", base.surfaceContainerLow),
        surfaceContainer = role("colorSurfaceContainer", base.surfaceContainer),
        surfaceContainerHigh = role("colorSurfaceContainerHigh", base.surfaceContainerHigh),
        surfaceContainerHighest = role("colorSurfaceContainerHighest", base.surfaceContainerHighest),
    )
}

private fun Context.themeColor(attrName: String, fallback: Color): Color {
    val attr = resources.getIdentifier(attrName, "attr", packageName)
    if (attr == 0) return fallback
    val value = TypedValue()
    if (!theme.resolveAttribute(attr, value, true)) return fallback
    return when {
        value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> Color(value.data)
        value.resourceId != 0 -> runCatching { Color(ContextCompat.getColor(this, value.resourceId)) }
            .getOrDefault(fallback)
        else -> fallback
    }
}
