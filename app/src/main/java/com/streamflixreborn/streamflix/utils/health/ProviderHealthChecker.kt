package com.streamflixreborn.streamflix.utils.health

import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.torrent.TorrentSpec
import com.streamflixreborn.streamflix.utils.MetadataLanguage
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.Collections

/**
 * Probes providers with a known movie (Inception, TMDb 27205) and a known episode
 * (Breaking Bad S01E01, TMDb 1396): `getServers`, then `getVideo` per server (bounded by
 * [videoTimeoutMs], [concurrency] at a time), then a request to the stream to read its quality.
 */
class ProviderHealthChecker(
    private val providers: List<Provider> = defaultProviders(includeCandidates = false),
    private val videoTimeoutMs: Long = 20_000L,
    private val lookupTimeoutMs: Long = 30_000L,
    private val concurrency: Int = 4,
    private val httpClient: () -> OkHttpClient = { NetworkClient.default },
) {

    sealed interface Progress {
        data class Started(val providers: List<String>) : Progress
        data class ProviderStarted(val provider: String, val index: Int, val total: Int) : Progress
        data class Checked(val result: ServerHealth) : Progress
        data class Finished(val results: List<ServerHealth>) : Progress
    }

    private class Probe(val kind: String, val id: String, val videoType: Video.Type)

    fun run(): Flow<Progress> = channelFlow {
        val results = Collections.synchronizedList(mutableListOf<ServerHealth>())
        val semaphore = Semaphore(concurrency)
        send(Progress.Started(providers.map { it.name }))

        providers.forEachIndexed { index, provider ->
            send(Progress.ProviderStarted(provider.name, index, providers.size))
            val kinds = buildList {
                if (Provider.supportsMovies(provider)) add(ServerHealth.PROBE_MOVIE)
                if (Provider.supportsTvShows(provider)) add(ServerHealth.PROBE_EPISODE)
            }
            for (kind in kinds) {
                val probe = try {
                    withTimeout(lookupTimeoutMs) { findProbe(provider, kind) }
                } catch (e: Throwable) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    report(results, providerFailure(provider, kind, describe(e)))
                    continue
                }
                if (probe == null) {
                    report(results, providerFailure(provider, kind, ServerHealth.ERROR_NO_MATCH))
                    continue
                }
                checkServers(provider, probe, semaphore, results)
            }
        }
        send(Progress.Finished(results.toList()))
    }.flowOn(Dispatchers.IO)

    private suspend fun ProducerScope<Progress>.checkServers(
        provider: Provider,
        probe: Probe,
        semaphore: Semaphore,
        results: MutableList<ServerHealth>,
    ) {
        val servers = try {
            withTimeout(lookupTimeoutMs) { provider.getServers(probe.id, probe.videoType) }
        } catch (e: Throwable) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            report(results, providerFailure(provider, probe.kind, "getServers: ${describe(e)}"))
            return
        }
        if (servers.isEmpty()) {
            report(results, providerFailure(provider, probe.kind, "no servers"))
            return
        }
        coroutineScope {
            servers.map { server ->
                async { semaphore.withPermit { report(results, checkServer(provider, server, probe.kind)) } }
            }.awaitAll()
        }
    }

    private suspend fun checkServer(provider: Provider, server: Video.Server, kind: String): ServerHealth {
        val started = System.currentTimeMillis()
        return try {
            withTimeout(videoTimeoutMs) {
                val video = provider.getVideo(server)
                if (video.source.isBlank()) error("empty source")
                val stream = inspectStream(video)
                ServerHealth(
                    provider = provider.name,
                    serverName = server.name,
                    ok = stream.ok,
                    latencyMs = System.currentTimeMillis() - started,
                    maxHeight = stream.maxHeight,
                    error = stream.error,
                    checkedAt = System.currentTimeMillis(),
                    probe = kind,
                )
            }
        } catch (e: Throwable) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            ServerHealth(
                provider = provider.name,
                serverName = server.name,
                ok = false,
                latencyMs = System.currentTimeMillis() - started,
                error = describe(e),
                checkedAt = System.currentTimeMillis(),
                probe = kind,
            )
        }
    }

    /**
     * Best quality class of [video]: the master playlist's RESOLUTION, or, for a plain media
     * playlist (no resolutions listed), an estimate from the first segment's bitrate. Null when
     * neither can be read.
     */
    suspend fun probeHeight(video: Video): Int? = try {
        // Torrent sources carry their quality in the URI; nothing to fetch.
        if (TorrentSpec.isTorrentUri(video.source)) TorrentSpec.heightOf(video.source)
        else inspectStream(video).maxHeight ?: estimateFromBitrate(video)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun estimateFromBitrate(video: Video): Int? {
        if (!video.source.startsWith("http", ignoreCase = true)) return null
        return runInterruptible(Dispatchers.IO) {
            val client = httpClient()
            fun request(url: String, range: String? = null): Request {
                val b = Request.Builder().url(url)
                video.headers?.forEach { (n, v) -> runCatching { b.header(n, v) } }
                if (range != null) b.header("Range", range)
                return b.build()
            }
            val text = client.newCall(request(video.source)).execute().use { r ->
                if (!r.isSuccessful) return@runInterruptible null
                r.body?.source()?.let { it.request(MAX_PLAYLIST_BYTES); it.buffer.clone().readUtf8() }.orEmpty()
            }
            if (!HlsPlaylistParser.isPlaylist(text) || HlsPlaylistParser.isMasterPlaylist(text)) return@runInterruptible null
            val lines = text.lines().map { it.trim() }
            val index = lines.indexOfFirst { it.startsWith("#EXTINF:") }
            if (index < 0) return@runInterruptible null
            val seconds = lines[index].removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: return@runInterruptible null
            val segment = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@runInterruptible null
            val url = client.newCall(request(video.source)).request().url.resolve(segment)?.toString() ?: return@runInterruptible null
            // Last segment of a film is short; use a middle one if there are enough.
            val total = client.newCall(request(url, "bytes=0-0")).execute().use { r ->
                r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.header("Content-Length")?.toLongOrNull()
            } ?: return@runInterruptible null
            if (seconds <= 1.0) return@runInterruptible null
            val bitsPerSecond = total * 8 / seconds
            when {
                bitsPerSecond >= FULL_HD_MIN_BPS -> 1080
                bitsPerSecond >= HD_MIN_BPS -> 720
                else -> 480
            }
        }
    }

    private class StreamCheck(val ok: Boolean, val maxHeight: Int?, val error: String?)

    private suspend fun inspectStream(video: Video): StreamCheck {
        if (TorrentSpec.isTorrentUri(video.source)) {
            val spec = TorrentSpec.parse(video.source)
            return StreamCheck(spec != null, spec?.height, if (spec == null) "bad torrent uri" else null)
        }
        val hls = isHls(video)
        // Some extractors (e.g. Vixcloud) hand the player an inline data: playlist; read it directly.
        if (video.source.startsWith("data:", ignoreCase = true)) return inspectDataUri(video.source, hls)
        val builder = Request.Builder().url(video.source)
        video.headers?.forEach { (name, value) -> runCatching { builder.header(name, value) } }
        if (!hls) builder.header("Range", "bytes=0-1")
        val request = runCatching { builder.build() }.getOrElse {
            return StreamCheck(false, null, "bad url")
        }
        return runInterruptible(Dispatchers.IO) {
            httpClient().newCall(request).execute().use { response -> readStream(response, hls) }
        }
    }

    private fun inspectDataUri(uri: String, hls: Boolean): StreamCheck {
        val comma = uri.indexOf(',')
        if (comma < 0) return StreamCheck(false, null, "bad data uri")
        val meta = uri.substring(5, comma)
        val payload = uri.substring(comma + 1)
        val text = runCatching {
            if (meta.endsWith(";base64", ignoreCase = true)) {
                String(android.util.Base64.decode(payload, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } else {
                java.net.URLDecoder.decode(payload, "UTF-8")
            }
        }.getOrElse { return StreamCheck(false, null, "bad data uri") }
        if (!HlsPlaylistParser.isPlaylist(text)) return StreamCheck(!hls, null, if (hls) "not a playlist" else null)
        return StreamCheck(true, HlsPlaylistParser.maxHeight(text), null)
    }

    private fun readStream(response: Response, hls: Boolean): StreamCheck {
        if (!response.isSuccessful) return StreamCheck(false, null, "HTTP ${response.code}")
        if (!hls) return StreamCheck(true, null, null)
        val text = response.body?.let { body ->
            body.source().let { source ->
                source.request(MAX_PLAYLIST_BYTES)
                source.buffer.clone().readUtf8()
            }
        }.orEmpty()
        if (!HlsPlaylistParser.isPlaylist(text)) return StreamCheck(false, null, "not a playlist")
        return StreamCheck(true, HlsPlaylistParser.maxHeight(text), null)
    }

    private suspend fun findProbe(provider: Provider, kind: String): Probe? =
        if (kind == ServerHealth.PROBE_MOVIE) findMovie(provider) else findEpisode(provider)

    private suspend fun findMovie(provider: Provider): Probe? {
        val movie: Movie = if (provider is TmdbProvider) {
            provider.getMovie(MOVIE_TMDB_ID)
        } else {
            val hit = bestMatch(provider.search(MOVIE_TITLE).filterIsInstance<Movie>(), MOVIE_TITLE) { it.title }
                ?: return null
            runCatching { provider.getMovie(hit.id) }.getOrDefault(hit)
        }
        return Probe(
            ServerHealth.PROBE_MOVIE,
            movie.id,
            Video.Type.Movie(
                id = movie.id,
                title = movie.title,
                releaseDate = movie.released?.format("yyyy-MM-dd") ?: MOVIE_RELEASE,
                poster = movie.poster.orEmpty(),
                imdbId = movie.imdbId ?: MOVIE_IMDB_ID.takeIf { provider is TmdbProvider },
            ),
        )
    }

    private suspend fun findEpisode(provider: Provider): Probe? {
        val showId = if (provider is TmdbProvider) {
            SHOW_TMDB_ID
        } else {
            bestMatch(provider.search(SHOW_TITLE).filterIsInstance<TvShow>(), SHOW_TITLE) { it.title }?.id
                ?: return null
        }
        val show = provider.getTvShow(showId)
        val season = show.seasons.firstOrNull { it.number == 1 }
            ?: show.seasons.minByOrNull { if (it.number <= 0) Int.MAX_VALUE else it.number }
            ?: return null
        val episode: Episode = provider.getEpisodesBySeason(season.id).let { episodes ->
            episodes.firstOrNull { it.number == 1 } ?: episodes.firstOrNull()
        } ?: return null
        return Probe(
            ServerHealth.PROBE_EPISODE,
            episode.id,
            Video.Type.Episode(
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
                    releaseDate = show.released?.format("yyyy-MM-dd"),
                    imdbId = show.imdbId,
                ),
                season = Video.Type.Episode.Season(number = season.number, title = season.title),
            ),
        )
    }

    private suspend fun SendChannel<Progress>.report(results: MutableList<ServerHealth>, result: ServerHealth) {
        results += result
        send(Progress.Checked(result))
    }

    private fun providerFailure(provider: Provider, kind: String, error: String) = ServerHealth(
        provider = provider.name,
        serverName = ServerHealth.PROVIDER_LEVEL,
        ok = false,
        error = error,
        checkedAt = System.currentTimeMillis(),
        probe = kind,
    )

    companion object {
        const val MOVIE_TITLE = "Inception"
        const val MOVIE_TMDB_ID = "27205"
        private const val MOVIE_IMDB_ID = "tt1375666"
        private const val MOVIE_RELEASE = "2010-07-15"
        const val SHOW_TITLE = "Breaking Bad"
        const val SHOW_TMDB_ID = "1396"
        private const val MAX_PLAYLIST_BYTES = 512L * 1024
        /** A film at or above this bitrate is treated as 1080p, at or above the lower one as 720p. */
        private const val FULL_HD_MIN_BPS = 3_500_000L
        private const val HD_MIN_BPS = 1_500_000L

        /** Providers not in `Provider.providers` that can be probed on request. */
        val candidateProviders: List<Provider>
            get() = emptyList<Provider>()

        /** Registered providers plus the TMDb provider of the metadata language. */
        fun defaultProviders(includeCandidates: Boolean): List<Provider> = buildList {
            addAll(Provider.providers.keys)
            add(MetadataLanguage.tmdbProvider())
            if (includeCandidates) addAll(candidateProviders)
        }

        fun isHls(video: Video): Boolean =
            video.type?.contains("mpegurl", ignoreCase = true) == true ||
                video.source.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                video.source.contains(".m3u8", ignoreCase = true)

        private fun normalizeTitle(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

        /** Exact title match first, then a title that only adds a suffix such as "(2010)". */
        internal fun <T> bestMatch(items: List<T>, wanted: String, title: (T) -> String): T? {
            val target = normalizeTitle(wanted)
            return items.firstOrNull { normalizeTitle(title(it)) == target }
                ?: items.firstOrNull { normalizeTitle(title(it)).startsWith("$target ") }
        }

        private fun describe(e: Throwable): String = when (e) {
            is TimeoutCancellationException -> "timeout"
            else -> (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).take(160)
        }
    }
}
