package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleDimStateTest {

    private val defaults = IdleDimSettings()

    @Test
    fun defaultsAre20sAnd15Percent() {
        assertTrue(defaults.enabled)
        assertEquals(20_000L, defaults.delayMs)
        assertEquals(0.15f, defaults.windowBrightness, 0.0001f)
        assertEquals(0.85f, defaults.overlayAlpha, 0.0001f)
    }

    @Test
    fun dimsAfterDefaultDelayWhenIdle() {
        val s = IdleDimState(0)
        assertFalse(s.isDimmed(19_999, defaults))
        assertTrue(s.isDimmed(20_000, defaults))
    }

    @Test
    fun interactionWakesAndRestartsTimer() {
        val s = IdleDimState(0)
        assertTrue(s.isDimmed(25_000, defaults))
        s.onInteraction(25_000)
        assertFalse(s.isDimmed(25_001, defaults))
        assertTrue(s.isDimmed(45_000, defaults))
    }

    @Test
    fun neverDimsWhilePlaying() {
        val s = IdleDimState(0)
        s.setPlaying(1_000, true)
        assertFalse(s.isDimmed(10 * 60_000L, defaults))
        assertNull(s.msUntilDim(10 * 60_000L, defaults))
    }

    @Test
    fun pauseCountsFromThePauseMoment() {
        val s = IdleDimState(0)
        s.setPlaying(0, true)
        s.setPlaying(100_000, false)
        assertFalse(s.isDimmed(119_999, defaults))
        assertTrue(s.isDimmed(120_000, defaults))
        assertEquals(5_000L, s.msUntilDim(115_000, defaults))
    }

    @Test
    fun resumeWakes() {
        val s = IdleDimState(0)
        assertTrue(s.isDimmed(30_000, defaults))
        s.setPlaying(30_000, true)
        assertFalse(s.isDimmed(30_000, defaults))
    }

    @Test
    fun disabledNeverDims() {
        val s = IdleDimState(0)
        val off = defaults.copy(enabled = false)
        assertFalse(s.isDimmed(1_000_000, off))
        assertNull(s.msUntilDim(0, off))
    }

    @Test
    fun delayChangeTakesEffect() {
        val s = IdleDimState(0)
        assertFalse(s.isDimmed(30_000, defaults.copy(delaySeconds = 60)))
        assertTrue(s.isDimmed(30_000, defaults.copy(delaySeconds = 10)))
    }

    @Test
    fun levelIsClamped() {
        assertEquals(0.05f, IdleDimSettings(levelPercent = 1).windowBrightness, 0.0001f)
        assertEquals(0.60f, IdleDimSettings(levelPercent = 99).windowBrightness, 0.0001f)
        assertEquals(0.40f, IdleDimSettings(levelPercent = 60).overlayAlpha, 0.0001f)
    }
}
