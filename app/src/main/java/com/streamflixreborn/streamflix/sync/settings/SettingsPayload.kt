package com.streamflixreborn.streamflix.sync.settings

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What travels between devices: a flat map of setting key to text value. Only settings that
 * make sense on every device are in it (never keys, passwords or device-specific tuning).
 */
data class SettingsPayload(val values: Map<String, String>) {

    /** Stable fingerprint, used to notice local changes since the last sync. */
    fun fingerprint(): String = values.toSortedMap().entries.joinToString("\u0001") { "${it.key}\u0002${it.value}" }

    /** Entries of [remote] that differ from this payload. */
    fun changesFrom(remote: Map<String, String>): Map<String, String> =
        remote.filter { (key, value) -> values[key] != value }
}

/** Picks the named palette closest to a colour; used to carry a phone's wallpaper colours to a TV. */
object ThemeSync {

    /** Hue (degrees) of each palette's primary colour. */
    private val PALETTE_HUES = listOf(
        "sage" to 92f,
        "forest" to 140f,
        "ocean" to 205f,
        "violet" to 270f,
        "crimson" to 350f,
        "sunset" to 25f,
    )

    /** Below this saturation a colour counts as grey and maps to the default palette. */
    private const val MIN_SATURATION = 0.12f

    fun nearestPalette(argb: Int): String {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val saturation = if (maxC == 0f) 0f else delta / maxC
        if (saturation < MIN_SATURATION) return DEFAULT_PALETTE
        val hue = when {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return PALETTE_HUES.minBy { (_, paletteHue) -> hueDistance(hue, paletteHue) }.first
    }

    const val DEFAULT_PALETTE = "sage"

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }
}
