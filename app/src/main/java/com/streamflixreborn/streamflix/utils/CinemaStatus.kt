package com.streamflixreborn.streamflix.utils

import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.providers.TmdbProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Whether a film is only in cinemas so far: it has opened in theatres but has no digital,
 * physical or TV release anywhere yet. Any copy online is then a camera recording, so the app
 * says so on its card and details page.
 *
 * The answer comes from TMDb's release types and is cached; only films that opened in the last
 * [RECENT_DAYS] days are ever looked up, so browsing does not turn into a request per poster.
 */
object CinemaStatus {

    private const val PREFS = "cinema_status"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    const val RECENT_DAYS = 270L
    private const val RECHECK_MS = 12L * 60 * 60 * 1000

    /** One dated release of a film; [type] is TMDb's 1 premiere, 2/3 theatrical, 4 digital, 5 physical, 6 TV. */
    data class ReleaseEntry(val type: Int, val millis: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limiter = Semaphore(3)
    private val inFlight = HashSet<String>()

    /** Pure rule, unit-tested. */
    fun isCinemaOnly(entries: List<ReleaseEntry>, now: Long = System.currentTimeMillis()): Boolean {
        val inCinemas = entries.filter { (it.type == 2 || it.type == 3) && it.millis <= now }
        if (inCinemas.isEmpty()) return false
        if (entries.any { it.type in 4..6 && it.millis <= now }) return false
        // Old films with no home release on record are missing data, not camera copies.
        return inCinemas.minOf { it.millis } >= now - RECENT_DAYS * DAY_MS
    }

    /** Worth a lookup at all: out already, and out recently. */
    fun worthChecking(releasedMillis: Long?, now: Long = System.currentTimeMillis()): Boolean =
        releasedMillis != null && releasedMillis <= now && releasedMillis >= now - RECENT_DAYS * DAY_MS

    /** "2025-11-03T00:00:00.000Z" (or just the date) as UTC midnight. */
    fun parseDate(value: String?): Long? {
        val day = value?.take(10)?.takeIf { it.length == 10 } ?: return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(day)?.time
        }.getOrNull()
    }

    /** The cached answer, or null when it is not known (or too old). */
    fun peek(id: String): Boolean? {
        val stored = prefs().getString(id, null) ?: return null
        val flag = stored.substringBefore('|').toBooleanStrictOrNull() ?: return null
        val checkedAt = stored.substringAfter('|', "").toLongOrNull() ?: return null
        return flag.takeIf { System.currentTimeMillis() - checkedAt < RECHECK_MS }
    }

    /** Only the TMDb provider's ids are TMDb ids. */
    fun applies(): Boolean = UserPreferences.currentProvider is TmdbProvider

    /**
     * Looks the film up in the background when nothing fresh is cached, then calls [onResult] on
     * the thread it finished on with the answer.
     */
    fun request(id: String, onResult: (Boolean) -> Unit) {
        val tmdbId = id.toIntOrNull() ?: return
        synchronized(inFlight) { if (!inFlight.add(id)) return }
        scope.launch {
            try {
                val answer = limiter.withPermit { fetch(tmdbId) }
                if (answer != null) {
                    prefs().edit().putString(id, "$answer|${System.currentTimeMillis()}").apply()
                    onResult(answer)
                }
            } finally {
                synchronized(inFlight) { inFlight.remove(id) }
            }
        }
    }

    private suspend fun fetch(tmdbId: Int): Boolean? = runCatching {
        val detail = TMDb3.Movies.details(
            movieId = tmdbId,
            appendToResponse = listOf(TMDb3.Params.AppendToResponse.Movie.RELEASES_DATES),
        )
        val entries = detail.releaseDates?.results.orEmpty()
            .flatMap { it.releaseDates }
            .mapNotNull { date -> parseDate(date.releaseDate)?.let { ReleaseEntry(date.type.value, it) } }
        isCinemaOnly(entries)
    }.getOrNull()

    private fun prefs() = StreamFlixApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
