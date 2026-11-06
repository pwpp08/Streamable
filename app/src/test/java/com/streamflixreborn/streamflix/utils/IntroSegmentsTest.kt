package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroSegmentsTest {

    private fun segments(json: String) = IntroDbClient.parse(json)!!

    @Test
    fun introWithBothEndsIsOfferedOnlyInsideIt() {
        val s = segments("""{"intro":[{"start_ms":228647,"end_ms":246339}]}""")
        assertNull(s.skippableAt(200_000, 3_000_000))
        assertNotNull(s.skippableAt(228_647, 3_000_000))
        assertNotNull(s.skippableAt(240_000, 3_000_000))
        assertNull(s.skippableAt(250_000, 3_000_000))
    }

    @Test
    fun pillGoesAwayJustBeforeTheEndSoSkippingNeverLandsOnTheSameSpot() {
        val s = segments("""{"intro":[{"start_ms":10000,"end_ms":40000}]}""")
        assertNotNull(s.skippableAt(38_000, 600_000))
        assertNull(s.skippableAt(39_000, 600_000))
    }

    @Test
    fun unknownStartDoesNotMeanTheIntroRunsFromTheVeryBeginning() {
        // Only the end (1:46) is known: the offer starts a typical intro length before it, not at 0:00.
        val s = segments("""{"intro":[{"start_ms":null,"end_ms":106019}]}""")
        assertNull(s.skippableAt(5_000, 3_000_000))
        assertNotNull(s.skippableAt(106_019 - IntroDbClient.OPEN_START_LEAD_MS + 1_000, 3_000_000))
        assertNotNull(s.skippableAt(100_000, 3_000_000))
    }

    @Test
    fun shortUnknownStartIntroStartsAtZero() {
        val s = segments("""{"intro":[{"start_ms":null,"end_ms":38000}]}""")
        assertNotNull(s.skippableAt(1_000, 3_000_000))
        assertNull(s.skippableAt(37_000, 3_000_000))
    }

    @Test
    fun implausiblyLongIntroIsDropped() {
        val s = segments("""{"intro":[{"start_ms":0,"end_ms":900000}]}""")
        assertTrue(s.isEmpty)
    }

    @Test
    fun recapAndIntroAreOfferedInOrder() {
        val s = segments("""{"recap":[{"start_ms":0,"end_ms":60000}],"intro":[{"start_ms":70000,"end_ms":150000}]}""")
        assertEquals(IntroDbClient.SegmentType.RECAP, s.skippableAt(10_000, 3_000_000)?.type)
        assertEquals(IntroDbClient.SegmentType.INTRO, s.skippableAt(80_000, 3_000_000)?.type)
    }

    @Test
    fun creditsAreNeverOfferedAsASkip() {
        val s = segments("""{"credits":[{"start_ms":3433269,"end_ms":null}]}""")
        assertNull(s.skippableAt(3_500_000, 3_600_000))
        assertEquals(3_433_269L, s.creditsStartMs(3_600_000))
    }
}
