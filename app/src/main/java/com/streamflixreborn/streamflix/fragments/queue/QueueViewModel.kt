package com.streamflixreborn.streamflix.fragments.queue

import com.streamflixreborn.streamflix.utils.FavoriteSync
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
        val sort: QueueSort,
        val rating: RatingFilter,
        val visible: List<QueueEntry>,
    )

    private val section = MutableStateFlow(Section.WATCHLIST)
    private val filter = MutableStateFlow(Filter.ALL)
    private val sort = MutableStateFlow(QueueSort.RECENT)
    private val rating = MutableStateFlow<RatingFilter>(RatingFilter.Everything)

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

    val state: StateFlow<UiState> = combine(lists, section, filter, sort, rating) { lists, section, filter, sort, rating ->
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
            } && (section != Section.WATCHED || rating.matches(it))
        }
        // Up next always follows what you touched last.
        val sorted = if (section == Section.UP_NEXT) filtered else QueueSorting.sort(filtered, sort)
        UiState(lists, section, filter, sort, rating, sorted)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UiState(Lists(), Section.WATCHLIST, Filter.ALL, QueueSort.RECENT, RatingFilter.Everything, emptyList()),
    )

    fun setSection(value: Section) { section.value = value }
    fun setFilter(value: Filter) { filter.value = value }
    fun setSort(value: QueueSort) { sort.value = value }
    fun setRating(value: RatingFilter) { rating.value = value }

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
            val allEpisodes = database.episodeDao().getByTvShowId(showId)
            val seasonEpisodes = allEpisodes.filter { it.season?.id == ep.season?.id }.sortedBy { it.number }
            upNext += QueueEntry.of(show, reactions, inQueue = showId in favShowIds).copy(
                episode = episode,
                seasonNumber = season?.number,
                episodeNumber = ep.number,
                episodesLeft = allEpisodes.count { !it.isWatched },
                seasonWatched = seasonEpisodes.map { it.isWatched }.takeIf { it.isNotEmpty() },
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
                val knownMovie = if (isTv) null else database.movieDao().getById(id)
                add(
                    QueueEntry(
                        key = s.key,
                        isTv = isTv,
                        id = id,
                        title = s.title,
                        poster = s.poster,
                        year = s.year,
                        rating = s.rating ?: if (isTv) shows[id]?.rating else knownMovie?.rating,
                        releasedMillis = s.releasedMillis
                            ?: if (isTv) shows[id]?.released?.timeInMillis else knownMovie?.released?.timeInMillis,
                        reaction = reactions[s.key],
                        watched = true,
                        sortTime = s.atMillis,
                        movie = knownMovie,
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
            ArtworkRepair.resolveTvShowForFavorite(context, show, inQueue).let {
                database.tvShowDao().upsertFavorite(it, inQueue)
                FavoriteSync.tvShow(context, it, inQueue)
            }
        } else {
            val movie = entry.movie ?: Movie(id = entry.id, title = entry.title, poster = entry.poster)
            ArtworkRepair.resolveMovieForFavorite(context, movie, inQueue).let {
                database.movieDao().upsertFavorite(it, inQueue)
                FavoriteSync.movie(context, it, inQueue)
            }
        }
    }

    /** The tick in Up next: the shown episode (or film) counts as watched, so the next one comes up. */
    fun markNextWatched(context: Context, entry: QueueEntry) = viewModelScope.launch(Dispatchers.IO) {
        val item = entry.episode ?: entry.movie ?: return@launch
        WatchStateActions.setWatched(context, item, true)
    }

    fun setReaction(entry: QueueEntry, reaction: Reaction?) {
        QueueStore.setReaction(entry.key, reaction)
        // Reacting means you've seen it.
        if (reaction != null && !entry.watched) {
            QueueStore.setSeen(entry.key, entry.title, entry.poster, entry.year, true, entry.releasedMillis, entry.rating)
        }
    }

    fun setWatched(context: Context, entry: QueueEntry, watched: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val movie = entry.movie
        if (!entry.isTv && movie != null) {
            WatchStateActions.setWatched(context, movie, watched)
        }
        QueueStore.setSeen(entry.key, entry.title, entry.poster, entry.year, watched, entry.releasedMillis, entry.rating)
    }

    // ---- Calendar ------------------------------------------------------------------------------

    data class Upcoming(
        val entry: QueueEntry,
        val date: Calendar,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val episodeTitle: String?,
    )

    private data class UpcomingCache(val at: Long, val signature: Set<String>, val items: List<Upcoming>)

    private var upcomingCache: UpcomingCache? = null

    /**
     * Upcoming episodes (the next few of the season on air) of the shows in your Queue, and Watchlist
     * films not out yet, soonest first. Cached for a while, but not across changes to the Queue.
     */
    suspend fun upcoming(): List<Upcoming> = withContext(Dispatchers.IO) {
        val lists = state.value.lists
        val signature = (lists.upNext + lists.watchlist + lists.watched).map { it.key }.toSet()
        upcomingCache
            ?.takeIf { System.currentTimeMillis() - it.at < 30 * 60_000L && it.signature == signature }
            ?.let { return@withContext it.items }
        val provider = UserPreferences.currentProvider
        val language = provider?.language
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // Shows you are following come first; finished shows can still return with a new season.
        val shows = (lists.upNext + lists.watchlist + lists.watched).filter { it.isTv }.distinctBy { it.key }.take(60)
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
                            } ?: return@runCatching emptyList<Upcoming>()
                            val next = TMDb3.TvSeries.details(seriesId = tmdbId, language = language).nextEpisodeToAir
                                ?: return@runCatching emptyList<Upcoming>()
                            val firstDate = parseDate(next.airDate) ?: return@runCatching emptyList<Upcoming>()
                            val first = Upcoming(entry, firstDate, next.seasonNumber, next.episodeNumber, next.name)
                            // The rest of that season that has an air date, so a weekly show lists several weeks.
                            val more = runCatching {
                                TMDb3.TvSeasons.details(seriesId = tmdbId, seasonNumber = next.seasonNumber, language = language)
                                    .episodes.orEmpty()
                                    .filter { it.episodeNumber > next.episodeNumber }
                                    .mapNotNull { ep ->
                                        parseDate(ep.airDate)?.let { Upcoming(entry, it, ep.seasonNumber, ep.episodeNumber, ep.name) }
                                    }
                                    .take(MAX_EPISODES_PER_SHOW - 1)
                            }.getOrDefault(emptyList())
                            (listOf(first) + more).filter { !it.date.before(today) }
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
        }
        val films = lists.watchlist.filter { !it.isTv }.mapNotNull { entry ->
            val released = entry.releasedMillis ?: return@mapNotNull null
            if (released < today.timeInMillis) return@mapNotNull null
            Upcoming(entry, Calendar.getInstance().apply { timeInMillis = released }, null, null, null)
        }
        val result = (episodes + films).sortedBy { it.date.timeInMillis }
        upcomingCache = UpcomingCache(System.currentTimeMillis(), signature, result)
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

    /** Where imported titles go: the Watchlist, or straight to Watched with this rating. */
    sealed interface ImportTarget {
        data object Watchlist : ImportTarget
        data class Rated(val reaction: Reaction) : ImportTarget
    }

    /**
     * Looks each pasted line up with the current provider and adds the best match to [target]:
     * the Watchlist, or Watched with the chosen rating (so a list of favourites can be brought in
     * as "Loved", then another as "Liked").
     */
    suspend fun import(
        context: Context,
        text: String,
        target: ImportTarget = ImportTarget.Watchlist,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ImportResult =
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
                        if (target is ImportTarget.Rated) {
                            markRated(QueueStore.key(false, match.id), match.title, match.poster ?: match.banner, match.released?.format("yyyy"), target.reaction, match.released?.timeInMillis, match.rating)
                        } else {
                            ArtworkRepair.resolveMovieForFavorite(context, match, true).let {
                                database.movieDao().upsertFavorite(it, true)
                                FavoriteSync.movie(context, it, true)
                            }
                        }
                        added += match.title
                    }
                    is TvShow -> {
                        if (target is ImportTarget.Rated) {
                            markRated(QueueStore.key(true, match.id), match.title, match.poster ?: match.banner, match.released?.format("yyyy"), target.reaction, match.released?.timeInMillis, match.rating)
                        } else {
                            ArtworkRepair.resolveTvShowForFavorite(context, match, true).let {
                                database.tvShowDao().upsertFavorite(it, true)
                                FavoriteSync.tvShow(context, it, true)
                            }
                        }
                        added += match.title
                    }
                    else -> notFound += title
                }
            }
            withContext(Dispatchers.Main) { onProgress(lines.size, lines.size) }
            ImportResult(added, notFound)
        }

    private fun markRated(
        key: String,
        title: String,
        poster: String?,
        year: String?,
        reaction: Reaction,
        releasedMillis: Long? = null,
        rating: Double? = null,
    ) {
        QueueStore.setSeen(key, title, poster, year, true, releasedMillis, rating)
        QueueStore.setReaction(key, reaction)
    }

    /** "1. The Bear (2022)" → ("The Bear", 2022). */
    private fun cleanLine(raw: String): Pair<String, Int?> {
        var line = raw.trim()
            .replace(Regex("^([-*•▪◦·]|\\d+[.)]|\\[[ xX]?\\])\\s*"), "")
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
        private const val MAX_EPISODES_PER_SHOW = 4
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
