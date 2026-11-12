package com.streamflixreborn.streamflix.fragments.queue

import java.util.Calendar

/** How the Watchlist and Watched lists are ordered. In progress always follows what you touched last. */
enum class QueueSort { RECENT, TITLE, NEWEST, OLDEST, RATING }

object QueueSorting {

    /**
     * Release time used for ordering: the stored date, else the start of the year shown on the card
     * (titles marked as seen from Swipe or an import only carry a year). Null when nothing is known.
     */
    fun releaseKey(entry: QueueEntry): Long? {
        entry.releasedMillis?.let { return it }
        val year = entry.year?.trim()?.toIntOrNull()?.takeIf { it in 1800..2200 } ?: return null
        return Calendar.getInstance().apply { clear(); set(year, Calendar.JANUARY, 1) }.timeInMillis
    }

    /**
     * Stable and deterministic: ties fall back to the title, and titles with no date (or no
     * rating) always go last, whichever direction is chosen.
     */
    fun sort(items: List<QueueEntry>, sort: QueueSort): List<QueueEntry> {
        val byTitle = compareBy<QueueEntry> { it.title.lowercase() }
        return when (sort) {
            QueueSort.RECENT -> items.sortedWith(compareByDescending<QueueEntry> { it.sortTime }.then(byTitle))
            QueueSort.TITLE -> items.sortedWith(byTitle)
            QueueSort.NEWEST -> items.sortedWith(
                compareBy<QueueEntry> { releaseKey(it) == null }
                    .thenByDescending { releaseKey(it) ?: 0L }
                    .then(byTitle),
            )
            QueueSort.OLDEST -> items.sortedWith(
                compareBy<QueueEntry> { releaseKey(it) == null }
                    .thenBy { releaseKey(it) ?: 0L }
                    .then(byTitle),
            )
            QueueSort.RATING -> items.sortedWith(
                compareBy<QueueEntry> { (it.rating ?: 0.0) <= 0.0 }
                    .thenByDescending { it.rating ?: 0.0 }
                    .then(byTitle),
            )
        }
    }
}
