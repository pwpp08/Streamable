package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.extractors.MoviesapiExtractor
import com.streamflixreborn.streamflix.extractors.TwoEmbedExtractor
import com.streamflixreborn.streamflix.extractors.VidsrcNetExtractor
import com.streamflixreborn.streamflix.extractors.VidsrcToExtractor
import com.streamflixreborn.streamflix.extractors.VidzeeExtractor
import com.streamflixreborn.streamflix.extractors.VideasyExtractor
import com.streamflixreborn.streamflix.extractors.VixSrcExtractor
import com.streamflixreborn.streamflix.extractors.VidLinkExtractor
import com.streamflixreborn.streamflix.extractors.VidsrcRuExtractor
import com.streamflixreborn.streamflix.extractors.VidflixExtractor
import com.streamflixreborn.streamflix.extractors.VidrockExtractor
import com.streamflixreborn.streamflix.extractors.VidloveExtractor
import com.streamflixreborn.streamflix.utils.health.HealthStore
import com.streamflixreborn.streamflix.extractors.VidemExtractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.torrent.TorrentServers
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.w342
import com.streamflixreborn.streamflix.utils.TMDb3.w780
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.safeSubList
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.withTimeoutOrNull

class TmdbProvider(override val language: String) : Provider {
    override val baseUrl: String
        get() = ""

    override val name = "TMDb ($language)"
    override val logo =
        "https://upload.wikimedia.org/wikipedia/commons/thumb/8/89/Tmdb.new.logo.svg/1280px-Tmdb.new.logo.svg.png"

    override suspend fun getHome(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()
        val watchRegion = if (language == "en") "US" else language.uppercase()

        val mapMulti: (TMDb3.MultiItem) -> AppAdapter.Item? = { multi ->
            when (multi) {
                is TMDb3.Movie -> Movie(
                    id = multi.id.toString(),
                    title = multi.title,
                    overview = multi.overview,
                    released = multi.releaseDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w342,
                    banner = multi.backdropPath?.w780,
                )

                is TMDb3.Tv -> TvShow(
                    id = multi.id.toString(),
                    title = multi.name,
                    overview = multi.overview,
                    released = multi.firstAirDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w342,
                    banner = multi.backdropPath?.w780,
                )

                else -> null
            }
        }

        val enabledCategories = UserPreferences.tmdbCategories

        val trendingDeferred = if ("featured" in enabledCategories || "trending" in enabledCategories) async {
            awaitAll(
                async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 1, language = language) },
                async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 2, language = language) },
                async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 3, language = language) },
            ).flatMap { it.results }
        } else null

        val popularMoviesDeferred = if ("popular_movies" in enabledCategories) async {
            awaitAll(
                async { TMDb3.MovieLists.popular(page = 1, language = language) },
                async { TMDb3.MovieLists.popular(page = 2, language = language) },
                async { TMDb3.MovieLists.popular(page = 3, language = language) },
            ).flatMap { it.results }
        } else null

        val popularTvShowsDeferred = if ("popular_tv" in enabledCategories) async {
            awaitAll(
                async { TMDb3.TvSeriesLists.popular(page = 1, language = language) },
                async { TMDb3.TvSeriesLists.popular(page = 2, language = language) },
                async { TMDb3.TvSeriesLists.popular(page = 3, language = language) },
            ).flatMap { it.results }
        } else null

        val popularAnimeDeferred = if ("popular_anime" in enabledCategories) async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        withKeywords = TMDb3.Params.WithBuilder(TMDb3.Keyword.KeywordId.ANIME)
                            .or(TMDb3.Keyword.KeywordId.BASED_ON_ANIME),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withKeywords = TMDb3.Params.WithBuilder(TMDb3.Keyword.KeywordId.ANIME)
                            .or(TMDb3.Keyword.KeywordId.BASED_ON_ANIME),
                    )
                },
            ).flatMap { it.results }
        } else null

        val netflixDeferred = if ("netflix" in enabledCategories) async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.NETFLIX),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.NETFLIX),
                    )
                },
            ).flatMap { it.results }
        } else null

        val amazonDeferred = if ("amazon" in enabledCategories) async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.AMAZON_VIDEO),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.AMAZON),
                    )
                },
            ).flatMap { it.results }
        } else null

        val disneyDeferred = if ("disney" in enabledCategories) async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.DISNEY_PLUS),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.DISNEY_PLUS),
                    )
                },
            ).flatMap { it.results }
        } else null

        val huluDeferred = if ("hulu" in enabledCategories) async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.HULU),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.HULU),
                    )
                },
            ).flatMap { it.results }
        } else null

        val appleDeferred = if ("apple" in enabledCategories) async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(TMDb3.Provider.WatchProviderId.APPLE_TV_PLUS),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.APPLE_TV),
                    )
                },
            ).flatMap { it.results }
        } else null

        val hboDeferred = if ("hbo" in enabledCategories) async {
            awaitAll(
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.HBO),
                        page = 1,
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.HBO),
                        page = 2,
                    )
                },
            ).flatMap { it.results }
        } else null

        if (trendingDeferred != null) {
            val trending = trendingDeferred.await()
            if ("featured" in enabledCategories) {
                categories.add(
                    Category(
                        name = Category.FEATURED,
                        list = trending.safeSubList(0, 20).mapNotNull(mapMulti)
                    )
                )
            }
            if ("trending" in enabledCategories) {
                categories.add(
                    Category(
                        name = getTranslation("Trending"),
                        list = trending.safeSubList(20, trending.size).mapNotNull(mapMulti)
                    )
                )
            }
        }

        popularMoviesDeferred?.await()?.let {
            categories.add(
                Category(
                    name = getTranslation("Popular Movies"),
                    list = it.mapNotNull(mapMulti)
                )
            )
        }

        popularTvShowsDeferred?.await()?.let {
            categories.add(
                Category(
                    name = getTranslation("Popular TV Shows"),
                    list = it.mapNotNull(mapMulti)
                )
            )
        }

        popularAnimeDeferred?.await()?.let {
            categories.add(
                Category(
                    name = getTranslation("Popular Anime"),
                    list = it
                        .sortedByDescending {
                            when (it) {
                                is TMDb3.Movie -> it.popularity
                                is TMDb3.Person -> it.popularity
                                is TMDb3.Tv -> it.popularity
                            }
                        }
                        .mapNotNull(mapMulti),
                )
            )
        }

        netflixDeferred?.await()?.let {
            categories.add(
                Category(
                    name = getTranslation("Popular on Netflix"),
                    list = it
                        .sortedByDescending {
                            when (it) {
                                is TMDb3.Movie -> it.popularity
                                is TMDb3.Person -> it.popularity
                                is TMDb3.Tv -> it.popularity
                            }
                        }
                        .mapNotNull(mapMulti),
                )
            )
        }

        amazonDeferred?.await()?.let {
            categories.add(
                Category(
                    name = getTranslation("Popular on Amazon"),
                    list = it
                        .sortedByDescending {
                            when (it) {
                                is TMDb3.Movie -> it.popularity
                                is TMDb3.Person -> it.popularity
                                is TMDb3.Tv -> it.popularity
                            }
                        }
                        .mapNotNull(mapMulti),
                )
            )
        }

        disneyDeferred?.await()?.let {
            categories.add(
                Category(
                    name = getTranslation("Popular on Disney+"),
                    list = it
                        .sortedByDescending {
                            when (it) {
                                is TMDb3.Movie -> it.popularity
                                is TMDb3.Person -> it.popularity
                                is TMDb3.Tv -> it.popularity
                            }
                        }
                        .mapNotNull(mapMulti),
                )
            )
        }

        huluDeferred?.await()?.let {
            categories.add(
                Category(
                    name = getTranslation("Popular on Hulu"),
                    list = it
                        .sortedByDescending {
                            when (it) {
                                is TMDb3.Movie -> it.popularity
                                is TMDb3.Person -> it.popularity
                                is TMDb3.Tv -> it.popularity
                            }
                        }
                        .mapNotNull(mapMulti),
                )
            )
        }

        appleDeferred?.await()?.let {
            categories.add(
                Category(
                    name = getTranslation("Popular on Apple TV+"),
                    list = it
                        .sortedByDescending {
                            when (it) {
                                is TMDb3.Movie -> it.popularity
                                is TMDb3.Person -> it.popularity
                                is TMDb3.Tv -> it.popularity
                            }
                        }
                        .mapNotNull(mapMulti),
                )
            )
        }

        hboDeferred?.await()?.let {
            categories.add(
                Category(
                    name = getTranslation("Popular on HBO"),
                    list = it.mapNotNull(mapMulti),
                )
            )
        }

        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            val genres = listOf(
                TMDb3.Genres.movieList(language = language),
                TMDb3.Genres.tvList(language = language),
            ).flatMap { it.genres }
                .distinctBy { it.id }
                .sortedBy { it.name }
                .map {
                    Genre(
                        id = it.id.toString(),
                        name = it.name,
                    )
                }

            return genres
        }

        val results = TMDb3.Search.multi(query, page = page, language = language).results.mapNotNull { multi ->
            when (multi) {
                is TMDb3.Movie -> Movie(
                    id = multi.id.toString(),
                    title = multi.title,
                    overview = multi.overview,
                    released = multi.releaseDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w342,
                    banner = multi.backdropPath?.w780,
                )

                is TMDb3.Tv -> TvShow(
                    id = multi.id.toString(),
                    title = multi.name,
                    overview = multi.overview,
                    released = multi.firstAirDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w342,
                    banner = multi.backdropPath?.w780,
                )

                else -> null
            }
        }

        return results
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val movies = TMDb3.MovieLists.popular(page = page, language = language).results.map { movie ->
            Movie(
                id = movie.id.toString(),
                title = movie.title,
                overview = movie.overview,
                released = movie.releaseDate,
                rating = movie.voteAverage.toDouble(),
                poster = movie.posterPath?.w342,
                banner = movie.backdropPath?.w780,
            )
        }

        return movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val tvShows = TMDb3.TvSeriesLists.popular(page = page, language = language).results.map { tv ->
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.w342,
                banner = tv.backdropPath?.w780,
            )
        }

        return tvShows
    }

    override suspend fun getMovie(id: String): Movie {
        val movie = TMDb3.Movies.details(
            movieId = id.toInt(),
            appendToResponse = listOf(
                TMDb3.Params.AppendToResponse.Movie.CREDITS,
                TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
            ),
            language = language
        ).let { movie ->
            Movie(
                id = movie.id.toString(),
                title = movie.title,
                overview = movie.overview,
                released = movie.releaseDate,
                runtime = movie.runtime,
                trailer = movie.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = movie.voteAverage.toDouble(),
                poster = movie.posterPath?.w342,
                banner = movie.backdropPath?.w780,
                imdbId = movie.externalIds?.imdbId,

                genres = movie.genres.map { genre ->
                    Genre(
                        genre.id.toString(),
                        genre.name,
                    )
                },
                cast = movie.credits?.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    )
                } ?: listOf(),
                recommendations = movie.recommendations?.results?.mapNotNull { multi ->
                    when (multi) {
                        is TMDb3.Movie -> Movie(
                            id = multi.id.toString(),
                            title = multi.title,
                            overview = multi.overview,
                            released = multi.releaseDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w342,
                            banner = multi.backdropPath?.w780,
                        )

                        is TMDb3.Tv -> TvShow(
                            id = multi.id.toString(),
                            title = multi.name,
                            overview = multi.overview,
                            released = multi.firstAirDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w342,
                            banner = multi.backdropPath?.w780,
                        )

                        else -> null
                    }
                } ?: listOf(),
            )
        }

        return movie
    }

    override suspend fun getTvShow(id: String): TvShow {
        val tvShow = TMDb3.TvSeries.details(
            seriesId = id.toInt(),
            appendToResponse = listOf(
                TMDb3.Params.AppendToResponse.Tv.CREDITS,
                TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
            ),
            language = language
        ).let { tv ->
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                trailer = tv.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.w342,
                banner = tv.backdropPath?.w780,
                imdbId = tv.externalIds?.imdbId,

                seasons = tv.seasons.map { season ->
                    Season(
                        id = "${tv.id}-${season.seasonNumber}",
                        number = season.seasonNumber,
                        title = season.name,
                        poster = season.posterPath?.w342,
                    )
                },
                genres = tv.genres.map { genre ->
                    Genre(
                        genre.id.toString(),
                        genre.name,
                    )
                },
                cast = tv.credits?.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    )
                } ?: listOf(),
                recommendations = tv.recommendations?.results?.mapNotNull { multi ->
                    when (multi) {
                        is TMDb3.Movie -> Movie(
                            id = multi.id.toString(),
                            title = multi.title,
                            overview = multi.overview,
                            released = multi.releaseDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w342,
                            banner = multi.backdropPath?.w780,
                        )

                        is TMDb3.Tv -> TvShow(
                            id = multi.id.toString(),
                            title = multi.name,
                            overview = multi.overview,
                            released = multi.firstAirDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w342,
                            banner = multi.backdropPath?.w780,
                        )

                        else -> null
                    }
                } ?: listOf(),
            )
        }

        return tvShow
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (tvShowId, seasonNumber) = seasonId.split("-")

        val episodes = TMDb3.TvSeasons.details(
            seriesId = tvShowId.toInt(),
            seasonNumber = seasonNumber.toInt(),
            language = language
        ).episodes?.map {
            Episode(
                id = it.id.toString(),
                number = it.episodeNumber,
                title = it.name ?: "",
                released = it.airDate,
                poster = it.stillPath?.w500,
            )
        } ?: listOf()

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        fun <T> List<T>.mix(other: List<T>): List<T> {
            return sequence {
                val first = iterator()
                val second = other.iterator()
                while (first.hasNext() && second.hasNext()) {
                    yield(first.next())
                    yield(second.next())
                }

                yieldAll(first)
                yieldAll(second)
            }.toList()
        }

        val genre = Genre(
            id = id,
            name = "",

            shows = TMDb3.Discover.movie(
                page = page,
                withGenres = TMDb3.Params.WithBuilder(id),
                language = language
            ).results.map { movie ->
                Movie(
                    id = movie.id.toString(),
                    title = movie.title,
                    overview = movie.overview,
                    released = movie.releaseDate,
                    rating = movie.voteAverage.toDouble(),
                    poster = movie.posterPath?.w342,
                    banner = movie.backdropPath?.w780,
                )
            }.mix(TMDb3.Discover.tv(
                page = page,
                withGenres = TMDb3.Params.WithBuilder(id),
                language = language
            ).results.map { tv ->
                TvShow(
                    id = tv.id.toString(),
                    title = tv.name,
                    overview = tv.overview,
                    released = tv.firstAirDate,
                    rating = tv.voteAverage.toDouble(),
                    poster = tv.posterPath?.w342,
                    banner = tv.backdropPath?.w780,
                )
            })
        )

        return genre
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val people = TMDb3.People.details(
            personId = id.toInt(),
            appendToResponse = listOfNotNull(
                if (page > 1) null else TMDb3.Params.AppendToResponse.Person.COMBINED_CREDITS,
            ),
            language = language
        ).let { person ->
            People(
                id = person.id.toString(),
                name = person.name,
                image = person.profilePath?.w500,
                biography = person.biography,
                placeOfBirth = person.placeOfBirth,
                birthday = person.birthday,
                deathday = person.deathday,

                filmography = person.combinedCredits?.cast
                    ?.mapNotNull { multi ->
                        when (multi) {
                            is TMDb3.Movie -> Movie(
                                id = multi.id.toString(),
                                title = multi.title,
                                overview = multi.overview,
                                released = multi.releaseDate,
                                rating = multi.voteAverage.toDouble(),
                                poster = multi.posterPath?.w342,
                                banner = multi.backdropPath?.w780,
                            )

                            is TMDb3.Tv -> TvShow(
                                id = multi.id.toString(),
                                title = multi.name,
                                overview = multi.overview,
                                released = multi.firstAirDate,
                                rating = multi.voteAverage.toDouble(),
                                poster = multi.posterPath?.w342,
                                banner = multi.backdropPath?.w780,
                            )

                        else -> null
                    }
                }
                    ?.sortedBy {
                        when (it) {
                            is Movie -> it.released
                            is TvShow -> it.released
                        }
                    }
                    ?.reversed()
                    ?: listOf()
            )
        }

        return people
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        // Videasy first: native, ~150 ms, adaptive up to 1080p (see VideasyExtractor).
        val videasy = VideasyExtractor().servers(videoType)
        servers.addAll(videasy)
        servers.addAll(listOf(
            VixSrcExtractor().server(videoType),
            TwoEmbedExtractor().server(videoType),
            VidsrcNetExtractor().server(videoType),
            VidLinkExtractor().server(videoType),
            VidsrcRuExtractor().server(videoType),
            VidflixExtractor().server(videoType),
        ))

        if (videoType is Video.Type.Movie) {
            servers.add(videasy.size + 2, MoviesapiExtractor().server(videoType))
        }

        servers.addAll(VidrockExtractor().servers(videoType))
        servers.addAll(VidzeeExtractor().servers(videoType))
        servers.addAll(VidemExtractor().servers(videoType, "en"))
        servers.addAll(VidloveExtractor().servers(videoType))

        // Torrent servers last: they are the 1080p fallback/upgrade (opt-out in Settings > Sources).
        servers.addAll(
            runCatching { kotlinx.coroutines.withTimeoutOrNull(8_000L) { TorrentServers().servers(videoType) } }
                .getOrNull().orEmpty()
        )

        // Servers that failed the last two health runs only slow playback down; keep them if nothing else is left.
        val unique = servers.distinctBy { it.id }
        val alive = unique.filterNot { !TorrentServers.isTorrentServer(it) && HealthStore.isDead(it.name) }
        return if (alive.size >= 2) alive else unique
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (TorrentServers.isTorrentServer(server)) return TorrentServers.videoFor(server)
        val url = server.src.ifEmpty { server.id }
        return server.video ?: Extractor.extract(url, server)
    }

    private fun getTranslation(key: String): String {
        return when (language) {
            "hu" -> when (key) {
                "Trending" -> "Felkapott"
                "Popular Movies" -> "Népszerű filmek"
                "Popular TV Shows" -> "Népszerű sorozatok"
                "Popular Anime" -> "Népszerű animék"
                "Popular on Netflix" -> "Népszerű a Netflixen"
                "Popular on Amazon" -> "Népszerű az Amazonon"
                "Popular on Disney+" -> "Népszerű a Disney+-on"
                "Popular on Hulu" -> "Népszerű a Hulun"
                "Popular on Apple TV+" -> "Népszerű az Apple TV+-on"
                "Popular on HBO" -> "Népszerű az HBO-n"
                else -> key
            }
            else -> key
        }
    }
}
