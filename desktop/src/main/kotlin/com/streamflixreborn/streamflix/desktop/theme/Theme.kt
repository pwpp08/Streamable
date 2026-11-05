package com.streamflixreborn.streamflix.desktop.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The Android app's Crimson palette (res/values/colors_m3.xml, dark), seed #E50914.
private val Crimson = darkColorScheme(
    primary = Color(0xFFFFB4AA),
    onPrimary = Color(0xFF690003),
    primaryContainer = Color(0xFFE50914),
    onPrimaryContainer = Color(0xFFFFF7F6),
    inversePrimary = Color(0xFFC0000C),
    secondary = Color(0xFFE7BDB7),
    onSecondary = Color(0xFF442926),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFD4ACA6),
    tertiary = Color(0xFFA7C8FF),
    onTertiary = Color(0xFF003061),
    tertiaryContainer = Color(0xFF0072D7),
    onTertiaryContainer = Color(0xFFF8F9FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF181211),
    onBackground = Color(0xFFEDE0DE),
    surface = Color(0xFF181211),
    onSurface = Color(0xFFEDE0DE),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BE),
    surfaceDim = Color(0xFF181211),
    surfaceBright = Color(0xFF3F3736),
    surfaceContainerLowest = Color(0xFF120D0C),
    surfaceContainerLow = Color(0xFF201A19),
    surfaceContainer = Color(0xFF251E1D),
    surfaceContainerHigh = Color(0xFF2F2827),
    surfaceContainerHighest = Color(0xFF3B3332),
    inverseSurface = Color(0xFFEDE0DE),
    inverseOnSurface = Color(0xFF362F2E),
    outline = Color(0xFFA08C8A),
    outlineVariant = Color(0xFF534341),
)

/** Amoled: the same accents on true black, as in the app's Amoled palette. */
private val Amoled = Crimson.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0E0E0E),
    surfaceContainer = Color(0xFF131313),
    surfaceContainerHigh = Color(0xFF1B1B1B),
    surfaceContainerHighest = Color(0xFF242424),
    surfaceBright = Color(0xFF333333),
)

// Google Sans Flex, the app's typeface (res/font/google_sans_flex.ttf).
private val Sans = FontFamily(
    Font("fonts/google_sans_flex.ttf", FontWeight.Normal),
    Font("fonts/google_sans_flex.ttf", FontWeight.Medium),
    Font("fonts/google_sans_flex.ttf", FontWeight.SemiBold),
    Font("fonts/google_sans_flex.ttf", FontWeight.Bold),
)

private fun TextStyle.sans() = copy(fontFamily = Sans)

private val base = Typography()

private val AppTypography = Typography(
    displayLarge = base.displayLarge.sans(),
    displayMedium = base.displayMedium.sans(),
    displaySmall = base.displaySmall.sans().copy(fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.sans().copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.sans().copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.sans().copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.sans().copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.sans().copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.sans(),
    bodyLarge = base.bodyLarge.sans(),
    bodyMedium = base.bodyMedium.sans(),
    bodySmall = base.bodySmall.sans(),
    labelLarge = base.labelLarge.sans().copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.sans(),
    labelSmall = base.labelSmall.sans().copy(fontSize = 11.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun StreamFlixTheme(amoled: Boolean = false, content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (amoled) Amoled else Crimson,
        typography = AppTypography,
        shapes = AppShapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
