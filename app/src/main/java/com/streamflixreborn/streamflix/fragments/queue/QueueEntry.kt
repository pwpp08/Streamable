package com.streamflixreborn.streamflix.fragments.queue

import com.streamflixreborn.streamflix.fragments.queue.QueueStore.Reaction
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.format

/** One card in the Queue tab. */
data class QueueEntry(
    val key: String,
    val isTv: Boolean,
    val id: String,
    val title: String,
    val poster: String?,
    val year: String? = null,
    val rating: Double? = null,
    val releasedMillis: Long? = null,
    val reaction: Reaction? = null,
    val inQueue: Boolean = false,
    val watched: Boolean = false,
    /** Up next: the episode to resume or start. */
    val episode: Episode? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** Up next: 0–100 while something is part-way through. */
    val progress: Int? = null,
    val remainingMs: Long? = null,
    /** When it was added, played or watched; the "Recent" sort. */
    val sortTime: Long = 0L,
    val movie: Movie? = null,
    val tvShow: TvShow? = null,
) {
    companion object {
        fun of(movie: Movie, reactions: Map<String, Reaction>, inQueue: Boolean): QueueEntry {
            val key = QueueStore.key(false, movie.id)
            return QueueEntry(
                key = key,
                isTv = false,
                id = movie.id,
                title = movie.title,
                poster = movie.poster ?: movie.banner,
                year = movie.released?.format("yyyy"),
                rating = movie.rating,
                releasedMillis = movie.released?.timeInMillis,
                reaction = reactions[key],
                inQueue = inQueue,
                watched = movie.isWatched,
                sortTime = movie.favoritedAtMillis ?: 0L,
                movie = movie,
            )
        }

        fun of(show: TvShow, reactions: Map<String, Reaction>, inQueue: Boolean): QueueEntry {
            val key = QueueStore.key(true, show.id)
            return QueueEntry(
                key = key,
                isTv = true,
                id = show.id,
                title = show.title,
                poster = show.poster ?: show.banner,
                year = show.released?.format("yyyy"),
                rating = show.rating,
                releasedMillis = show.released?.timeInMillis,
                reaction = reactions[key],
                inQueue = inQueue,
                sortTime = show.favoritedAtMillis ?: 0L,
                tvShow = show,
            )
        }
    }
}
