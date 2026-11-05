package com.streamflixreborn.streamflix.desktop.data

import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar

/** Where an episode got to. */
@Serializable
data class EpisodeState(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val watched: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One title in the library: on the watchlist (the Queue), part-watched, watched, or any of them.
 * Shows keep per-episode state keyed "season:number".
 */
@Serializable
data class LibraryItem(
    val key: String,
    val isTv: Boolean,
    val id: String,
    val title: String,
    val poster: String? = null,
    val banner: String? = null,
    val overview: String? = null,
    val year: String? = null,
    val rating: Double? = null,
    val inQueue: Boolean = false,
    val queuedAt: Long = 0,
    val watched: Boolean = false,
    val watchedAt: Long = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val reaction: String? = null,
    val episodes: Map<String, EpisodeState> = emptyMap(),
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** The episode last played, if a show. */
    val lastEpisode: EpisodeState? get() = episodes.values.maxByOrNull { it.updatedAt }

    val inProgress: Boolean
        get() = if (isTv) episodes.isNotEmpty() && !watched else positionMs > 60_000 && !watched
}

@Serializable
private data class LibraryFile(val items: List<LibraryItem> = emptyList(), val searches: List<String> = emptyList())

/** The desktop library, saved as JSON in the data folder a moment after each change. */
@OptIn(FlowPreview::class)
object Library {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val file = File(StreamFlixApp.instance.dataDir, "library.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<Map<String, LibraryItem>>(emptyMap())
    val items: StateFlow<Map<String, LibraryItem>> = _items

    private val _searches = MutableStateFlow<List<String>>(emptyList())
    val searches: StateFlow<List<String>> = _searches

    init {
        runCatching {
            if (file.exists()) {
                val data = json.decodeFromString<LibraryFile>(file.readText())
                _items.value = data.items.associateBy { it.key }
                _searches.value = data.searches
            }
        }
        _items.drop(1).debounce(400).onEach { save() }.launchIn(scope)
        _searches.drop(1).debounce(400).onEach { save() }.launchIn(scope)
    }

    @Synchronized
    private fun save() {
        runCatching {
            val tmp = File(file.parentFile, "library.json.tmp")
            tmp.writeText(json.encodeToString(LibraryFile.serializer(), LibraryFile(_items.value.values.toList(), _searches.value)))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    fun flush() = save()

    fun key(isTv: Boolean, id: String) = if (isTv) "t:$id" else "m:$id"

    operator fun get(key: String): LibraryItem? = _items.value[key]

    private fun year(cal: Calendar?) = cal?.get(Calendar.YEAR)?.toString()

    fun itemFor(movie: Movie) = _items.value[key(false, movie.id)] ?: LibraryItem(
        key = key(false, movie.id), isTv = false, id = movie.id, title = movie.title,
        poster = movie.poster, banner = movie.banner, overview = movie.overview, year = year(movie.released), rating = movie.rating,
    )

    fun itemFor(show: TvShow) = _items.value[key(true, show.id)] ?: LibraryItem(
        key = key(true, show.id), isTv = true, id = show.id, title = show.title,
        poster = show.poster, banner = show.banner, overview = show.overview, year = year(show.released), rating = show.rating,
    )

    /** Refreshes artwork/metadata and applies [change]. */
    fun edit(base: LibraryItem, change: (LibraryItem) -> LibraryItem) {
        _items.update { map ->
            val current = map[base.key]?.let {
                it.copy(
                    title = base.title.ifBlank { it.title },
                    poster = base.poster ?: it.poster,
                    banner = base.banner ?: it.banner,
                    overview = base.overview ?: it.overview,
                    year = base.year ?: it.year,
                    rating = base.rating ?: it.rating,
                )
            } ?: base
            val next = change(current)
            if (!next.inQueue && !next.watched && next.positionMs == 0L && next.episodes.isEmpty() && next.reaction == null) map - base.key
            else map + (base.key to next)
        }
    }

    fun setInQueue(base: LibraryItem, inQueue: Boolean) =
        edit(base) { it.copy(inQueue = inQueue, queuedAt = if (inQueue) System.currentTimeMillis() else 0) }

    fun setWatched(base: LibraryItem, watched: Boolean) = edit(base) {
        it.copy(watched = watched, watchedAt = if (watched) System.currentTimeMillis() else 0, positionMs = if (watched) 0 else it.positionMs)
    }

    fun setReaction(base: LibraryItem, reaction: String?) = edit(base) { it.copy(reaction = reaction) }

    /** Saves a movie's playback position; past 92 % it counts as watched. */
    fun saveMovieProgress(base: LibraryItem, positionMs: Long, durationMs: Long) = edit(base) {
        val done = durationMs > 0 && positionMs > durationMs * 0.92
        it.copy(
            positionMs = if (done) 0 else positionMs,
            durationMs = durationMs,
            lastPlayedAt = System.currentTimeMillis(),
            watched = it.watched || done,
            watchedAt = if (done) System.currentTimeMillis() else it.watchedAt,
        )
    }

    fun saveEpisodeProgress(base: LibraryItem, episode: EpisodeState, positionMs: Long, durationMs: Long) = edit(base) {
        val done = durationMs > 0 && positionMs > durationMs * 0.92
        val key = "${episode.season}:${episode.number}"
        val prev = it.episodes[key]
        val next = episode.copy(
            positionMs = if (done) 0 else positionMs,
            durationMs = durationMs,
            watched = (prev?.watched ?: false) || done,
            updatedAt = System.currentTimeMillis(),
        )
        it.copy(episodes = it.episodes + (key to next), lastPlayedAt = System.currentTimeMillis())
    }

    fun setEpisodeWatched(base: LibraryItem, episode: EpisodeState, watched: Boolean) = edit(base) {
        val key = "${episode.season}:${episode.number}"
        val prev = it.episodes[key] ?: episode
        it.copy(episodes = it.episodes + (key to prev.copy(watched = watched, positionMs = 0, updatedAt = System.currentTimeMillis())))
    }

    fun episode(showId: String, season: Int, number: Int): EpisodeState? = _items.value[key(true, showId)]?.episodes?.get("$season:$number")

    fun addSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        _searches.update { (listOf(q) + it.filterNot { s -> s.equals(q, ignoreCase = true) }).take(12) }
    }

    fun clearSearches() { _searches.value = emptyList() }
}
