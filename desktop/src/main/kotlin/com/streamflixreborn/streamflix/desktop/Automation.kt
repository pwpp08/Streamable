package com.streamflixreborn.streamflix.desktop

import com.streamflixreborn.streamflix.desktop.data.Repo
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.awt.EventQueue
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * Test hook for development builds: with -Dstreamflix.automation=<port>, a loopback-only HTTP
 * endpoint drives navigation (/tab, /open, /back, /play) so the app can be exercised from
 * scripts. Never started otherwise.
 */
object Automation {
    var status: () -> String = { "" }
    var pause: () -> Unit = {}

    fun startIfEnabled() {
        val port = System.getProperty("streamflix.automation")?.toIntOrNull() ?: return
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 8)
        server.createContext("/") { ex ->
            val q = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            val reply = runCatching { handle(ex.requestURI.path, q) }.getOrElse { "error: ${it.message}" }
            val bytes = reply.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun ui(block: () -> Unit) = EventQueue.invokeAndWait(block)

    private fun handle(path: String, q: Map<String, String>): String = when (path) {
        "/tab" -> { ui { Nav.selectTab(Nav.tabs.first { it.toString().equals(q["name"], true) }) }; "ok" }
        "/open" -> {
            ui { Nav.open(if (q["type"] == "show") Screen.ShowDetails(q["id"]!!) else Screen.MovieDetails(q["id"]!!)) }
            "ok"
        }
        "/back" -> { ui { Nav.back() }; "ok" }
        "/play" -> {
            val request = runBlocking {
                if (q["show"] != null) {
                    val show = Repo.show(q["show"]!!)
                    val season = show.seasons.first { it.number == (q["season"]?.toInt() ?: 1) }
                    val ep = Repo.episodes(season.id).first { it.number == (q["ep"]?.toInt() ?: 1) }
                    PlayRequest(Repo.episodeType(show, season.number, season.title, ep), show.title, "S${season.number} · E${ep.number}")
                } else {
                    val movie = Repo.movie(q["movie"]!!)
                    PlayRequest(Repo.movieType(movie), movie.title, startMs = q["start"]?.toLong() ?: 0)
                }
            }
            ui { Nav.play(request) }
            "ok"
        }
        "/status" -> "inPlayer=${Nav.playing != null} " + status()
        "/pause" -> { ui { pause() }; "ok" }
        else -> "unknown"
    }
}
