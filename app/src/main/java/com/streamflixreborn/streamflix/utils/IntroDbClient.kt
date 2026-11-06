package com.streamflixreborn.streamflix.utils

import android.util.Log
import android.util.LruCache
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Intro / recap / credits timestamps from TheIntroDB (https://theintrodb.org).
 *
 * `GET https://api.theintrodb.org/v2/media?tmdb_id=1396&season=1&episode=1` returns e.g.
 * `{"tmdb_id":1396,"type":"tv","season":1,"episode":1,
 *   "intro":[{"start_ms":228627,"end_ms":246339}],"credits":[{"start_ms":3431000,"end_ms":null}]}`
 * where a null start means "from the beginning" and a null end means "until the end".
 * Movies omit season/episode. Every failure is reported as "no data".
 */
object IntroDbClient {

    private const val TAG = "IntroDbClient"
    private const val BASE_URL = "https://api.theintrodb.org/v2/media"

    /** How far before a known intro end the skip is offered when the intro start is unknown. */
    const val OPEN_START_LEAD_MS = 90_000L

    /** Nothing is offered in the last moments of a segment: skipping would land on the same spot. */
    const val END_GUARD_MS = 1_500L

    /** Longer "intros" are bad crowd-sourced data (or a whole scene), not something to offer a skip for. */
    private const val MAX_SKIPPABLE_MS = 5 * 60_000L

    enum class SegmentType(val key: String) {
        RECAP("recap"),
        INTRO("intro"),
        CREDITS("credits"),
        PREVIEW("preview"),
    }

    data class Segment(val type: SegmentType, val startMs: Long?, val endMs: Long?) {
        /**
         * When the database only knows where the intro ends ("start": null) it does not mean the
         * intro runs from 0:00, so the skip offer starts a typical intro's length before the end.
         */
        fun start(): Long = startMs ?: if (endMs != null) (endMs - OPEN_START_LEAD_MS).coerceAtLeast(0L) else 0L
        fun end(durationMs: Long): Long = endMs ?: durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE

        /** Intro or recap that a viewer can be offered a skip for. */
        val isSkippable get() = type == SegmentType.INTRO || type == SegmentType.RECAP
    }

    data class Segments(val segments: List<Segment>) {
        fun ofType(type: SegmentType) = segments.filter { it.type == type }

        /** Start of the end credits, if known and plausible for [durationMs]. */
        fun creditsStartMs(durationMs: Long): Long? = ofType(SegmentType.CREDITS)
            .mapNotNull { it.startMs }
            .filter { durationMs <= 0 || (it in (durationMs / 2) until durationMs) }
            .minOrNull()

        /** Intro or recap that [positionMs] is inside of (and not about to end), earliest first. */
        fun skippableAt(positionMs: Long, durationMs: Long): Segment? = segments
            .filter { it.isSkippable }
            .sortedBy { it.start() }
            .firstOrNull { positionMs >= it.start() && positionMs < it.end(durationMs) - END_GUARD_MS }

        val isEmpty get() = segments.isEmpty()
    }

    data class Query(val tmdbId: Int, val season: Int?, val episode: Int?)

    private val client: OkHttpClient by lazy {
        NetworkClient.default.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val cache = LruCache<Query, Segments>(64)

    /** TMDB ids are only available from [TmdbProvider]; other providers use their own ids. */
    fun queryFor(provider: Provider?, videoType: Video.Type): Query? {
        if (provider !is TmdbProvider) return null
        return when (videoType) {
            is Video.Type.Movie -> videoType.id.toIntOrNull()?.let { Query(it, null, null) }
            is Video.Type.Episode -> videoType.tvShow.id.toIntOrNull()?.let {
                Query(it, videoType.season.number, videoType.number)
            }
        }?.takeIf { it.tmdbId in 1..10_000_000 }
    }

    suspend fun fetch(query: Query): Segments? = withContext(Dispatchers.IO) {
        cache.get(query)?.let { return@withContext it }
        val url = buildString {
            append(BASE_URL).append("?tmdb_id=").append(query.tmdbId)
            if (query.season != null && query.episode != null) {
                append("&season=").append(query.season).append("&episode=").append(query.episode)
            }
        }
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Sec-Fetch-Mode", "cors")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "no data (${response.code}) for $query")
                    return@withContext null
                }
                val segments = parse(response.body?.string().orEmpty())
                if (segments != null) cache.put(query, segments)
                segments
            }
        } catch (e: Exception) {
            Log.w(TAG, "request failed for $query: ${e.message}")
            null
        }
    }

    fun parse(json: String): Segments? = runCatching {
        val root = JSONObject(json)
        val segments = SegmentType.entries.flatMap { type ->
            val array: JSONArray = root.optJSONArray(type.key) ?: return@flatMap emptyList()
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val start = item.optLongOrNull("start_ms")
                val end = item.optLongOrNull("end_ms")
                if (start == null && end == null) return@mapNotNull null
                if (start != null && end != null && end <= start) return@mapNotNull null
                val skippable = type == SegmentType.INTRO || type == SegmentType.RECAP
                if (skippable && end != null && end <= 0L) return@mapNotNull null
                if (skippable && start != null && end != null && end - start > MAX_SKIPPABLE_MS) return@mapNotNull null
                Segment(type, start, end)
            }
        }
        Segments(segments)
    }.getOrNull()

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name, -1L).takeIf { it >= 0 }
}
