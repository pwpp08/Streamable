package com.streamflixreborn.streamflix.fragments.queue

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class QueueSortingTest {

    private fun millis(year: Int, month: Int = Calendar.JANUARY, day: Int = 1) =
        Calendar.getInstance().apply { clear(); set(year, month, day) }.timeInMillis

    private fun entry(
        title: String,
        year: String? = null,
        released: Long? = null,
        rating: Double? = null,
        sortTime: Long = 0L,
    ) = QueueEntry(
        key = "m:$title", isTv = false, id = title, title = title, poster = null,
        year = year, releasedMillis = released, rating = rating, sortTime = sortTime,
    )

    private fun titles(items: List<QueueEntry>, sort: QueueSort) = QueueSorting.sort(items, sort).map { it.title }

    @Test
    fun newestFirstUsesTheReleaseDate() {
        val items = listOf(
            entry("Old", released = millis(1999)),
            entry("New", released = millis(2024, Calendar.MARCH, 5)),
            entry("Mid", released = millis(2010)),
        )
        assertEquals(listOf("New", "Mid", "Old"), titles(items, QueueSort.NEWEST))
        assertEquals(listOf("Old", "Mid", "New"), titles(items, QueueSort.OLDEST))
    }

    @Test
    fun aTitleWithOnlyAYearStillSortsByIt() {
        val items = listOf(
            entry("A", year = "2001"),
            entry("B", released = millis(2020, Calendar.JUNE, 1)),
            entry("C", year = "2015"),
        )
        assertEquals(listOf("B", "C", "A"), titles(items, QueueSort.NEWEST))
    }

    @Test
    fun titlesWithNoDateAlwaysGoLastInBothDirections() {
        val items = listOf(
            entry("Unknown"),
            entry("Dated", released = millis(2005)),
        )
        assertEquals(listOf("Dated", "Unknown"), titles(items, QueueSort.NEWEST))
        assertEquals(listOf("Dated", "Unknown"), titles(items, QueueSort.OLDEST))
    }

    @Test
    fun sameReleaseDateFallsBackToTheTitleSoTheOrderNeverShuffles() {
        val day = millis(2022, Calendar.MAY, 5)
        val items = listOf(entry("Zed", released = day), entry("alpha", released = day), entry("Mike", released = day))
        assertEquals(listOf("alpha", "Mike", "Zed"), titles(items, QueueSort.NEWEST))
        assertEquals(titles(items, QueueSort.NEWEST), titles(items.reversed(), QueueSort.NEWEST))
    }

    @Test
    fun ratingPutsTheBestFirstAndUnratedLast() {
        val items = listOf(
            entry("None"),
            entry("Good", rating = 7.9),
            entry("Best", rating = 9.1),
            entry("Zero", rating = 0.0),
        )
        assertEquals(listOf("Best", "Good", "None", "Zero"), titles(items, QueueSort.RATING))
    }

    @Test
    fun recentIsLatestTouchedFirstAndTitleIsAlphabetical() {
        val items = listOf(entry("B", sortTime = 5), entry("A", sortTime = 9), entry("C", sortTime = 1))
        assertEquals(listOf("A", "B", "C"), titles(items, QueueSort.RECENT))
        assertEquals(listOf("A", "B", "C"), titles(items.reversed(), QueueSort.TITLE))
    }
}
