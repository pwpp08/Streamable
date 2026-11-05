package com.streamflixreborn.streamflix.recommend

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Taste-based ranking for the "Recommended for you" row. Pure Kotlin, no Android, no network.
 *
 * Every title the user has engaged with is a [Signal]; its genres become a weighted taste profile.
 * Candidates are scored from how well their genres match the profile, a prior on how well-regarded
 * they are, and a boost when a candidate came from TMDb's own "people who liked X also liked Y".
 *
 * [confidence] grows with the amount of history: with nothing watched the ranking is just quality
 * and popularity, and every extra title shifts weight toward personal taste.
 */
object RecommendationEngine {

    /** Titles needed before taste outweighs the quality prior. */
    const val FULL_CONFIDENCE_TITLES = 12
    private const val HALF_LIFE_DAYS = 60.0
    private const val MS_PER_DAY = 86_400_000.0

    /** Mean TMDb rating and the vote count that makes a rating trustworthy, for the Bayesian average. */
    private const val PRIOR_MEAN = 6.5
    private const val PRIOR_VOTES = 300.0

    data class Signal(
        val genres: List<Int>,
        /** Favorite 3, finished 2, started 1. */
        val strength: Double,
        val engagedAtMillis: Long,
        val isTv: Boolean = false,
    )

    data class Candidate(
        val key: String,
        val genres: List<Int>,
        val rating: Double,
        val votes: Int,
        val popularity: Double,
        /** How many of the user's seed titles TMDb listed this one under. */
        val seedHits: Int = 0,
        val isTv: Boolean = false,
    )

    data class Scored(val candidate: Candidate, val score: Double)

    /** Genre id -> affinity in 0..1 (1 = the most-liked genre). */
    fun tasteProfile(signals: List<Signal>, nowMillis: Long): Map<Int, Double> {
        val raw = HashMap<Int, Double>()
        for (signal in signals) {
            if (signal.genres.isEmpty()) continue
            val ageDays = ((nowMillis - signal.engagedAtMillis).coerceAtLeast(0)) / MS_PER_DAY
            val decay = exp(-ln(2.0) * ageDays / HALF_LIFE_DAYS)
            // A title's weight is split over its genres, so a 5-genre title does not outvote a 2-genre one.
            val share = signal.strength * decay / sqrt(signal.genres.size.toDouble())
            for (genre in Genres.canonical(signal.genres)) raw[genre] = (raw[genre] ?: 0.0) + share
        }
        val max = raw.values.maxOrNull() ?: return emptyMap()
        return raw.mapValues { it.value / max }
    }

    /** 0 with no history, 1 once [FULL_CONFIDENCE_TITLES] distinct titles have been engaged with. */
    fun confidence(titleCount: Int): Double = min(1.0, titleCount / FULL_CONFIDENCE_TITLES.toDouble())

    /** Rating pulled toward the mean for titles with few votes, scaled to 0..1. */
    fun quality(rating: Double, votes: Int): Double {
        val v = votes.coerceAtLeast(0).toDouble()
        val bayes = (v / (v + PRIOR_VOTES)) * rating + (PRIOR_VOTES / (v + PRIOR_VOTES)) * PRIOR_MEAN
        return (bayes / 10.0).coerceIn(0.0, 1.0)
    }

    fun score(candidate: Candidate, taste: Map<Int, Double>, confidence: Double): Double {
        val genres = Genres.canonical(candidate.genres)
        val affinity = if (genres.isEmpty() || taste.isEmpty()) 0.0 else {
            // Mean of the best two matches: one strong genre is not drowned by unrelated ones.
            genres.map { taste[it] ?: 0.0 }.sortedDescending().take(2).average()
        }
        val quality = quality(candidate.rating, candidate.votes)
        val popularity = (ln(1.0 + candidate.popularity.coerceAtLeast(0.0)) / ln(1.0 + 1000.0)).coerceIn(0.0, 1.0)
        val seed = min(candidate.seedHits, 3) / 3.0

        val general = 0.75 * quality + 0.25 * popularity
        val personal = 0.55 * affinity + 0.25 * seed + 0.20 * quality
        return (1.0 - confidence) * general + confidence * personal
    }

    /**
     * The best [limit] candidates, best first. Titles in [seen] are dropped; no genre fills more than
     * [maxPerGenre] of the list so one favorite genre cannot make the whole row samey.
     */
    fun rank(
        candidates: List<Candidate>,
        signals: List<Signal>,
        seen: Set<String>,
        nowMillis: Long,
        limit: Int = 20,
        maxPerGenre: Int = 8,
    ): List<Scored> {
        val taste = tasteProfile(signals, nowMillis)
        val confidence = confidence(signals.size)
        val scored = candidates
            .asSequence()
            .distinctBy { it.key }
            .filter { it.key !in seen }
            .filter { it.votes >= 20 }
            .map { Scored(it, score(it, taste, confidence)) }
            .sortedByDescending { it.score }
            .toList()

        val perGenre = HashMap<Int, Int>()
        val picked = ArrayList<Scored>(limit)
        for (item in scored) {
            val lead = Genres.canonical(item.candidate.genres).firstOrNull()
            if (lead != null && (perGenre[lead] ?: 0) >= maxPerGenre) continue
            if (lead != null) perGenre[lead] = (perGenre[lead] ?: 0) + 1
            picked += item
            if (picked.size == limit) break
        }
        return picked
    }

    /** Taste genres worth a Discover query, strongest first. */
    fun topGenres(signals: List<Signal>, nowMillis: Long, count: Int = 3): List<Int> =
        tasteProfile(signals, nowMillis).entries.sortedByDescending { it.value }.take(count).map { it.key }
}

/** TMDb uses different ids for the same idea on TV; fold them so movie and TV history share one profile. */
object Genres {
    private val tvToMovie = mapOf(
        10759 to 28,   // Action & Adventure
        10765 to 878,  // Sci-Fi & Fantasy
        10768 to 10752, // War & Politics
    )

    fun canonical(genres: List<Int>): List<Int> = genres.map { tvToMovie[it] ?: it }.distinct()

    /** The id to ask Discover for when searching TV. */
    fun forTv(movieGenre: Int): Int = when (movieGenre) {
        28, 12 -> 10759
        878, 14 -> 10765
        10752 -> 10768
        else -> movieGenre
    }
}
