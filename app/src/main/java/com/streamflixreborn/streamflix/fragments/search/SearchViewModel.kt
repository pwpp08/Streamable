package com.streamflixreborn.streamflix.fragments.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.ParentalControlUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.utils.MetadataLanguage
import com.streamflixreborn.streamflix.utils.TMDb3
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

// DEFINICIONES DE ESTADO Y RESULTADOS (Fuera de la clase para mejor acceso)
sealed class State {
    data object Searching : State()
    data object SearchingMore : State()
    data class SuccessSearching(val results: List<AppAdapter.Item>, val hasMore: Boolean) : State()
    data class FailedSearching(val error: Exception) : State()
}


class SearchViewModel(private val database: AppDatabase) : ViewModel() {

    companion object {
        /** Category names that start with this hold the title the row is based on. */
        const val BECAUSE_PREFIX = "because:"
        private const val RECOMMENDATION_SEEDS = 2
        private const val MIN_ROW_ITEMS = 4
        private const val HOME_ROWS = 3
    }

    private val _state = MutableStateFlow<State>(State.Searching)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessSearching -> {
                    val movies = state.results
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.movieDao().getByIds(movies.map { it.id }))
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        },
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessSearching -> {
                    val tvShows = state.results
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.tvShowDao().getByIds(tvShows.map { it.id }))
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, moviesDb, tvShowsDb ->
        when (state) {
            is State.SuccessSearching -> {
                val moviesById = moviesDb.associateBy { it.id }
                val tvShowsById = tvShowsDb.associateBy { it.id }

                State.SuccessSearching(
                    results = state.results.map { item ->
                        when (item) {
                            is Movie -> moviesById[item.id]
                                ?.takeIf { !item.isSame(it) }
                                ?.let { item.copy().merge(it) }
                                ?: item
                            is TvShow -> tvShowsById[item.id]
                                ?.takeIf { !item.isSame(it) }
                                ?.let { item.copy().merge(it) }
                                ?: item
                            else -> item
                        }
                    },
                    hasMore = state.hasMore
                )
            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    var query = ""
    private var page = 1

    // --- Recent searches (per profile) and TMDb suggestions ---

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private val typed = MutableStateFlow("")

    /** Suggestions and the text they were fetched for. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val suggestions: StateFlow<Pair<String, List<SearchSuggestion>>> = typed
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce(SearchSuggestion.DEBOUNCE_MS)
        .mapLatest { text ->
            if (text.length < SearchSuggestion.MIN_CHARS || !suggestionsEnabled()) {
                text to emptyList()
            } else {
                text to try {
                    SearchSuggestion.fromTmdb(
                        TMDb3.Search.multi(text, includeAdult = false, language = MetadataLanguage.get()).results
                    )
                } catch (e: Exception) {
                    Log.w("SearchViewModel", "suggestions: ", e)
                    emptyList()
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, "" to emptyList())

    fun onTextChanged(text: String) {
        typed.value = text
    }

    fun clearHistory() {
        UserPreferences.searchHistory = null
        _history.value = emptyList()
    }

    private fun recordSearch(query: String) {
        if (query.isBlank()) return
        val updated = SearchHistory.add(_history.value, query)
        _history.value = updated
        runCatching { UserPreferences.searchHistory = SearchHistory.toJson(updated) }
    }

    private fun loadHistory(): List<String> =
        runCatching { SearchHistory.parse(UserPreferences.searchHistory) }.getOrDefault(emptyList())

    /** TMDb is on, a key exists and the provider is not IPTV (channel names are not on TMDb). */
    private fun suggestionsEnabled(): Boolean =
        UserPreferences.enableTmdb &&
            (UserPreferences.tmdbApiKey.isNotBlank() || BuildConfig.TMDB_API_KEY.let { it.isNotBlank() && it != "null" }) &&
            UserPreferences.currentProvider !is IptvProvider

    // --- Recommendations (shown while the search box is empty) ---

    private val _recommendations = MutableStateFlow<List<Category>?>(null)
    /** null while loading; rows of recommended titles afterwards (possibly empty). */
    val recommendations: StateFlow<List<Category>?> = _recommendations.asStateFlow()

    init {
        search(query)
        loadRecommendations()
    }

    /**
     * "Because you watched X" rows from what was played or favourited most recently, then the
     * provider's own trending/popular rows. Parental filters apply to every row.
     */
    fun loadRecommendations() = viewModelScope.launch(Dispatchers.IO) {
        _recommendations.value = try {
            val provider = UserPreferences.currentProvider ?: run { _recommendations.value = emptyList(); return@launch }
            val rows = mutableListOf<Category>()

            val seeds = (
                database.movieDao().getRecentlyWatched().first() +
                    database.tvShowDao().getRecentlyWatched().first() +
                    database.movieDao().getFavorites().first() +
                    database.tvShowDao().getFavorites().first()
                ).distinctBy { (it as? Movie)?.id ?: (it as TvShow).id }
                .take(RECOMMENDATION_SEEDS)

            val becauseRows = kotlinx.coroutines.coroutineScope {
                seeds.map { seed ->
                    async {
                        runCatching {
                            when (seed) {
                                is Movie -> seed.title to provider.getMovie(seed.id).recommendations
                                is TvShow -> seed.title to provider.getTvShow(seed.id).recommendations
                                else -> null
                            }
                        }.getOrNull()
                    }
                }.awaitAll()
            }
            becauseRows.filterNotNull().forEach { (title, shows) ->
                val list = ParentalControlUtils.filterItems(shows.filter { it is Movie || it is TvShow })
                if (list.size >= MIN_ROW_ITEMS) rows += Category(name = BECAUSE_PREFIX + title, list = list)
            }

            runCatching { provider.getHome() }.getOrNull()
                ?.filter { it.name != Category.FEATURED && it.list.any { item -> item is Movie || item is TvShow } }
                ?.take(HOME_ROWS)
                ?.forEach { row ->
                    val list = ParentalControlUtils.filterItems(row.list.filter { it is Movie || it is TvShow })
                    if (list.isNotEmpty()) rows += Category(name = row.name, list = list)
                }
            rows
        } catch (e: Exception) {
            Log.w("SearchViewModel", "recommendations: ", e)
            emptyList()
        }
    }

    fun search(query: String) = viewModelScope.launch(Dispatchers.IO) {
        // Set up front so Retry after a failure repeats this query instead of the previous one.
        this@SearchViewModel.query = query
        if (query.isBlank()) {
            // Nothing to look up: the screen shows recommendations instead of the provider's genre list.
            page = 1
            _state.emit(State.SuccessSearching(emptyList(), false))
            return@launch
        }
        _state.emit(State.Searching)
        recordSearch(query)

        try {
            val results = ParentalControlUtils.filterItems(UserPreferences.currentProvider!!.search(query))
            page = 1
            _state.emit(State.SuccessSearching(results, results.isNotEmpty()))
        } catch (e: Exception) {
            Log.e("SearchViewModel", "search: ", e)
            _state.emit(State.FailedSearching(e))
        }
    }

    fun loadMore() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessSearching) {
            _state.emit(State.SearchingMore)
            try {
                val results = ParentalControlUtils.filterItems(
                    UserPreferences.currentProvider!!.search(query, page + 1)
                )
                val existingKeys = currentState.results
                    .asSequence()
                    .map { it.searchIdentityKey() }
                    .toHashSet()
                val newUniqueResults = results.filterNot { it.searchIdentityKey() in existingKeys }
                page += 1
                _state.emit(
                    State.SuccessSearching(
                        results = currentState.results + newUniqueResults,
                        hasMore = newUniqueResults.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("SearchViewModel", "loadMore: ", e)
                _state.emit(State.FailedSearching(e))
            }
        }
    }
}

private fun AppAdapter.Item.searchIdentityKey(): String = when (this) {
    is Movie -> "movie:$id"
    is TvShow -> "tvshow:$id"
    else -> "${this::class.java.name}:${hashCode()}"
}
