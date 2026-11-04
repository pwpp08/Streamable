package com.streamflixreborn.streamflix.utils

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.google.android.material.color.DynamicColors
import com.streamflixreborn.streamflix.R
import com.google.android.material.R as M

/**
 * Maps the stored theme id ([UserPreferences.selectedTheme]) to a Material 3 Expressive theme and
 * applies it, including Material You ("wallpaper") colours and edge-to-edge system bars.
 *
 * Usage in an activity, before `super.onCreate`:
 * ```
 * ThemeManager.applyMobileTheme(this)   // or applyTvTheme(this)
 * super.onCreate(savedInstanceState)
 * ThemeManager.applySystemBars(this)
 * ```
 * Colours for code come from the theme ([palette] / [color]), never from hard-coded hex values.
 * Theme names, palettes and roles are documented in docs/DESIGN_SYSTEM.md.
 */
object ThemeManager {
    /** Material You colours from the wallpaper (Android 12+ phones only). */
    const val WALLPAPER = "wallpaper"
    const val CRIMSON = "crimson"
    const val OCEAN = "ocean"
    const val FOREST = "forest"
    /** Sage green: near-black green surfaces, sage primary. */
    const val SAGE = "sage"
    const val VIOLET = "violet"
    const val SUNSET = "sunset"
    /** Crimson accents on pure-black surfaces. */
    const val AMOLED = "amoled"

    /** Stored value when the user never picked a theme. Resolves to [defaultTheme]. */
    const val DEFAULT = "default"

    /** Ids in settings order; must match `@array/theme_values`. */
    val ALL = listOf(WALLPAPER, SAGE, CRIMSON, OCEAN, FOREST, VIOLET, SUNSET, AMOLED)

    /** Ids from the pre-M3 theme list, mapped to the closest new palette. */
    private val LEGACY = mapOf(
        "nero_amoled_oled" to AMOLED,
        "sunset_cinema" to SUNSET,
        "steel_blue" to OCEAN,
        "nord_frost" to OCEAN,
        "forest_night" to FOREST,
        "emerald_luxe" to FOREST,
        "crimson_noir" to CRIMSON,
        "midnight_violet" to VIOLET,
        "retro_neon" to VIOLET,
    )

    /** True when wallpaper colours can be offered (Android 12+ with dynamic colour support, not TV). */
    fun supportsWallpaperColors(isTv: Boolean): Boolean =
        !isTv && DynamicColors.isDynamicColorAvailable()

    /** Wallpaper colours where supported, otherwise Sage. */
    fun defaultTheme(isTv: Boolean): String =
        if (supportsWallpaperColors(isTv)) WALLPAPER else SAGE

    /**
     * Normalises any stored id (new, legacy, "default" or unknown) to one of [ALL] that is valid
     * on this device.
     */
    fun resolve(stored: String?, isTv: Boolean): String {
        val id = when (stored) {
            null, "", DEFAULT -> defaultTheme(isTv)
            in ALL -> stored
            else -> LEGACY[stored] ?: defaultTheme(isTv)
        }
        return if (id == WALLPAPER && !supportsWallpaperColors(isTv)) SAGE else id
    }

    /** The current theme id for this form factor. */
    fun current(isTv: Boolean): String = resolve(UserPreferences.selectedTheme, isTv)

    @StyleRes
    fun mobileThemeRes(id: String): Int = when (resolve(id, isTv = false)) {
        OCEAN -> R.style.Theme_StreamFlix_Ocean
        FOREST -> R.style.Theme_StreamFlix_Forest
        SAGE -> R.style.Theme_StreamFlix_Sage
        VIOLET -> R.style.Theme_StreamFlix_Violet
        SUNSET -> R.style.Theme_StreamFlix_Sunset
        AMOLED -> R.style.Theme_StreamFlix_Amoled
        // WALLPAPER uses Crimson as the base; the dynamic overlay replaces its colour roles.
        else -> R.style.Theme_StreamFlix_Crimson
    }

    @StyleRes
    fun tvThemeRes(id: String): Int = when (resolve(id, isTv = true)) {
        OCEAN -> R.style.Theme_StreamFlix_Tv_Ocean
        FOREST -> R.style.Theme_StreamFlix_Tv_Forest
        SAGE -> R.style.Theme_StreamFlix_Tv_Sage
        VIOLET -> R.style.Theme_StreamFlix_Tv_Violet
        SUNSET -> R.style.Theme_StreamFlix_Tv_Sunset
        AMOLED -> R.style.Theme_StreamFlix_Tv_Amoled
        else -> R.style.Theme_StreamFlix_Tv_Crimson
    }

    @StringRes
    fun titleRes(id: String): Int = when (id) {
        WALLPAPER -> R.string.theme_wallpaper
        OCEAN -> R.string.theme_ocean
        FOREST -> R.string.theme_forest
        SAGE -> R.string.theme_sage
        VIOLET -> R.string.theme_violet
        SUNSET -> R.string.theme_sunset
        AMOLED -> R.string.theme_amoled
        else -> R.string.theme_crimson
    }

    /** Sets the phone theme (call before `super.onCreate`). Applies wallpaper colours if selected. */
    fun applyMobileTheme(activity: Activity) {
        val id = current(isTv = false)
        activity.setTheme(mobileThemeRes(id))
        if (id == WALLPAPER) DynamicColors.applyToActivityIfAvailable(activity)
    }

    /** Sets the TV theme (call before `super.onCreate`). */
    fun applyTvTheme(activity: Activity) {
        activity.setTheme(tvThemeRes(current(isTv = true)))
    }

    /**
     * Edge-to-edge system bars driven by the theme: transparent bars (set in the theme) with icon
     * contrast picked from `colorSurface`. Content must handle insets itself.
     */
    fun applySystemBars(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val surface = color(activity, M.attr.colorSurface, Color.BLACK)
        val lightBars = ColorUtils.calculateLuminance(surface) > 0.5
        window.statusBarColor = Color.TRANSPARENT
        // Below API 26 light navigation-bar icons are unavailable, so keep a dark scrim there.
        window.navigationBarColor =
            if (lightBars && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) 0x66000000 else Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }

    /** Resolves a colour theme attribute of [context]'s theme. */
    @ColorInt
    fun color(context: Context, @AttrRes attr: Int, @ColorInt fallback: Int = Color.MAGENTA): Int {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return fallback
        return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }

    /**
     * Navigation-chrome colours resolved from the current theme, for code that still colours views
     * by hand. New code should use theme attributes in XML instead.
     */
    data class Palette(
        @ColorInt val mobileNavBackground: Int,
        @ColorInt val mobileNavActive: Int,
        @ColorInt val mobileNavInactive: Int,
        @ColorInt val systemBar: Int,
        @ColorInt val tvNavBackground: Int,
        @ColorInt val tvHeaderPrimary: Int,
        @ColorInt val tvHeaderSecondary: Int,
    )

    fun palette(context: Context): Palette {
        fun c(attr: Int) = color(context, attr)
        val surface = c(M.attr.colorSurface)
        val container = c(M.attr.colorSurfaceContainer)
        return Palette(
            mobileNavBackground = container,
            mobileNavActive = c(androidx.appcompat.R.attr.colorPrimary),
            mobileNavInactive = c(M.attr.colorOnSurfaceVariant),
            systemBar = surface,
            tvNavBackground = c(M.attr.colorSurfaceContainerLow),
            tvHeaderPrimary = c(M.attr.colorOnSurface),
            tvHeaderSecondary = c(M.attr.colorOnSurfaceVariant),
        )
    }
}
