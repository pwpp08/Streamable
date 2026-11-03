package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.utils.WatchStateActions.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WatchStateRulesTest {

    private val history = WatchItem.WatchHistory(1_000L, 500L, 2_000L)

    @Test
    fun `mark watched clears the progress`() {
        val movie = Movie(id = "m").apply { watchHistory = history }
        val now = Calendar.getInstance()
        Rules.markWatched(movie, now)
        assertTrue(movie.isWatched)
        assertEquals(now, movie.watchedDate)
        assertNull(movie.watchHistory)
    }

    @Test
    fun `mark unwatched keeps the progress`() {
        val episode = Episode(id = "e").apply {
            isWatched = true
            watchedDate = Calendar.getInstance()
            watchHistory = history
        }
        Rules.markUnwatched(episode)
        assertFalse(episode.isWatched)
        assertNull(episode.watchedDate)
        assertEquals(history, episode.watchHistory)
    }

    @Test
    fun `clear progress only drops the watch history`() {
        val movie = Movie(id = "m").apply {
            isWatched = true
            watchHistory = history
        }
        Rules.clearProgress(movie)
        assertNull(movie.watchHistory)
        assertTrue(movie.isWatched)
    }

    @Test
    fun `provider name comes from the movie or the show of the episode`() {
        assertEquals("A", Rules.providerNameOf(Movie(id = "m", providerName = "A")))
        assertEquals("B", Rules.providerNameOf(Episode(id = "e", tvShow = TvShow(id = "s", providerName = "B"))))
        assertNull(Rules.providerNameOf(Movie(id = "m", providerName = " ")))
        assertTrue(Rules.isOtherProvider(Movie(id = "m", providerName = "A"), "B"))
        assertFalse(Rules.isOtherProvider(Movie(id = "m", providerName = "A"), "A"))
        assertFalse(Rules.isOtherProvider(Movie(id = "m"), "A"))
    }

    @Test
    fun `season episodes merge remote metadata with local state and keep local-only rows`() {
        val localWatched = Episode(id = "1", number = 1, title = "old").apply { isWatched = true }
        val localOnly = Episode(id = "9", number = 9).apply { watchHistory = history }
        val remote = listOf(Episode(id = "1", number = 1, title = "new"), Episode(id = "2", number = 2))

        val result = Rules.seasonEpisodes(listOf(localWatched, localOnly), remote, tvShowId = "s", seasonId = "s-1")

        assertEquals(listOf("1", "2", "9"), result.map { it.id })
        assertEquals("new", result[0].title)
        assertTrue(result[0].isWatched)
        assertFalse(result[1].isWatched)
        assertEquals(history, result[2].watchHistory)
        assertTrue(result.all { it.tvShow?.id == "s" && it.season?.id == "s-1" })
    }

    @Test
    fun `up to keeps episodes up to the given number`() {
        val episodes = (1..5).map { Episode(id = "$it", number = it) }
        assertEquals(listOf(1, 2, 3), Rules.upTo(episodes, 3).map { it.number })
    }
}
