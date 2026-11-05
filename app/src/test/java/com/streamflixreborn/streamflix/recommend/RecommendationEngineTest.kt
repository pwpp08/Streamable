package com.streamflixreborn.streamflix.recommend

import com.streamflixreborn.streamflix.recommend.RecommendationEngine.Candidate
import com.streamflixreborn.streamflix.recommend.RecommendationEngine.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun candidate(key: String, genres: List<Int>, rating: Double = 7.5, votes: Int = 2000, popularity: Double = 80.0, seedHits: Int = 0) =
        Candidate(key, genres, rating, votes, popularity, seedHits)

    private fun signal(genres: List<Int>, strength: Double = 2.0, ageDays: Int = 1) =
        Signal(genres, strength, now - ageDays * day)

    @Test
    fun withNoHistoryTheBetterRatedTitleWins() {
        val ranked = RecommendationEngine.rank(
            candidates = listOf(candidate("m:1", listOf(35), rating = 6.0), candidate("m:2", listOf(18), rating = 8.4)),
            signals = emptyList(), seen = emptySet(), nowMillis = now,
        )
        assertEquals("m:2", ranked.first().candidate.key)
    }

    @Test
    fun tasteOutweighsARatingGapOnceThereIsHistory() {
        val horror = List(10) { signal(listOf(27, 53)) }
        val ranked = RecommendationEngine.rank(
            candidates = listOf(candidate("m:drama", listOf(18), rating = 8.2), candidate("m:horror", listOf(27), rating = 7.0)),
            signals = horror, seen = emptySet(), nowMillis = now,
        )
        assertEquals("m:horror", ranked.first().candidate.key)
    }

    @Test
    fun oneWatchedTitleBarelyMovesTheRanking() {
        val ranked = RecommendationEngine.rank(
            candidates = listOf(candidate("m:drama", listOf(18), rating = 8.2), candidate("m:horror", listOf(27), rating = 7.0)),
            signals = listOf(signal(listOf(27))), seen = emptySet(), nowMillis = now,
        )
        assertEquals("m:drama", ranked.first().candidate.key)
    }

    @Test
    fun confidenceGrowsWithHistoryAndCaps() {
        assertEquals(0.0, RecommendationEngine.confidence(0), 1e-9)
        assertTrue(RecommendationEngine.confidence(3) < RecommendationEngine.confidence(6))
        assertEquals(1.0, RecommendationEngine.confidence(50), 1e-9)
    }

    @Test
    fun seenTitlesAndTitlesWithFewVotesAreDropped() {
        val ranked = RecommendationEngine.rank(
            candidates = listOf(
                candidate("m:seen", listOf(28)),
                candidate("m:obscure", listOf(28), votes = 3),
                candidate("m:ok", listOf(28)),
            ),
            signals = emptyList(), seen = setOf("m:seen"), nowMillis = now,
        )
        assertEquals(listOf("m:ok"), ranked.map { it.candidate.key })
    }

    @Test
    fun recentAndFavoritedTitlesCountMoreThanOldOnes() {
        val profile = RecommendationEngine.tasteProfile(
            listOf(signal(listOf(35), strength = 2.0, ageDays = 400), signal(listOf(878), strength = 3.0, ageDays = 2)),
            now,
        )
        assertTrue(profile.getValue(878) > profile.getValue(35))
        assertEquals(1.0, profile.getValue(878), 1e-9)
    }

    @Test
    fun noGenreFloodsTheRow() {
        val sameGenre = List(10) { candidate("m:a$it", listOf(28), rating = 8.0) }
        val other = List(4) { candidate("m:b$it", listOf(35), rating = 6.5) }
        val ranked = RecommendationEngine.rank(
            candidates = sameGenre + other,
            signals = List(12) { signal(listOf(28)) }, seen = emptySet(), nowMillis = now, limit = 12, maxPerGenre = 5,
        )
        assertEquals(5, ranked.count { it.candidate.genres == listOf(28) })
        assertTrue(ranked.any { it.candidate.genres == listOf(35) })
    }

    @Test
    fun fewVotesPullARatingTowardTheMean() {
        assertTrue(RecommendationEngine.quality(9.8, 5) < RecommendationEngine.quality(8.6, 5000))
    }

    @Test
    fun tvAndMovieGenresShareOneProfile() {
        assertEquals(listOf(28), Genres.canonical(listOf(10759, 28)))
        val profile = RecommendationEngine.tasteProfile(listOf(signal(listOf(10765))), now)
        assertFalse(profile.containsKey(10765))
        assertTrue(profile.containsKey(878))
    }

    @Test
    fun topGenresAreStrongestFirst() {
        val signals = List(3) { signal(listOf(27)) } + List(1) { signal(listOf(35)) }
        assertEquals(listOf(27, 35), RecommendationEngine.topGenres(signals, now, count = 2))
    }
}
