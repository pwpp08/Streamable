package com.streamflixreborn.streamflix.fragments.queue

import com.streamflixreborn.streamflix.fragments.queue.QueueStore.Reaction
import com.streamflixreborn.streamflix.utils.ReleaseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class QueueRulesTest {

    private fun entry(reaction: Reaction?, releasedMillis: Long? = null) = QueueEntry(
        key = "m:1", isTv = false, id = "1", title = "T", poster = null,
        reaction = reaction, releasedMillis = releasedMillis,
    )

    @Test
    fun fiveReactionsInBestToWorstOrder() {
        assertEquals(
            listOf("LOVED", "LIKED", "OKAY", "DISLIKED", "HATED"),
            Reaction.entries.map { it.name },
        )
        assertEquals(Reaction.HATED, Reaction.of("HATED"))
        assertNull(Reaction.of("MEH"))
    }

    @Test
    fun ratingFilterMatchesOnlyTheChosenRating() {
        val loved = entry(Reaction.LOVED)
        val none = entry(null)
        assertTrue(RatingFilter.Everything.matches(loved))
        assertTrue(RatingFilter.Everything.matches(none))
        assertTrue(RatingFilter.Only(Reaction.LOVED).matches(loved))
        assertFalse(RatingFilter.Only(Reaction.LIKED).matches(loved))
        assertFalse(RatingFilter.Only(Reaction.LOVED).matches(none))
        assertTrue(RatingFilter.Unrated.matches(none))
        assertFalse(RatingFilter.Unrated.matches(loved))
    }

    @Test
    fun aTitleIsUnreleasedOnlyBeforeItsReleaseDay() {
        val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 6, 12, 0) }.timeInMillis
        val tomorrow = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 7) }
        val today = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 6) }
        assertTrue(ReleaseStatus.isUnreleased(tomorrow, now))
        assertFalse(ReleaseStatus.isUnreleased(today, now))
        assertFalse(ReleaseStatus.isUnreleased(null as Calendar?, now))
        assertTrue(ReleaseStatus.isUnreleased(tomorrow.timeInMillis, now))
        assertFalse(ReleaseStatus.isUnreleased(null as Long?, now))
    }

    @Test
    fun unreleasedEntriesCannotBePlayed() {
        val future = System.currentTimeMillis() + 5L * 86_400_000L
        assertFalse(QueueNavigator.canPlay(entry(null, releasedMillis = future)))
        assertTrue(QueueNavigator.canPlay(entry(null, releasedMillis = 1_000L)))
        assertTrue(QueueNavigator.canPlay(entry(null)))
    }
}
