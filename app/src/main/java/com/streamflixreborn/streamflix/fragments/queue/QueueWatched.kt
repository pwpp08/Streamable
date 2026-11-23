package com.streamflixreborn.streamflix.fragments.queue

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.utils.WatchStateActions

/**
 * The Queue keeps its own "seen" list (Swipe, imports, reactions), but the film page, the posters'
 * ticks, Recently watched and the cloud sync read the film's own watched flag. These keep the two
 * together, so a film in the Watched tab is marked watched everywhere.
 */
object QueueWatched {
    private const val TAG = "QueueWatched"

    /** Marks [movie] watched in its database without asking "how was it" again. */
    suspend fun markMovie(context: Context, movie: Movie) {
        runCatching { WatchStateActions.setWatched(context.applicationContext, movie, true, announce = false) }
            .onFailure { Log.w(TAG, "Could not mark ${movie.id} watched", it) }
    }

    /** Films in the Queue's seen list whose own flag is still off (marked before this fix). */
    suspend fun reconcile(context: Context) {
        val seenMovies = QueueStore.seen().filter { !it.key.startsWith("t:") }
        if (seenMovies.isEmpty()) return
        val dao = AppDatabase.getInstance(context.applicationContext).movieDao()
        seenMovies.forEach { seen ->
            val id = seen.key.substringAfter(':')
            val stored = dao.getById(id)
            if (stored?.isWatched == true) return@forEach
            markMovie(context, stored ?: Movie(id = id, title = seen.title, poster = seen.poster?.takeIf { it.isNotBlank() }))
        }
    }
}
