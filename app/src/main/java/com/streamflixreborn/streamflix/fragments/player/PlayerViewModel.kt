package com.streamflixreborn.streamflix.fragments.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.downloads.OfflineMediaProvider
import com.streamflixreborn.streamflix.fragments.player.engine.NextEpisodePrefetch
import com.streamflixreborn.streamflix.fragments.player.engine.QualityMemory
import com.streamflixreborn.streamflix.fragments.player.engine.AudioGate
import com.streamflixreborn.streamflix.fragments.player.engine.ServerRanker
import com.streamflixreborn.streamflix.fragments.player.engine.SourceProbe
import com.streamflixreborn.streamflix.torrent.TorrentEngine
import com.streamflixreborn.streamflix.torrent.TorrentSpec
import com.streamflixreborn.streamflix.fragments.player.engine.StickyServers
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.LanguageMatcher
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import com.streamflixreborn.streamflix.utils.SubDL
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import com.streamflixreborn.streamflix.utils.health.ProviderHealthChecker
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

class PlayerViewModel(
    private val videoType: Video.Type,
    private val id: String,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.LoadingServers)
    val state: Flow<State> = _state

    private val _lowQuality = MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 1)

    /** (wanted, playing) lines when every source was checked and none reached the minimum quality. */
    val lowQuality: SharedFlow<Pair<Int, Int>> = _lowQuality

    private val _subtitleState = MutableSharedFlow<SubtitleState>()
    val subtitleState: SharedFlow<SubtitleState> = _subtitleState

    private val _playPreviousOrNextEpisode = MutableSharedFlow<Video.Type.Episode>()
    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode

    /** Resolved videos by server id; reused for instant failover. */
    private class Resolved(val video: Video, val at: Long = SystemClock.elapsedRealtime()) {
        val isFresh get() = SystemClock.elapsedRealtime() - at < RESOLVED_MAX_AGE_MS
    }

    private var servers: List<Video.Server> = emptyList()
    private var ranked: List<Video.Server> = emptyList()
    private val resolved = ConcurrentHashMap<String, Resolved>()
    private val failedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val wrongAudio: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var lastError: Exception? = null

    @Volatile
    private var currentServerId: String? = null

    /** The server that is really on screen; during a background switch it differs from [currentServerId]. */
    private var activeServerId: String? = null
    private var activeVideo: Video? = null
    private var upgradeJob: Job? = null
    private var resolutionJob: Job? = null
    private var playbackJob: Job? = null
    private var prefetchJob: Job? = null
    private val playbackMutex = Mutex()

    private val stickyKey: String?
        get() = StickyServers.key(UserPreferences.currentProvider, videoType)

    /** Set while a completed download is playing (see [loadSources]). */
    @Volatile
    private var offlineServer: Video.Server? = null

    init {
        loadSources()
        getSubtitles(videoType)
    }

    /**
     * A title with a completed download plays from the download cache, skipping server
     * resolution entirely (also online, to save data). Otherwise the provider's servers load.
     */
    private fun loadSources() = viewModelScope.launch(Dispatchers.IO) {
        val offline = OfflineMediaProvider.lookup(
            StreamFlixApp.instance, videoType, UserPreferences.currentProvider?.name
        )
        if (offline == null) {
            getServers(videoType, id)
            return@launch
        }
        offlineServer = offline.server
        currentServerId = offline.server.id
        _state.emit(State.SuccessLoadingVideo(prepareVideo(offline.video), offline.server))
    }

    fun playEpisode(direction: Direction) {
        val hasEpisode = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.hasPreviousEpisode()
            Direction.NEXT -> EpisodeManager.hasNextEpisode()
        }

        if (!hasEpisode) return

        val ep = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.getPreviousEpisode()
            Direction.NEXT -> EpisodeManager.getNextEpisode()
        } ?: return

        val nextEpisode = Video.Type.Episode(
            id = ep.id,
            number = ep.number,
            title = ep.title,
            poster = ep.poster,
            overview = ep.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = ep.tvShow.id,
                title = ep.tvShow.title,
                poster = ep.tvShow.poster,
                banner = ep.tvShow.banner,
                releaseDate = ep.tvShow.releaseDate,
                imdbId = ep.tvShow.imdbId
            ),
            season = Video.Type.Episode.Season(
                number = ep.season.number,
                title = ep.season.title
            )
        )

        // The player screen is re-created for the new episode (with a new ViewModel that
        // loads its own servers), so only the navigation event is needed here.
        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(nextEpisode)
        }
    }

    enum class Direction { PREVIOUS, NEXT }
    fun playPreviousEpisode() =
        playEpisode(Direction.PREVIOUS)

    fun playNextEpisode() =
        playEpisode(Direction.NEXT)

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) {
            playEpisode(Direction.NEXT)
        }
    }

    private fun getServers(videoType: Video.Type, id: String) = viewModelScope.launch(Dispatchers.IO) {
        Log.d(TAG, "Loading servers for id: $id")
        _state.emit(State.LoadingServers)
        try {
            val provider = UserPreferences.currentProvider!!
            val prefetched = NextEpisodePrefetch.take(id, provider.name)
            val servers = prefetched?.servers ?: provider.getServers(id, videoType)
            if (servers.isEmpty()) throw Exception("No servers found")
            prefetched?.resolved?.forEach { (serverId, video) -> resolved[serverId] = Resolved(video, prefetched.createdAt) }

            Log.i(TAG, "[SERVERS LIST] -> Provider: ${provider.name}, prefetched=${prefetched != null}")
            Log.i(TAG, "[SERVERS LIST] -> Found ${servers.size} servers: ${servers.joinToString { it.name }}")
            _state.emit(State.SuccessLoadingServers(servers))
        } catch (e: Exception) {
            Log.e(TAG, "Loading servers failed: ", e)
            _state.emit(State.FailedLoadingServers(e))
        }
    }

    /**
     * Resolves servers in parallel (best ranked first) and starts the best-ranked one that
     * resolved. The others stay available for failover.
     */
    fun startPlayback(servers: List<Video.Server>, preferredServerName: String?) {
        if (servers.isEmpty()) return
        this.servers = servers
        val sticky = StickyServers.get(StreamFlixApp.instance, stickyKey)
        stickyName = sticky
        ranked = ServerRanker.rank(servers, preferredName = preferredServerName, stickyName = sticky)
        failedIds.clear()
        wrongAudio.clear()
        Log.d(TAG, "ranked servers (preferred=$preferredServerName, sticky=$sticky): ${ranked.joinToString { it.name }}")

        val results = Channel<Pair<Int, Video?>>(Channel.UNLIMITED)
        resolutionJob?.cancel()
        resolutionJob = launchResolutions(ranked, results)
        launchPlayback { pickFirst(ranked, results) }
    }

    /** Manual server choice from the player settings. */
    fun getVideo(server: Video.Server) {
        if (ranked.isEmpty()) {
            servers = listOf(server)
            ranked = servers
        }
        failedIds.remove(server.id)
        launchPlayback { playChosen(server) }
    }

    /**
     * A server picked by hand. If it doesn't resolve while something is already playing, that keeps
     * playing and the user is told; moving on to another server they didn't pick would be confusing.
     */
    private suspend fun playChosen(server: Video.Server) {
        val cached = cachedVideo(server)
        if (cached == null) _state.emit(State.LoadingVideo(server))
        val video = cached ?: resolve(server)
        if (video != null) {
            emitSuccess(video, server, failover = false)
            return
        }
        failedIds += server.id
        if (activeServerId != null) {
            _state.emit(State.ChoiceUnavailable(server))
            return
        }
        playWithFailover(null, failover = true)
    }

    /** A hand-picked server failed and nothing was left playing: carry on with the best of the rest. */
    fun continueAfterFailedChoice() {
        launchPlayback { playWithFailover(null, failover = true) }
    }

    /** The player failed on [server] (fatal error): continue with the next server. */
    fun onPlaybackFailed(server: Video.Server?) {
        val failedId = server?.id ?: currentServerId ?: return
        if (failedId != currentServerId) return
        if (failedId == offlineServer?.id) {
            // The downloaded copy is unreadable: fall back to streaming.
            Log.w(TAG, "offline playback failed, loading servers")
            offlineServer = null
            currentServerId = null
            getServers(videoType, id)
            return
        }
        failedIds += failedId
        resolved.remove(failedId)
        currentServerId = null
        launchPlayback { playWithFailover(null, failover = true) }
    }

    /** [server] is playing fine; remember it for this title. */
    fun onPlaybackStarted(server: Video.Server) {
        activeServerId = server.id
        resolved[server.id]?.video?.let { activeVideo = it }
        if (server.id == offlineServer?.id) return
        // Torrents too: whatever played this title last is what it starts with next time.
        StickyServers.put(StreamFlixApp.instance, stickyKey, server.name)
    }

    /**
     * The background switch to [candidate] could not be completed; [previous] is still on screen.
     * The candidate is crossed off, and the search for a sharper source goes on from the one playing.
     */
    fun onSwitchFailed(candidate: Video.Server, previous: Video.Server?) {
        failedIds += candidate.id
        resolved.remove(candidate.id)
        if (currentServerId != candidate.id) return
        if (previous == null) {
            currentServerId = null
            return
        }
        currentServerId = previous.id
        QualityMemory.currentServer = previous.name
        activeVideo?.let { startQualityUpgrade(it, previous) }
    }

    /** Resolves the next episode's servers ahead of time (called at ~90 % progress). */
    fun prefetchNextEpisode(episode: Video.Type.Episode, preferredServerName: String?) {
        val provider = UserPreferences.currentProvider ?: return
        if (prefetchJob?.isActive == true || NextEpisodePrefetch.isPrepared(episode.id, provider.name)) return
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val servers = try {
                provider.getServers(episode.id, episode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "next episode prefetch: servers failed", e)
                return@launch
            }
            if (servers.isEmpty()) return@launch
            val sticky = StickyServers.get(StreamFlixApp.instance, StickyServers.key(provider, episode))
            val order = ServerRanker.rank(servers, preferredName = preferredServerName, stickyName = sticky)
            val prepared = mutableMapOf<String, Video>()
            for (server in order.take(PREFETCH_ATTEMPTS)) {
                val video = resolveRaw(server) ?: continue
                prepared[server.id] = video
                break
            }
            NextEpisodePrefetch.put(NextEpisodePrefetch.Entry(episode.id, provider.name, servers, prepared))
            Log.d(TAG, "next episode prefetched: ${servers.size} servers, ${prepared.size} resolved")
        }
    }

    private fun launchPlayback(block: suspend () -> Unit) {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch(Dispatchers.IO) {
            playbackMutex.withLock { block() }
        }
    }

    private fun launchResolutions(order: List<Video.Server>, results: Channel<Pair<Int, Video?>>): Job =
        viewModelScope.launch(Dispatchers.IO) {
            val semaphore = Semaphore(MAX_PARALLEL_RESOLUTIONS)
            val successes = AtomicInteger(0)
            order.forEachIndexed { index, server ->
                cachedVideo(server)?.let {
                    successes.incrementAndGet()
                    results.trySend(index to it)
                    return@forEachIndexed
                }
                launch {
                    semaphore.withPermit {
                        // Enough backups are ready; the rest is resolved on demand.
                        if (successes.get() >= MAX_PRERESOLVED) {
                            results.trySend(index to null)
                            return@withPermit
                        }
                        val video = resolve(server)
                        if (video != null) successes.incrementAndGet() else failedIds += server.id
                        results.trySend(index to video)
                    }
                }
            }
        }

    /** The server this title played on last time; a remembered torrent isn't held back like other torrents. */
    private var stickyName: String? = null

    private fun deferred(server: Video.Server) =
        ServerRanker.isTorrentServer(server.name) && !server.name.equals(stickyName, ignoreCase = true)

    private suspend fun pickFirst(order: List<Video.Server>, results: Channel<Pair<Int, Video?>>) {
        _state.emit(State.LoadingVideo(order.first()))
        val done = BooleanArray(order.size)
        val videos = arrayOfNulls<Video>(order.size)
        var graceDeadline = 0L

        while (true) {
            coroutineContext.ensureActive()
            // A torrent "resolves" instantly but takes far longer to start, so it only opens when no web server can.
            val webPending = order.indices.any { !done[it] && !deferred(order[it]) }
            val best = videos.indices.firstOrNull { videos[it] != null && (!webPending || !deferred(order[it])) } ?: -1
            if (best >= 0) {
                val higherRankedDone = (0 until best).all { done[it] }
                val now = SystemClock.elapsedRealtime()
                if (graceDeadline == 0L) graceDeadline = now + PICK_GRACE_MS
                // Quality differs per title, so the first answer is not picked until there are a few to compare.
                // The remembered server needs no comparison: it is what the viewer settled on.
                val enough = videos.indices.count { videos[it] != null && !ServerRanker.isTorrentServer(order[it].name) } >= MIN_TO_COMPARE ||
                    !webPending || order[best].name.equals(stickyName, ignoreCase = true)
                if ((higherRankedDone && enough) || now >= graceDeadline) {
                    val pick = if (order[best].name.equals(stickyName, ignoreCase = true)) best else chooseByMeasurement(order, videos) ?: best
                    emitSuccess(videos[pick]!!, order[pick], failover = false)
                    return
                }
            } else if (done.all { it }) {
                // Everything was tried; anything skipped is retried sequentially.
                playWithFailover(null, failover = false)
                return
            }

            val next = if (graceDeadline == 0L) {
                results.receive()
            } else {
                withTimeoutOrNull((graceDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)) {
                    results.receive()
                } ?: continue
            }
            done[next.first] = true
            videos[next.first] = next.second
        }
    }

    /**
     * Samples the resolved candidates (best ranked first, a few at most) in parallel and returns the
     * index of the one that can really deliver full HD; null keeps the ranking's choice.
     */
    private suspend fun chooseByMeasurement(order: List<Video.Server>, videos: Array<Video?>): Int? {
        val candidates = videos.indices
            .filter { videos[it] != null && !ServerRanker.isTorrentServer(order[it].name) }
            .take(PROBE_CANDIDATES)
        if (candidates.size < 2) return null
        val started = SystemClock.elapsedRealtime()
        val results = ArrayList<Pair<Int, SourceProbe.Result?>>()
        coroutineScope {
            val done = Channel<Pair<Int, SourceProbe.Result?>>(Channel.UNLIMITED)
            candidates.forEach { index -> launch(Dispatchers.IO) { done.send(index to SourceProbe.measure(videos[index]!!)) } }
            var deadline = started + MEASURE_MAX_MS
            while (results.size < candidates.size) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                val next = withTimeoutOrNull(remaining) { done.receive() } ?: break
                results += next
                // A full-HD stream the link sustains is good enough; give the others a moment, not seconds.
                if (next.second?.let { it.height >= 1000 && it.sustainable } == true) {
                    deadline = minOf(deadline, SystemClock.elapsedRealtime() + MEASURE_GRACE_MS)
                }
            }
            coroutineContext.cancelChildren()
        }
        val best = SourceProbe.pickBest(results.map { (index, result) -> index to result })
        Log.d(TAG, "measured ${results.joinToString { (i, r) -> "${order[i].name}=${r?.speedMbps?.let { "%.1f".format(it) } ?: "?"}Mbps@${r?.height ?: "?"}p" }} in ${SystemClock.elapsedRealtime() - started} ms -> ${best?.let { order[it].name }}")
        return best
    }

    private suspend fun playWithFailover(first: Video.Server?, failover: Boolean) {
        var candidate = first ?: nextCandidate()
        var isFailover = failover
        var lastTried = first
        while (candidate != null) {
            coroutineContext.ensureActive()
            lastTried = candidate
            val cached = cachedVideo(candidate)
            if (cached == null) _state.emit(State.LoadingVideo(candidate))
            val video = cached ?: resolve(candidate)
            if (video != null) {
                emitSuccess(video, candidate, isFailover)
                return
            }
            failedIds += candidate.id
            isFailover = true
            candidate = nextCandidate()
        }
        // Nothing else worked: a wrong-language stream still beats no picture.
        ranked.firstOrNull { it.id in wrongAudio && cachedVideo(it) != null }?.let { server ->
            wrongAudio -= server.id
            emitSuccess(cachedVideo(server)!!, server, isFailover)
            return
        }
        val server = lastTried ?: ranked.lastOrNull() ?: servers.lastOrNull() ?: return
        _state.emit(State.FailedLoadingVideo(lastError ?: Exception("All servers failed"), server))
    }

    /** Next untried server, preferring ones that are already resolved. */
    private fun nextCandidate(): Video.Server? {
        val remaining = ranked.filter { it.id !in failedIds && it.id != currentServerId && it.id != activeServerId }
        return remaining.firstOrNull { cachedVideo(it) != null } ?: remaining.firstOrNull()
    }

    private fun cachedVideo(server: Video.Server): Video? =
        resolved[server.id]?.takeIf { it.isFresh }?.video

    private suspend fun emitSuccess(video: Video, server: Video.Server, failover: Boolean) {
        currentServerId = server.id
        QualityMemory.currentServer = server.name
        Log.d(TAG, "playing ${server.name} (failover=$failover)")
        _state.emit(State.SuccessLoadingVideo(prepareVideo(video), server, failover))
        startQualityUpgrade(video, server)
    }

    /**
     * Picks the best picture automatically: if the server that started is below full HD, look at
     * the other servers' playlists and move to one that offers 1080p, keeping the position.
     */
    private fun startQualityUpgrade(playing: Video, server: Video.Server) {
        upgradeJob?.cancel()
        if (server.id == offlineServer?.id) return
        upgradeJob = viewModelScope.launch(Dispatchers.IO) {
            // Let playback settle first; the projector has one weak CPU cluster.
            kotlinx.coroutines.delay(UPGRADE_DELAY_MS)
            val prober = ProviderHealthChecker(providers = emptyList())
            Log.d(TAG, "upgrade check: probing ${server.name}")
            // Nothing stated in the playlist: go by the real picture. A bitrate guess takes 1080p HEVC for 720p.
            val known = withTimeoutOrNull(PROBE_TIMEOUT_MS) { prober.declaredHeight(playing) } ?: decodedHeight(server.name)
            QualityMemory.hint(server.name, known)
            val current = known ?: withTimeoutOrNull(PROBE_TIMEOUT_MS) { prober.probeHeight(playing) }
            if (current != null && current >= FULL_HD) return@launch
            // Below the minimum (or unreadable): no probe or time limit, every source gets its turn.
            val minHeight = UserPreferences.minQualityHeight
            val strict = minHeight > 0 && (current == null || current < minHeight)
            // Adaptive Videasy streams reach their top rung on their own; an unreadable playlist is no reason to churn.
            if (current == null && ServerRanker.isVideasy(server.name)) return@launch
            val deadline = if (strict) Long.MAX_VALUE else SystemClock.elapsedRealtime() + UPGRADE_WINDOW_MS
            var probes = 0
            // Ones already resolved come first (instant), then the torrents, which are the free 1080p
            // most titles have; web servers still unanswered can take 12 s each, so they wait.
            val web = ranked.filterNot { ServerRanker.isTorrentServer(it.name) }
            val order = web.filter { cachedVideo(it) != null } +
                ranked.filter { ServerRanker.isTorrentServer(it.name) } +
                web.filter { cachedVideo(it) == null }
            Log.d(TAG, "upgrade check: playing ${server.name} at ${current ?: "?"}p, ${order.size} candidates")
            for (candidate in order) {
                val torrent = ServerRanker.isTorrentServer(candidate.name)
                if (!torrent && (SystemClock.elapsedRealtime() > deadline || (!strict && probes >= UPGRADE_MAX_PROBES))) continue
                if (torrent && !UserPreferences.torrentEnabled) continue
                if (candidate.id == server.id || candidate.id in failedIds) continue
                // Quality differs per title, so while below the minimum another title's result is no reason to skip.
                if (!strict && QualityMemory.height(candidate.name)?.let { it in 1 until FULL_HD } == true) continue
                val video = cachedVideo(candidate) ?: resolve(candidate) ?: continue
                if (!torrent) probes++
                // Web probes that take longer than this are not worth waiting for; torrents are checked by swarm.
                val declared = if (torrent) prober.declaredHeight(video) else withTimeoutOrNull(PROBE_TIMEOUT_MS) { prober.declaredHeight(video) }
                QualityMemory.hint(candidate.name, declared)
                val height = declared ?: withTimeoutOrNull(PROBE_TIMEOUT_MS) { prober.probeHeight(video) }
                // A torrent is only worth switching to when its swarm is alive; check before cutting the picture.
                if (TorrentSpec.isTorrentUri(video.source) && height != null && height >= FULL_HD) {
                    val spec = TorrentSpec.parse(video.source)
                    val alive = spec != null && withTimeoutOrNull(TORRENT_PREWARM_MS) {
                        kotlinx.coroutines.runInterruptible { TorrentEngine.prewarm(spec, TORRENT_PREWARM_MS / 2) }
                    } == true
                    Log.d(TAG, "upgrade probe: ${candidate.name} swarm alive=$alive")
                    if (!alive) {
                        failedIds += candidate.id
                        continue
                    }
                }
                Log.d(TAG, "upgrade probe: ${candidate.name} -> ${height ?: "?"}p (playing ${current ?: "?"}p)")
                // Full HD is the goal, but anything under 720p moves up at once; the search carries on from there.
                val stepUp = current != null && current < HD && height != null && height >= HD
                if (height != null && (height >= FULL_HD || stepUp) && height > (current ?: 0)) {
                    if (currentServerId != server.id) return@launch
                    playbackMutex.withLock { emitSuccess(video, candidate, failover = true) }
                    return@launch
                }
            }
            // Every source was looked at and none has the minimum: tell the viewer why it stays soft.
            if (strict && current != null && currentServerId == server.id) _lowQuality.tryEmit(minHeight to current)
        }
    }

    /** Waits briefly for the first frame's format of the stream that just started. */
    private suspend fun decodedHeight(serverName: String): Int? = withTimeoutOrNull(DECODE_WAIT_MS) {
        var height = QualityMemory.decodedHeight(serverName)
        while (height == null) {
            kotlinx.coroutines.delay(250)
            height = QualityMemory.decodedHeight(serverName)
        }
        height
    }

    private suspend fun resolve(server: Video.Server): Video? {
        val video = resolveRaw(server) ?: return null
        resolved[server.id] = Resolved(video)
        // A stream dubbed only in languages the user doesn't speak waits behind the others.
        if (AudioGate.lacksSpokenAudio(video, UserPreferences.spokenLanguages)) {
            Log.w(TAG, "${server.name}: no audio in a spoken language, trying others first")
            wrongAudio += server.id
            return null
        }
        return video
    }

    private suspend fun resolveRaw(server: Video.Server): Video? {
        val started = SystemClock.elapsedRealtime()
        return try {
            val video = withTimeout(RESOLVE_TIMEOUT_MS) {
                UserPreferences.currentProvider!!.getVideo(server)
            }
            if (video.source.isEmpty()) throw Exception("No source found")
            Log.d(TAG, "resolved ${server.name} in ${SystemClock.elapsedRealtime() - started} ms")
            video
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "resolving ${server.name} timed out")
            lastError = Exception("${server.name}: timeout")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "resolving ${server.name} failed: ${e.message}")
            lastError = e
            null
        }
    }

    /**
     * Drops sideloaded subtitles in languages the user doesn't speak (they are all downloaded
     * when the player prepares) and pre-selects the last used subtitle.
     */
    private fun prepareVideo(video: Video): Video {
        val subtitles = if (UserPreferences.showAllTrackLanguages) {
            video.subtitles
        } else {
            val spoken = UserPreferences.spokenLanguages
            video.subtitles.filter {
                it.initialDefault || LanguageMatcher.isSpoken(LanguageMatcher.normalize(it.label), spoken)
            }
        }
        if (subtitles.size != video.subtitles.size) {
            Log.d(TAG, "kept ${subtitles.size}/${video.subtitles.size} sideloaded subtitles")
        }

        // If the provider didn't mark a default subtitle, pre-select the last one the user chose.
        val currentProviderLang = UserPreferences.currentProvider?.language ?: ""
        val hasDefaultAlready = subtitles.any { it.default }
        if (!hasDefaultAlready && currentProviderLang != "es") {
            if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {
                subtitles
                    .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }
                    ?.default = true
            }
        }
        return if (subtitles.size == video.subtitles.size) video else video.copy(subtitles = subtitles)
    }

    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        _subtitleState.emit(SubtitleState.Loading)
        val spoken = UserPreferences.spokenLanguages

        launch {
            try {
                val languages = OpenSubtitles.subLanguageIds(spoken)
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        OpenSubtitles.search(
                            query = videoType.tvShow.title,
                            season = videoType.season.number,
                            episode = videoType.number,
                            subLanguageId = languages,
                        )
                    }
                    is Video.Type.Movie -> {
                        OpenSubtitles.search(query = videoType.title, subLanguageId = languages)
                    }
                }.sortedWith(compareBy({ it.languageName }, { it.subDownloadsCnt }))

                Log.d(TAG, "OpenSubtitles: ${subtitles.size} results")
                _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e(TAG, "OpenSubtitles failed: ", e)
                _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
            }
        }

        launch {
            try {
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        SubDL.search(
                            filmName = videoType.tvShow.title,
                            seasonNumber = videoType.season.number,
                            episodeNumber = videoType.number,
                            type = "tv",
                            languages = spoken,
                        )
                    }
                    is Video.Type.Movie -> {
                        SubDL.search(
                            filmName = videoType.title,
                            type = "movie",
                            languages = spoken,
                        )
                    }
                }

                Log.d(TAG, "SubDL: ${subtitles.size} results")
                _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e(TAG, "SubDL failed: ", e)
                _subtitleState.emit(SubtitleState.FailedSubDLSubtitles(e))
            }
        }
    }

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
        try {
            val uri = OpenSubtitles.download(subtitle)
            _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e(TAG, "OpenSubtitles download failed: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
        }
    }

    fun downloadSubDLSubtitle(subtitle: SubDL.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        _subtitleState.emit(SubtitleState.DownloadingSubDLSubtitle)
        try {
            val uri = SubDL.download(subtitle)
            _subtitleState.emit(SubtitleState.SuccessDownloadingSubDLSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e(TAG, "SubDL download failed: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingSubDLSubtitle(e, subtitle))
        }
    }

    sealed class State {
        data object LoadingServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
        data class FailedLoadingServers(val error: Exception) : State()
        data class LoadingVideo(val server: Video.Server) : State()

        /** [failover] is true when this server replaces one that failed. */
        data class SuccessLoadingVideo(
            val video: Video,
            val server: Video.Server,
            val failover: Boolean = false,
        ) : State()

        /** All servers failed. */
        data class FailedLoadingVideo(val error: Exception, val server: Video.Server) : State()

        /** A server picked by hand did not work; the stream that was playing carries on. */
        data class ChoiceUnavailable(val server: Video.Server) : State()
    }

    sealed class SubtitleState {
        data object Loading : SubtitleState()
        data class SuccessOpenSubtitles(val subtitles: List<OpenSubtitles.Subtitle>) : SubtitleState()
        data class FailedOpenSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingOpenSubtitle : SubtitleState()
        data class SuccessDownloadingOpenSubtitle(val subtitle: OpenSubtitles.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingOpenSubtitle(val error: Exception, val subtitle: OpenSubtitles.Subtitle) : SubtitleState()

        data class SuccessSubDLSubtitles(val subtitles: List<SubDL.Subtitle>) : SubtitleState()
        data class FailedSubDLSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingSubDLSubtitle : SubtitleState()
        data class SuccessDownloadingSubDLSubtitle(val subtitle: SubDL.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingSubDLSubtitle(val error: Exception, val subtitle: SubDL.Subtitle) : SubtitleState()
    }

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val MAX_PARALLEL_RESOLUTIONS = 4
        private const val RESOLVE_TIMEOUT_MS = 12_000L
        /** After the first server resolves, wait this long for better-ranked ones. */
        private const val PICK_GRACE_MS = 2_000L
        private const val MIN_TO_COMPARE = 3
        /** Started server + backups resolved ahead of time. */
        private const val MAX_PRERESOLVED = 3
        private const val RESOLVED_MAX_AGE_MS = 10 * 60_000L
        private const val PREFETCH_ATTEMPTS = 3
        private const val FULL_HD = 1080
        private const val HD = 720
        private const val UPGRADE_DELAY_MS = 8_000L
        private const val TORRENT_PREWARM_MS = 90_000L
        private const val UPGRADE_MAX_PROBES = 6
        /** How long after start the other servers are checked for a sharper picture. */
        private const val UPGRADE_WINDOW_MS = 45_000L
        private const val PROBE_TIMEOUT_MS = 6_000L
        private const val DECODE_WAIT_MS = 15_000L
        /** Resolved servers whose speed is sampled before choosing the one to play. */
        private const val PROBE_CANDIDATES = 4
        private const val MEASURE_MAX_MS = 3_500L
        private const val MEASURE_GRACE_MS = 600L
    }
}
