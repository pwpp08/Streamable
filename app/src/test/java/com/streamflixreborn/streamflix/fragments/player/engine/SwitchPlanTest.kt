package com.streamflixreborn.streamflix.fragments.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchPlanTest {

    private val duration = 7_200_000L

    @Test
    fun cutsOverAtTheStandbysOwnStartWhenThePictureHasNotGotThereYet() {
        // Picture at 100 s, standby prepared at 104 s with 9 s buffered.
        assertEquals(104_000L, SwitchPlan.swapPoint(100_000, 104_000, 113_000, duration))
    }

    @Test
    fun waitsWhileTheStandbyHasNotBufferedFarEnough() {
        assertNull(SwitchPlan.swapPoint(100_000, 104_000, 105_000, duration))
        assertNull(SwitchPlan.swapPoint(100_000, 104_000, 106_000, duration))
        assertEquals(104_000L, SwitchPlan.swapPoint(100_000, 104_000, 106_600, duration))
    }

    @Test
    fun picksAPointJustAheadOfThePictureWhenTheStandbyWasSlow() {
        // The picture is already at 108 s; the standby started at 104 s and has buffered to 120 s.
        assertEquals(108_000L + SwitchPlan.SYNC_LEAD_MS, SwitchPlan.swapPoint(108_000, 104_000, 120_000, duration))
    }

    @Test
    fun aLaterAttemptLeavesMoreRoom() {
        val first = SwitchPlan.swapPoint(108_000, 104_000, 130_000, duration, attempt = 0)!!
        val second = SwitchPlan.swapPoint(108_000, 104_000, 130_000, duration, attempt = 1)!!
        assertTrue(second > first)
    }

    @Test
    fun theStandbyMustHaveBufferedBeyondTheChosenPoint() {
        // A point at 109.2 s needs buffer up to 111.7 s.
        assertNull(SwitchPlan.swapPoint(108_000, 104_000, 111_000, duration))
        assertEquals(109_200L, SwitchPlan.swapPoint(108_000, 104_000, 112_000, duration))
    }

    @Test
    fun nearTheEndOfTheFilmTheDurationLimitsWhatIsNeeded() {
        assertEquals(duration - 9_800, SwitchPlan.swapPoint(duration - 11_000, duration - 20_000, duration, duration))
        // Past the last half second there is nothing left to switch for.
        assertNull(SwitchPlan.swapPoint(duration - 600, duration - 5_000, duration, duration))
    }

    @Test
    fun torrentsAreStartedFurtherAheadThanWebSources() {
        assertTrue(SwitchPlan.startLeadMs(true) > SwitchPlan.startLeadMs(false))
    }

    @Test
    fun softwareDecodersAreRecognised() {
        assertTrue(DecoderWatch.isSoftwareDecoder("c2.android.avc.decoder"))
        assertTrue(DecoderWatch.isSoftwareDecoder("OMX.google.h264.decoder"))
        assertTrue(DecoderWatch.isSoftwareDecoder("ffmpegVideoRenderer"))
        assertFalse(DecoderWatch.isSoftwareDecoder("OMX.hisi.video.decoder.hevc"))
        assertFalse(DecoderWatch.isSoftwareDecoder("c2.qti.avc.decoder"))
        assertFalse(DecoderWatch.isSoftwareDecoder(null))
    }

    @Test
    fun theLimitOnlyHoldsUntilItIsLifted() {
        val limit = PrepLimit(20_000_000L)
        assertEquals(20_000_000L, limit.maxBufferedUs)
        limit.lift()
        assertEquals(PrepLimit.UNLIMITED, limit.maxBufferedUs)
    }
}
