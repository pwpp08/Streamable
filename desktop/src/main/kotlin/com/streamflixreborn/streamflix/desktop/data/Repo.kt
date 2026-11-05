package com.streamflixreborn.streamflix.desktop.data

import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/** The current provider plus small in-memory caches, so going back never refetches. */
object Repo {
    val provider: Provider get() = UserPreferences.currentProvider!!

    private var homeCache: Pair<String, List<Category>>? = null
    private val movies = ConcurrentHashMap<String, Movie>()
    private val shows = ConcurrentHashMap<String, TvShow>()
    private val seasons = ConcurrentHashMap<String, List<Episode>>()

    suspend fun home(force: Boolean = false): List<Category> = withContext(Dispatchers.IO) {
        val p = provider
        homeCache?.takeIf { !force && it.first == p.name }?.let { return@withContext it.second }
        p.getHome().filter { it.list.isNotEmpty() }.also { homeCache = p.name to it }
    }

    suspend fun movie(id: String): Movie = withContext(Dispatchers.IO) { movies[id] ?: provider.getMovie(id).also { movies[id] = it } }

    suspend fun show(id: String): TvShow = withContext(Dispatchers.IO) { shows[id] ?: provider.getTvShow(id).also { shows[id] = it } }

    suspend fun episodes(seasonId: String): List<Episode> = withContext(Dispatchers.IO) {
        seasons[seasonId] ?: provider.getEpisodesBySeason(seasonId).also { seasons[seasonId] = it }
    }

    suspend fun search(query: String, page: Int = 1) = withContext(Dispatchers.IO) { provider.search(query, page) }
    suspend fun movies(page: Int) = withContext(Dispatchers.IO) { provider.getMovies(page) }
    suspend fun shows(page: Int) = withContext(Dispatchers.IO) { provider.getTvShows(page) }
    suspend fun genre(id: String, page: Int) = withContext(Dispatchers.IO) { provider.getGenre(id, page) }
    suspend fun person(id: String, page: Int) = withContext(Dispatchers.IO) { provider.getPeople(id, page) }

    fun clear() {
        homeCache = null
        movies.clear()
        shows.clear()
        seasons.clear()
    }

    private fun Calendar?.ymd() = this?.let { "%04d-%02d-%02d".format(get(Calendar.YEAR), get(Calendar.MONTH) + 1, get(Calendar.DAY_OF_MONTH)) } ?: ""

    fun movieType(movie: Movie) = Video.Type.Movie(
        id = movie.id,
        title = movie.title,
        releaseDate = movie.released.ymd(),
        poster = movie.poster ?: "",
        imdbId = movie.imdbId,
    )

    fun episodeType(show: TvShow, seasonNumber: Int, seasonTitle: String?, episode: Episode) = Video.Type.Episode(
        id = episode.id,
        number = episode.number,
        title = episode.title,
        poster = episode.poster,
        overview = episode.overview,
        tvShow = Video.Type.Episode.TvShow(
            id = show.id,
            title = show.title,
            poster = show.poster,
            banner = show.banner,
            releaseDate = show.released.ymd(),
            imdbId = show.imdbId,
        ),
        season = Video.Type.Episode.Season(number = seasonNumber, title = seasonTitle),
    )
}
