package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.utils.CinemaStatus.ReleaseEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaStatusTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun at(daysAgo: Long) = now - daysAgo * day

    @Test
    fun onlyInTheatresMeansACameraCopy() {
        val entries = listOf(ReleaseEntry(3, at(20)), ReleaseEntry(4, now + 30 * day))
        assertTrue(CinemaStatus.isCinemaOnly(entries, now))
    }

    @Test
    fun anyDigitalPhysicalOrTvReleaseThatHasHappenedEndsIt() {
        val theatrical = ReleaseEntry(3, at(40))
        assertFalse(CinemaStatus.isCinemaOnly(listOf(theatrical, ReleaseEntry(4, at(1))), now))
        assertFalse(CinemaStatus.isCinemaOnly(listOf(theatrical, ReleaseEntry(5, at(2))), now))
        assertFalse(CinemaStatus.isCinemaOnly(listOf(theatrical, ReleaseEntry(6, at(3))), now))
    }

    @Test
    fun aFestivalPremiereAloneIsNotInCinemas() {
        assertFalse(CinemaStatus.isCinemaOnly(listOf(ReleaseEntry(1, at(10))), now))
        assertFalse(CinemaStatus.isCinemaOnly(emptyList(), now))
    }

    @Test
    fun notYetOpenedIsNotInCinemas() {
        assertFalse(CinemaStatus.isCinemaOnly(listOf(ReleaseEntry(3, now + 5 * day)), now))
    }

    @Test
    fun anOldFilmWithNoHomeReleaseOnRecordIsMissingDataNotACameraCopy() {
        assertFalse(CinemaStatus.isCinemaOnly(listOf(ReleaseEntry(3, at(CinemaStatus.RECENT_DAYS + 30))), now))
    }

    @Test
    fun onlyRecentlyReleasedFilmsAreLookedUp() {
        assertTrue(CinemaStatus.worthChecking(at(10), now))
        assertFalse(CinemaStatus.worthChecking(at(CinemaStatus.RECENT_DAYS + 1), now))
        assertFalse(CinemaStatus.worthChecking(now + day, now))
        assertFalse(CinemaStatus.worthChecking(null, now))
    }

    @Test
    fun releaseDatesParseAsUtcDays() {
        val parsed = CinemaStatus.parseDate("2025-11-03T00:00:00.000Z")
        assertNotNull(parsed)
        assertEquals(parsed, CinemaStatus.parseDate("2025-11-03"))
        assertNull(CinemaStatus.parseDate(""))
        assertNull(CinemaStatus.parseDate(null))
        assertNull(CinemaStatus.parseDate("soon"))
    }

    @Test
    fun streamingDateIsTheEarliestDigitalReleaseStillToCome() {
        val entries = listOf(
            ReleaseEntry(3, at(20)),
            ReleaseEntry(4, now + 90 * day),
            ReleaseEntry(4, now + 60 * day),
            ReleaseEntry(5, now + 30 * day),
        )
        assertEquals(now + 60 * day, CinemaStatus.streamingDate(entries, now))
    }

    @Test
    fun noAnnouncedDigitalReleaseMeansNoDate() {
        assertNull(CinemaStatus.streamingDate(listOf(ReleaseEntry(3, at(5)), ReleaseEntry(5, now + 10 * day)), now))
        assertNull(CinemaStatus.streamingDate(listOf(ReleaseEntry(4, at(1))), now))
    }
}
