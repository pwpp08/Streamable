package com.streamflixreborn.streamflix.fragments.queue

import com.streamflixreborn.streamflix.fragments.queue.QueueStore.Reaction

/** The rating chips of the Watched list: everything, only what has no rating yet, or one rating. */
sealed interface RatingFilter {
    fun matches(entry: QueueEntry): Boolean

    data object Everything : RatingFilter {
        override fun matches(entry: QueueEntry) = true
    }

    data object Unrated : RatingFilter {
        override fun matches(entry: QueueEntry) = entry.reaction == null
    }

    data class Only(val reaction: Reaction) : RatingFilter {
        override fun matches(entry: QueueEntry) = entry.reaction == reaction
    }
}
