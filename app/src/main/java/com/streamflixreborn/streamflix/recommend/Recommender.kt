package com.streamflixreborn.streamflix.recommend

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.recommend.RecommendationEngine.Candidate
import com.streamflixreborn.streamflix.recommend.RecommendationEngine.Signal
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.w342
import com.streamflixreborn.streamflix.utils.TMDb3.w780
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Builds the "Recommended for you" row from what the active profile has watched, favorited or
 * started. Only for TMDb providers: their ids are TMDb ids, which is what the candidate pool uses.
 *
 * History lives in Room without genres, so each title's genres are fetched once from TMDb and kept
 * in [GenreCache]; after that, a refresh costs a handful of Discover/recommendation calls.
 */
object Recommender {
    private const val TAG = "Recommender"
    private const val HISTORY_LIMIT = 40
    private const val GENRE_LOOKUPS_PER_RUN = 15
    private const val SEED_COUNT = 4
    private const val ROW_SIZE = 20
    private const val DEFAULT_AGE_MS = 30L * 86_400_000L

    private const val MOVIE = 'm'
    private const val TV = 't'

    private data class Entry(val isTv: Boolean, val id: String, val strength: Double, val engagedAt: Long)

    /** Null when there is nothing to learn from yet or TMDb could not be reached. */
    suspend fun category(context: Context, name: String, language: String): Category? = withContext(Dispatchers.IO) {
        runCatching { build(context, name, language) }
            .onFailure { Log.w(TAG, "recommendations failed: ${it.message}") }
            .getOrNull()
    }

    private suspend fun build(context: Context, name: String, language: String): Category? = coroutineScope {
        val now = System.currentTimeMillis()
        val entries = history(context, now)
        if (entries.isEmpty()) return@coroutineScope null

        val cache = GenreCache(context)
        val recent = entries.sortedByDescending { it.engagedAt }.take(HISTORY_LIMIT)

        // Genres for the titles we have not looked up yet (a few per run, newest first).
        recent.filter { cache.get(keyOf(it.isTv, it.id)) == null }.take(GENRE_LOOKUPS_PER_RUN).map { entry ->
            async {
                runCatching { lookupGenres(entry, language) }.getOrNull()?.let { cache.put(keyOf(entry.isTv, entry.id), it) }
            }
        }.awaitAll()

        val signals = recent.mapNotNull { entry ->
            val genres = cache.get(keyOf(entry.isTv, entry.id)) ?: return@mapNotNull null
            Signal(genres, entry.strength, entry.engagedAt, entry.isTv)
        }
        if (signals.isEmpty()) return@coroutineScope null

        val seen = entries.map { keyOf(it.isTv, it.id) }.toSet()
        val seeds = recent.sortedByDescending { it.strength * 1_000_000_000_000.0 + it.engagedAt }.take(SEED_COUNT)
        val topGenres = RecommendationEngine.topGenres(signals, now)

        val pool = LinkedHashMap<String, Pick>()
        fun collect(pick: Pick, fromSeed: Boolean) {
            val existing = pool[pick.candidate.key]
            if (existing == null) {
                pool[pick.candidate.key] = pick
            } else if (fromSeed) {
                pool[pick.candidate.key] = existing.copy(candidate = existing.candidate.copy(seedHits = existing.candidate.seedHits + 1))
            }
        }

        val jobs = buildList {
            seeds.forEach { seed -> add(async { runCatching { seedRecommendations(seed, language) }.getOrDefault(emptyList()) }) }
            topGenres.forEach { genre ->
                add(async { runCatching { discover(genre, tv = false, language) }.getOrDefault(emptyList()) })
                add(async { runCatching { discover(genre, tv = true, language) }.getOrDefault(emptyList()) })
            }
        }
        val seedCount = seeds.size
        jobs.awaitAll().forEachIndexed { index, picks -> picks.forEach { collect(it, fromSeed = index < seedCount) } }

        val ranked = RecommendationEngine.rank(
            candidates = pool.values.map { it.candidate },
            signals = signals,
            seen = seen,
            nowMillis = now,
            limit = ROW_SIZE,
        )
        Log.d(TAG, "signals=${signals.size} topGenres=$topGenres pool=${pool.size} ranked=${ranked.size}")
        if (ranked.size < 4) return@coroutineScope null

        val items: List<AppAdapter.Item> = ranked.mapNotNull { pool[it.candidate.key]?.item }
        Category(name = name, list = items)
    }

    private data class Pick(val candidate: Candidate, val item: AppAdapter.Item)

    private fun keyOf(isTv: Boolean, id: String) = "${if (isTv) TV else MOVIE}:$id"

    /** Everything the active profile engaged with, with a strength per title. */
    private fun history(context: Context, now: Long): List<Entry> {
        val db = AppDatabase.getInstance(context)
        val entries = ArrayList<Entry>()

        for (movie in db.movieDao().getAll()) {
            val engagedAt = movie.watchHistory?.lastEngagementTimeUtcMillis
                ?: movie.lastPlayedAtMillis
                ?: movie.watchedDate?.timeInMillis
                ?: movie.favoritedAtMillis
            val progress = movie.watchHistory?.let { it.lastPlaybackPositionMillis.toDouble() / it.durationMillis.coerceAtLeast(1) } ?: 0.0
            val strength = when {
                movie.isFavorite -> 3.0
                movie.isWatched || progress >= 0.8 -> 2.0
                engagedAt != null -> if (progress >= 0.2) 1.5 else 1.0
                else -> continue
            }
            entries += Entry(isTv = false, id = movie.id, strength = strength, engagedAt = engagedAt ?: (now - DEFAULT_AGE_MS))
        }

        for (show in db.tvShowDao().getAllForBackup()) {
            val engagedAt = show.lastPlayedAtMillis ?: show.favoritedAtMillis
            val strength = when {
                show.isFavorite -> 3.0
                engagedAt != null -> 1.5
                else -> continue
            }
            entries += Entry(isTv = true, id = show.id, strength = strength, engagedAt = engagedAt ?: (now - DEFAULT_AGE_MS))
        }
        return entries.filter { it.id.toIntOrNull() != null }
    }

    private suspend fun lookupGenres(entry: Entry, language: String): List<Int> {
        val id = entry.id.toInt()
        return if (entry.isTv) {
            TMDb3.TvSeries.details(seriesId = id, language = language).genres.map { it.id }
        } else {
            TMDb3.Movies.details(movieId = id, language = language).genres.map { it.id }
        }
    }

    /** TMDb's own "viewers of X also liked" list for a seed title. */
    private suspend fun seedRecommendations(seed: Entry, language: String): List<Pick> {
        val id = seed.id.toInt()
        val results = if (seed.isTv) {
            TMDb3.TvSeries.details(
                seriesId = id,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS),
                language = language,
            ).recommendations?.results
        } else {
            TMDb3.Movies.details(
                movieId = id,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS),
                language = language,
            ).recommendations?.results
        }
        return results.orEmpty().mapNotNull(::pick)
    }

    private suspend fun discover(genre: Int, tv: Boolean, language: String): List<Pick> {
        val params = mapOf(
            "with_genres" to (if (tv) Genres.forTv(genre) else genre).toString(),
            "sort_by" to "popularity.desc",
            "vote_count.gte" to "300",
            "vote_average.gte" to "6.5",
            "language" to language,
        )
        val results: List<TMDb3.MultiItem> =
            if (tv) TMDb3.Discover.tv(params).results else TMDb3.Discover.movie(params).results
        return results.mapNotNull(::pick)
    }

    private fun pick(multi: TMDb3.MultiItem): Pick? = when (multi) {
        is TMDb3.Movie -> Pick(
            Candidate(
                key = keyOf(false, multi.id.toString()),
                genres = multi.genresIds,
                rating = multi.voteAverage.toDouble(),
                votes = multi.voteCount,
                popularity = multi.popularity.toDouble(),
            ),
            Movie(
                id = multi.id.toString(),
                title = multi.title,
                overview = multi.overview,
                released = multi.releaseDate,
                rating = multi.voteAverage.toDouble(),
                poster = multi.posterPath?.w342,
                banner = multi.backdropPath?.w780,
            ),
        ).takeIf { multi.posterPath != null }

        is TMDb3.Tv -> Pick(
            Candidate(
                key = keyOf(true, multi.id.toString()),
                genres = multi.genresIds,
                rating = multi.voteAverage.toDouble(),
                votes = multi.voteCount,
                popularity = multi.popularity.toDouble(),
                isTv = true,
            ),
            TvShow(
                id = multi.id.toString(),
                title = multi.name,
                overview = multi.overview,
                released = multi.firstAirDate,
                rating = multi.voteAverage.toDouble(),
                poster = multi.posterPath?.w342,
                banner = multi.backdropPath?.w780,
            ),
        ).takeIf { multi.posterPath != null }

        else -> null
    }

    /** Genre ids per title, kept across launches so history is only looked up once. */
    private class GenreCache(context: Context) {
        private val prefs = context.getSharedPreferences("recommend_genres", Context.MODE_PRIVATE)

        fun get(key: String): List<Int>? =
            prefs.getString(key, null)?.split(',')?.mapNotNull { it.toIntOrNull() }

        fun put(key: String, genres: List<Int>) {
            prefs.edit().putString(key, genres.joinToString(",")).apply()
        }
    }
}
