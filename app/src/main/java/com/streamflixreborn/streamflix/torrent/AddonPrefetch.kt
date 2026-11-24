package com.streamflixreborn.streamflix.torrent

import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.launch

/** Gets the addons' streams for what is waiting in "continue watching", before anyone presses play. */
object AddonPrefetch {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val asked = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val AGAIN_AFTER_MS = 30 * 60_000L
    private const val MAX_TITLES = 10

    /**
     * The viewer is likely to open one of the first titles of a row (Home rows, the Queue): ask the addons about
     * them now, a little apart, so their answers are saved by the time play is pressed. Each title is asked about
     * at most every half hour.
     */
    fun rows(lists: List<List<Any>>, rows: Int = 3, perRow: Int = 3) {
        val now = System.currentTimeMillis()
        val types = lists.take(rows).flatMap { it.take(perRow) }
            .mapNotNull { typeOf(it) }
            .filter { type ->
                val key = when (type) {
                    is Video.Type.Movie -> "m:${type.id}"
                    is Video.Type.Episode -> "e:${type.id}"
                }
                val last = asked[key]
                (last == null || now - last > AGAIN_AFTER_MS).also { if (it) asked[key] = now }
            }
            .take(MAX_TITLES)
        if (types.isEmpty()) return
        scope.launch {
            types.forEach {
                StremioAddons.prefetch(it)
                kotlinx.coroutines.delay(400)
            }
        }
    }

    fun continueWatching(items: List<Any>) {
        items.take(4).forEach { item -> typeOf(item)?.let(StremioAddons::prefetch) }
        // The first item is the one most likely pressed next: get its servers resolved too.
        items.firstOrNull()?.let { typeOf(it) }?.let(com.streamflixreborn.streamflix.fragments.player.engine.PlayWarmup::warm)
    }

    /** The saved answer for this item is not needed any more (finished, or removed from the row). */
    fun forget(item: Any) {
        typeOf(item)?.let(StremioAddons::forget)
    }

    fun typeOf(item: Any): Video.Type? = when (item) {
        is Movie -> Video.Type.Movie(
            id = item.id,
            title = item.title,
            releaseDate = item.released?.format("yyyy-MM-dd") ?: "",
            poster = item.poster ?: "",
            imdbId = item.imdbId,
        )
        is Episode -> Video.Type.Episode(
            id = item.id,
            number = item.number,
            title = item.title,
            poster = item.poster,
            overview = item.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = item.tvShow?.id ?: "",
                title = item.tvShow?.title ?: "",
                poster = item.tvShow?.poster,
                banner = item.tvShow?.banner,
                releaseDate = item.tvShow?.released?.format("yyyy-MM-dd"),
                imdbId = item.tvShow?.imdbId,
            ),
            season = Video.Type.Episode.Season(number = item.season?.number ?: 0, title = item.season?.title),
        )
        else -> null
    }
}
