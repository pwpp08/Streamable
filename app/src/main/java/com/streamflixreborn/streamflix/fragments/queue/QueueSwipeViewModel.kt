package com.streamflixreborn.streamflix.fragments.queue

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.recommend.Recommender
import com.streamflixreborn.streamflix.utils.ArtworkRepair
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SwipeDecision { ADD, SKIP, SEEN }

/**
 * The Swipe page: a stack of suggestions (recommendations first, then the provider's home rows).
 * Lives with the page's back stack entry, so opening a title's details and coming back keeps the
 * stack and the position in it.
 */
class QueueSwipeViewModel(private val database: AppDatabase) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val items: List<AppAdapter.Item> = emptyList(),
        val index: Int = 0,
        val added: Int = 0,
    ) {
        val current get() = items.getOrNull(index)
        val next get() = items.getOrNull(index + 1)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var started = false

    fun load(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        viewModelScope.launch {
            val items = candidates(appContext)
            _state.value = UiState(loading = false, items = items)
        }
    }

    /** Applies [decision] to the current title and moves on to the next one. */
    fun decide(context: Context, decision: SwipeDecision) {
        val current = _state.value.current ?: return
        val appContext = context.applicationContext
        _state.value = _state.value.let {
            it.copy(index = it.index + 1, added = it.added + if (decision == SwipeDecision.ADD) 1 else 0)
        }
        viewModelScope.launch(Dispatchers.IO) {
            when (decision) {
                SwipeDecision.ADD -> add(appContext, current, seen = false)
                SwipeDecision.SEEN -> add(appContext, current, seen = true)
                SwipeDecision.SKIP -> QueueSwipeMemory.skip(appContext, keyOf(current))
            }
        }
    }

    private suspend fun add(context: Context, item: AppAdapter.Item, seen: Boolean) {
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

    private suspend fun candidates(context: Context): List<AppAdapter.Item> = withContext(Dispatchers.IO) {
        val provider = UserPreferences.currentProvider ?: return@withContext emptyList()
        val known = knownKeys(context)
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

    /** Everything already in the Queue (any section), seen, or skipped before. */
    private suspend fun knownKeys(context: Context): Set<String> = buildSet {
        database.movieDao().getFavorites().first().forEach { add(QueueStore.key(false, it.id)) }
        database.movieDao().getWatched().first().forEach { add(QueueStore.key(false, it.id)) }
        database.movieDao().getWatchingMovies().first().forEach { add(QueueStore.key(false, it.id)) }
        database.tvShowDao().getFavorites().first().forEach { add(QueueStore.key(true, it.id)) }
        database.tvShowDao().getFullyWatched().first().forEach { add(QueueStore.key(true, it.id)) }
        database.episodeDao().getWatchingEpisodes().first().forEach { ep -> ep.tvShow?.id?.let { add(QueueStore.key(true, it)) } }
        QueueStore.seen().forEach { add(it.key) }
        addAll(QueueSwipeMemory.skipped(context))
    }

    companion object {
        private const val TAG = "QueueSwipe"

        fun keyOf(item: AppAdapter.Item): String = when (item) {
            is Movie -> QueueStore.key(false, item.id)
            is TvShow -> QueueStore.key(true, item.id)
            else -> ""
        }

        fun posterOf(item: AppAdapter.Item): String? = when (item) {
            is Movie -> item.poster ?: item.banner
            is TvShow -> item.poster ?: item.banner
            else -> null
        }
    }
}
