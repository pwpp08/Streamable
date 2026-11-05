package com.streamflixreborn.streamflix.desktop

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.TmdbProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Command-line check of the shared provider stack: home rows, servers and a resolved stream. */
fun main(args: Array<String>): Unit = runBlocking {
    val provider = TmdbProvider("en")
    val home = provider.getHome()
    println("home rows: " + home.joinToString { "${it.name}(${it.list.size})" })
    val movieId = args.firstOrNull() ?: "27205"
    val movie = provider.getMovie(movieId)
    println("movie: ${movie.title} imdb=${movie.imdbId}")
    val type = Video.Type.Movie(movie.id, movie.title, movie.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: "", movie.poster ?: "", movie.imdbId)
    val servers = provider.getServers(movie.id, type)
    println("servers: " + servers.joinToString { it.name })
    for (server in servers.take(6)) {
        val video = withTimeoutOrNull(20_000) { runCatching { provider.getVideo(server) }.onFailure { println("  ${server.name}: FAIL ${it.message}") }.getOrNull() }
        if (video != null) println("  ${server.name}: ${video.type} ${video.source.take(120)} headers=${video.headers?.keys}")
    }
    Runtime.getRuntime().halt(0)
}
