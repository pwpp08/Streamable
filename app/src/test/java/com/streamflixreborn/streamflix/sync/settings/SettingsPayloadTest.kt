package com.streamflixreborn.streamflix.sync.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SettingsPayloadTest {

    @Test
    fun greenWallpaperBecomesSage() {
        // The sage primary of a green Material You palette.
        assertEquals("sage", ThemeSync.nearestPalette(0xFFB7D09B.toInt()))
    }

    @Test
    fun otherHuesMapToTheirPalette() {
        assertEquals("ocean", ThemeSync.nearestPalette(0xFF4A90D9.toInt()))
        assertEquals("violet", ThemeSync.nearestPalette(0xFF9575CD.toInt()))
        assertEquals("crimson", ThemeSync.nearestPalette(0xFFE53935.toInt()))
        assertEquals("sunset", ThemeSync.nearestPalette(0xFFFF8A3D.toInt()))
    }

    @Test
    fun greyFallsBackToDefaultPalette() {
        assertEquals(ThemeSync.DEFAULT_PALETTE, ThemeSync.nearestPalette(0xFF808080.toInt()))
    }

    @Test
    fun fingerprintIgnoresOrderButNotValues() {
        val a = SettingsPayload(mapOf("x" to "1", "y" to "2"))
        val b = SettingsPayload(mapOf("y" to "2", "x" to "1"))
        val c = SettingsPayload(mapOf("x" to "1", "y" to "3"))
        assertEquals(a.fingerprint(), b.fingerprint())
        assertNotEquals(a.fingerprint(), c.fingerprint())
    }

    @Test
    fun changesFromListsOnlyDifferences() {
        val local = SettingsPayload(mapOf("theme" to "sage", "autoplay" to "true"))
        val changes = local.changesFrom(mapOf("theme" to "ocean", "autoplay" to "true", "new_key" to "v"))
        assertEquals(mapOf("theme" to "ocean", "new_key" to "v"), changes)
    }
}
