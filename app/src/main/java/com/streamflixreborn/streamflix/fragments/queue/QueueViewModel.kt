package com.streamflixreborn.streamflix.fragments.queue

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.fragments.queue.QueueStore.Reaction
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.recommend.Recommender
import com.streamflixreborn.streamflix.utils.ArtworkRepair
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.WatchStateActions
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Calendar

/**
 * The Queue tab: what you're in the middle of (Up next), what you want to watch (Watchlist, which
 * is the old favorites), and what you've seen (Watched, with your reaction). Everything comes from
 * the provider's Room database plus [QueueStore].
 */
class QueueViewModel(private val database: AppDatabase) : ViewModel() {

    enum class Section { UP_NEXT, WATCHLIST, WATCHED }
    enum class Filter { ALL, MOVIES, SHOWS }
    enum class Sort { RECENT, TITLE, RELEASE, RATING }

    data class Lists(
        val upNext: List<QueueEntry> = emptyList(),
        val watchlist: List<QueueEntry> = emptyList(),
        val watched: List<QueueEntry> = emptyList(),
        val loaded: Boolean = false,
    )

    data class UiState(
        val lists: Lists,
        val section: Section,
        val filter: Filter,
        val sort: Sort,
        val visible: List<QueueEntry>,
    )

    private val section = MutableStateFlow(Section.WATCHLIST)
    private val filter = MutableStateFlow(Filter.ALL)
    private val sort = MutableStateFlow(Sort.RECENT)

    private data class Sources(
        val favMovies: List<Movie>,
        val favShows: List<TvShow>,
        val watchingMovies: List<Movie>,
        val watchedMovies: List<Movie>,
        val watchedShows: List<TvShow>,
    )

    private val sources: Flow<Sources> = combine(
        database.movieDao().getFavorites(),
        database.tvShowDao().getFavorites(),
        database.movieDao().getWatchingMovies(),
        database.movieDao().getWatched(),
        database.tvShowDao().getFullyWatched(),
    ) { favMovies, favShows, watchingMovies, watchedMovies, watchedShows ->
        Sources(favMovies, favShows, watchingMovies, watchedMovies, watchedShows)
    }

    private val lists: Flow<Lists> = combine(
        sources,
        database.episodeDao().getWatchingEpisodes(),
        database.episodeDao().getNextEpisodesToWatch(),
        database.tvShowDao().getAll(),
        QueueStore.revision,
    ) { src, watchingEpisodes, nextEpisodes, allShows, _ ->
        build(src, watchingEpisodes, nextEpisodes, allShows)
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<UiState> = combine(lists, section, filter, sort) { lists, section, filter, sort ->
        val base = when (section) {
            Section.UP_NEXT -> lists.upNext
            Section.WATCHLIST -> lists.watchlist
            Section.WATCHED -> lists.watched
        }
        val filtered = base.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.MOVIES -> !it.isTv
                Filter.SHOWS -> it.isTv
            }
        }
        // Up next always follows what you touched last.
        val sorted = if (section == Section.UP_NEXT) filtered else sorted(filtered, sort)
        UiState(lists, section, filter, sort, sorted)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(Lists(), Section.WATCHLIST, Filter.ALL, Sort.RECENT, emptyList()),
    )

    fun setSection(value: Section) { section.value = value }
    fun setFilter(value: Filter) { filter.value = value }
    fun setSort(value: Sort) { sort.value = value }

    private fun sorted(items: List<QueueEntry>, sort: Sort): List<QueueEntry> = when (sort) {
        Sort.RECENT -> items.sortedByDescending { it.sortTime }
        Sort.TITLE -> items.sortedBy { it.title.lowercase() }
        Sort.RELEASE -> items.sortedByDescending { it.releasedMillis ?: Long.MIN_VALUE }
        Sort.RATING -> items.sortedByDescending { it.rating ?: -1.0 }
    }

    private fun build(
        src: Sources,
        watchingEpisodes: List<Episode>,
        nextEpisodes: List<Episode>,
        allShows: List<TvShow>,
    ): Lists {
        val shows = allShows.associateBy { it.id }
        val reactions = QueueStore.reactions()
        val seen = QueueStore.seen()
        val seasonIds = (watchingEpisodes + nextEpisodes).mapNotNull { it.season?.id }.distinct()
        val seasons = if (seasonIds.isEmpty()) emptyMap() else database.seasonDao().getByIds(seasonIds).associateBy { it.id }
        val favMovieIds = src.favMovies.map { it.id }.toSet()
        val favShowIds = src.favShows.map { it.id }.toSet()

        val watchedKeys = buildSet {
            src.watchedMovies.forEach { add(QueueStore.key(false, it.id)) }
            src.watchedShows.forEach { add(QueueStore.key(true, it.id)) }
            seen.forEach { add(it.key) }
        }

        // Up next: films part-way through, and per show the episode in progress or the next one.
        val upNext = mutableListOf<QueueEntry>()
        src.watchingMovies
            .filter { !it.isWatched && QueueStore.key(false, it.id) !in watchedKeys }
            .forEach { movie ->
                val history = movie.watchHistory
                upNext += QueueEntry.of(movie, reactions, inQueue = movie.id in favMovieIds).copy(
                    progress = history?.let { percent(it.lastPlaybackPositionMillis, it.durationMillis) },
                    remainingMs = history?.let { (it.durationMillis - it.lastPlaybackPositionMillis).coerceAtLeast(0) },
                    sortTime = history?.lastEngagementTimeUtcMillis ?: 0L,
                )
            }
        val episodesByShow = LinkedHashMap<String, Pair<Episode, Boolean>>()
        watchingEpisodes.forEach { ep ->
            val showId = ep.tvShow?.id ?: return@forEach
            if (showId !in episodesByShow) episodesByShow[showId] = ep to true
        }
        nextEpisodes.forEach { ep ->
            val showId = ep.tvShow?.id ?: return@forEach
            if (showId !in episodesByShow) episodesByShow[showId] = ep to false
        }
        episodesByShow.forEach { (showId, pair) ->
            val (ep, inProgress) = pair
            val show = shows[showId] ?: ep.tvShow ?: return@forEach
            if (QueueStore.key(true, showId) in watchedKeys) return@forEach
            val season = ep.season?.id?.let { seasons[it] } ?: ep.season
            val episode = ep.copy(tvShow = show, season = season)
            val history = ep.watchHistory
            upNext += QueueEntry.of(show, reactions, inQueue = showId in favShowIds).copy(
                episode = episode,
                seasonNumber = season?.number,
                episodeNumber = ep.number,
                progress = if (inProgress) history?.let { percent(it.lastPlaybackPositionMillis, it.durationMillis) } else null,
                sortTime = history?.lastEngagementTimeUtcMillis
                    ?: ep.watchedDate?.timeInMillis
                    ?: show.lastPlayedAtMillis
                    ?: 0L,
            )
        }
        upNext.sortByDescending { it.sortTime }
        val upNextKeys = upNext.map { it.key }.toSet()

        val watchlist = buildList {
            src.favMovies.forEach { movie ->
                val key = QueueStore.key(false, movie.id)
                if (key !in watchedKeys && key !in upNextKeys) add(QueueEntry.of(movie, reactions, inQueue = true))
            }
            src.favShows.forEach { show ->
                val key = QueueStore.key(true, show.id)
                if (key !in watchedKeys && key !in upNextKeys) add(QueueEntry.of(show, reactions, inQueue = true))
            }
        }

        val watched = buildList {
            val added = mutableSetOf<String>()
            src.watchedMovies.forEach { movie ->
                val entry = QueueEntry.of(movie, reactions, inQueue = movie.id in favMovieIds)
                    .copy(watched = true, sortTime = movie.watchedDate?.timeInMillis ?: 0L)
                if (added.add(entry.key)) add(entry)
            }
            src.watchedShows.forEach { show ->
                val entry = QueueEntry.of(show, reactions, inQueue = show.id in favShowIds)
                    .copy(watched = true, sortTime = show.lastPlayedAtMillis ?: 0L)
                if (added.add(entry.key)) add(entry)
            }
            seen.forEach { s ->
                if (!added.add(s.key)) return@forEach
                val isTv = s.key.startsWith("t:")
                val id = s.key.substringAfter(':')
                add(
                    QueueEntry(
                        key = s.key,
                        isTv = isTv,
                        id = id,
                        title = s.title,
                        poster = s.poster,
                        year = s.year,
                        reaction = reactions[s.key],
                        watched = true,
                        sortTime = s.atMillis,
                        movie = if (isTv) null else database.movieDao().getById(id),
                        tvShow = if (isTv) shows[id] else null,
                    ),
                )
            }
        }

        return Lists(upNext = upNext, watchlist = watchlist, watched = watched, loaded = true)
    }

    private fun percent(position: Long, duration: Long): Int? =
        if (duration <= 0) null else ((position * 100) / duration).toInt().coerceIn(0, 100)

    // ---- Actions -------------------------------------------------------------------------------

    fun setInQueue(context: Context, entry: QueueEntry, inQueue: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (entry.isTv) {
            val show = entry.tvShow ?: TvShow(id = entry.id, title = entry.title, poster = entry.poster)
            database.tvShowDao().upsertFavorite(ArtworkRepair.resolveTvShowForFavorite(context, show, inQueue), inQueue)
        } else {
            val movie = entry.movie ?: Movie(id = entry.id, title = entry.title, poster = entry.poster)
            database.movieDao().upsertFavorite(ArtworkRepair.resolveMovieForFavorite(context, movie, inQueue), inQueue)
        }
    }

    fun setReaction(entry: QueueEntry, reaction: Reaction?) {
        QueueStore.setReaction(entry.key, reaction)
        // Reacting means you've seen it.
        if (reaction != null && !entry.watched) QueueStore.setSeen(entry.key, entry.title, entry.poster, entry.year, true)
    }

    fun setWatched(context: Context, entry: QueueEntry, watched: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val movie = entry.movie
        if (!entry.isTv && movie != null) {
            WatchStateActions.setWatched(context, movie, watched)
        }
        QueueStore.setSeen(entry.key, entry.title, entry.poster, entry.year, watched)
    }

    /** Adds a title found while swiping; [seen] puts it under Watched instead. */
    fun addFromSwipe(context: Context, item: AppAdapter.Item, seen: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        when (item) {
            is Movie -> if (seen) {
                QueueStore.setSeen(QueueStore.key(false, item.id), item.title, item.poster, item.released?.format("yyyy"), true)
            } else {
                database.movieDao().upsertFavorite(ArtworkRepair.resolveMovieForFavorite(context, item, true), true)
            }
            is TvShow -> if (seen) {
                QueueStore.setSeen(QueueStore.key(true, item.id), item.title, item.poster, item.released?.format("yyyy"), true)
            } else {
                database.tvShowDao().upsertFavorite(ArtworkRepair.resolveTvShowForFavorite(context, item, true), true)
            }
            else -> Unit
        }
    }

    // ---- Swipe ---------------------------------------------------------------------------------

    /** Titles to swipe through: recommendations first (TMDb providers), then the provider's home rows. */
    suspend fun swipeCandidates(context: Context): List<AppAdapter.Item> = withContext(Dispatchers.IO) {
        val provider = UserPreferences.currentProvider ?: return@withContext emptyList()
        val known = state.value.lists.let { l -> (l.upNext + l.watchlist + l.watched).map { it.key }.toSet() } +
            QueueSwipeMemory.skipped(context)
        val recommended = if (provider is TmdbProvider) {
            Recommender.category(context, "queue-swipe", provider.language)?.list.orEmpty()
        } else {
            emptyList()
        }
        val home = runCatching { provider.getHome() }
            .onFailure { Log.w(TAG, "swipe: home failed: ${it.message}") }
            .getOrNull().orEmpty()
            .flatMap { it.list }
            .shuffled()
        (recommended + home)
            .filter { it is Movie || it is TvShow }
            .distinctBy { keyOf(it) }
            .filter { keyOf(it) !in known && posterOf(it) != null }
            .take(60)
    }

    fun keyOf(item: AppAdapter.Item): String = when (item) {
        is Movie -> QueueStore.key(false, item.id)
        is TvShow -> QueueStore.key(true, item.id)
        else -> ""
    }

    private fun posterOf(item: AppAdapter.Item): String? = when (item) {
        is Movie -> item.poster ?: item.banner
        is TvShow -> item.poster ?: item.banner
        else -> null
    }

    // ---- Calendar ------------------------------------------------------------------------------

    data class Upcoming(
        val entry: QueueEntry,
        val date: Calendar,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val episodeTitle: String?,
    )

    private var upcomingCache: Pair<Long, List<Upcoming>>? = null

    /** Next episodes of the shows in your Queue, and Watchlist films not out yet, soonest first. */
    suspend fun upcoming(): List<Upcoming> = withContext(Dispatchers.IO) {
        upcomingCache?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.let { return@withContext it.second }
        val provider = UserPreferences.currentProvider
        val language = provider?.language
        val lists = state.value.lists
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val shows = (lists.upNext + lists.watchlist + lists.watched).filter { it.isTv }.distinctBy { it.key }.take(40)
        val gate = Semaphore(6)
        val episodes = coroutineScope {
            shows.map { entry ->
                async {
                    gate.withPermit {
                        runCatching {
                            val tmdbId = if (provider is TmdbProvider) {
                                entry.id.toIntOrNull()
                            } else {
                                TmdbUtils.getTvShow(entry.title, entry.year?.toIntOrNull(), language)?.id?.toIntOrNull()
                            } ?: return@runCatching null
                            val next = TMDb3.TvSeries.details(seriesId = tmdbId, language = language).nextEpisodeToAir
                                ?: return@runCatching null
                            val date = parseDate(next.airDate) ?: return@runCatching null
                            if (date.before(today)) return@runCatching null
                            Upcoming(entry, date, next.seasonNumber, next.episodeNumber, next.name)
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val films = lists.watchlist.filter { !it.isTv }.mapNotNull { entry ->
            val released = entry.releasedMillis ?: return@mapNotNull null
            if (released < today.timeInMillis) return@mapNotNull null
            Upcoming(entry, Calendar.getInstance().apply { timeInMillis = released }, null, null, null)
        }
        val result = (episodes + films).sortedBy { it.date.timeInMillis }
        upcomingCache = System.currentTimeMillis() to result
        result
    }

    private fun parseDate(value: String?): Calendar? {
        val parts = value?.split('-')?.mapNotNull { it.toIntOrNull() } ?: return null
        if (parts.size < 3) return null
        return Calendar.getInstance().apply {
            clear()
            set(parts[0], parts[1] - 1, parts[2])
        }
    }

    // ---- Import --------------------------------------------------------------------------------

    data class ImportResult(val added: List<String>, val notFound: List<String>)

    /** Looks each pasted line up with the current provider and adds the best match to the Watchlist. */
    suspend fun import(context: Context, text: String, onProgress: (done: Int, total: Int) -> Unit): ImportResult =
        withContext(Dispatchers.IO) {
            val provider = UserPreferences.currentProvider ?: return@withContext ImportResult(emptyList(), emptyList())
            val lines = text.lines().map(::cleanLine).filter { it.first.isNotBlank() }.distinct().take(100)
            val added = mutableListOf<String>()
            val notFound = mutableListOf<String>()
            lines.forEachIndexed { index, (title, year) ->
                withContext(Dispatchers.Main) { onProgress(index, lines.size) }
                val match = runCatching { provider.search(title, 1) }.getOrNull().orEmpty()
                    .filter { it is Movie || it is TvShow }
                    .maxByOrNull { score(it, title, year) }
                    ?.takeIf { score(it, title, year) > 0 }
                when (match) {
                    is Movie -> {
                        database.movieDao().upsertFavorite(ArtworkRepair.resolveMovieForFavorite(context, match, true), true)
                        added += match.title
                    }
                    is TvShow -> {
                        database.tvShowDao().upsertFavorite(ArtworkRepair.resolveTvShowForFavorite(context, match, true), true)
                        added += match.title
                    }
                    else -> notFound += title
                }
            }
            withContext(Dispatchers.Main) { onProgress(lines.size, lines.size) }
            ImportResult(added, notFound)
        }

    /** "1. The Bear (2022)" → ("The Bear", 2022). */
    private fun cleanLine(raw: String): Pair<String, Int?> {
        var line = raw.trim()
            .replace(Regex("^([-*•▪◦·]|\\d+[.)]|\\[[ xX]?])\\s*"), "")
            .trim()
        val year = Regex("\\((\\d{4})\\)\\s*$").find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\s(\\d{4})$").find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        if (year != null) line = line.replace(Regex("\\s*\\(?$year\\)?\\s*$"), "")
        return line.trim() to year
    }

    private fun score(item: AppAdapter.Item, title: String, year: Int?): Int {
        val (itemTitle, itemYear) = when (item) {
            is Movie -> item.title to item.released?.get(Calendar.YEAR)
            is TvShow -> item.title to item.released?.get(Calendar.YEAR)
            else -> return 0
        }
        val a = normalize(itemTitle)
        val b = normalize(title)
        var score = when {
            a == b -> 10
            a.startsWith(b) || b.startsWith(a) -> 5
            a.contains(b) || b.contains(a) -> 2
            else -> 0
        }
        if (score > 0 && year != null && itemYear == year) score += 5
        return score
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    companion object {
        private const val TAG = "QueueViewModel"
    }
}

/** Titles skipped while swiping, so they don't come back (kept on this device only). */
object QueueSwipeMemory {
    private const val PREFS = "queue_swipe"
    private const val KEY = "skipped"
    private const val LIMIT = 600

    fun skipped(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    fun skip(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        prefs.edit().putString(KEY, (list + key).distinct().takeLast(LIMIT).joinToString("\n")).apply()
    }
}
