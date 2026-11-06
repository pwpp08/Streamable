package com.streamflixreborn.streamflix.desktop.player

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.streamflixreborn.streamflix.desktop.PlayRequest
import com.streamflixreborn.streamflix.desktop.data.EpisodeState
import com.streamflixreborn.streamflix.desktop.data.Library
import com.streamflixreborn.streamflix.desktop.data.LibraryItem
import com.streamflixreborn.streamflix.desktop.data.Repo
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import uk.co.caprica.vlcj.media.MediaSlavePriority
import uk.co.caprica.vlcj.media.MediaSlaveType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer

data class Track(val id: Int, val name: String)

/** One playback session: finds a server, plays it in VLC, and keeps the library up to date. */
class PlayerController(val request: PlayRequest) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    /** The picture being shown; moves to another VLC player when the 4K upgrade takes over. */
    @Volatile var frames = VideoFrames()
        private set
    private var player: EmbeddedMediaPlayer? = null

    private fun newPlayer(target: VideoFrames): EmbeddedMediaPlayer? =
        VlcEngine.factory?.mediaPlayers()?.newEmbeddedMediaPlayer()?.also { mp ->
            mp.videoSurface().set(VlcEngine.factory!!.videoSurfaces().newVideoSurface(target, target, true))
        }

    // ---- State the UI reads ----------------------------------------------------------------
    var status by mutableStateOf("Finding a server…")
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(if (VlcEngine.factory == null) "VLC isn’t available: ${VlcEngine.error ?: "not found"}" else null)
        private set
    var playing by mutableStateOf(false)
        private set
    var timeMs by mutableLongStateOf(0L)
        private set
    var lengthMs by mutableLongStateOf(0L)
        private set
    var volume by mutableIntStateOf(UserPreferences.volume)
        private set
    var muted by mutableStateOf(false)
        private set
    var videoHeight by mutableIntStateOf(0)
        private set
    var buffering by mutableFloatStateOf(100f)
        private set
    val sources = mutableStateListOf<Source>()
    var current by mutableStateOf<Source?>(null)
        private set
    var audioTracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var subtitleTracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var audioTrack by mutableIntStateOf(-1)
        private set
    var subtitleTrack by mutableIntStateOf(-1)
        private set
    var finished by mutableStateOf(false)
        private set

    private var started = false
    private var proxyUrl: String? = null
    private var watchdog: Job? = null
    private val tried = mutableSetOf<String>()
    private var resolving: Job? = null

    private val type = request.videoType
    private val libraryBase: LibraryItem = when (type) {
        is Video.Type.Movie -> Library[Library.key(false, type.id)] ?: LibraryItem(
            key = Library.key(false, type.id), isTv = false, id = type.id, title = type.title, poster = type.poster.ifBlank { null },
            year = type.releaseDate.take(4).ifBlank { null },
        )
        is Video.Type.Episode -> Library[Library.key(true, type.tvShow.id)] ?: LibraryItem(
            key = Library.key(true, type.tvShow.id), isTv = true, id = type.tvShow.id, title = type.tvShow.title,
            poster = type.tvShow.poster, banner = type.tvShow.banner, year = type.tvShow.releaseDate?.take(4),
        )
    }

    init {
        com.streamflixreborn.streamflix.desktop.Automation.status = {
            "loading=$loading playing=$playing error=$error status=$status time=$timeMs/$lengthMs height=$videoHeight " +
                "current=${current?.server?.name}:${current?.label} frames=${frames.version.get()} " +
                "sources=" + sources.joinToString { it.server.name + "=" + it.label }
        }
        com.streamflixreborn.streamflix.desktop.Automation.pause = { togglePause() }
        player = newPlayer(frames)?.also(::attach)
        if (player != null) start()
    }

    /** Wires a VLC player's events to the UI state; only the player on screen gets through. */
    private fun attach(mp: EmbeddedMediaPlayer) {
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) { if (mediaPlayer === player) playing = true }
            override fun paused(mediaPlayer: MediaPlayer) { if (mediaPlayer === player) playing = false }
            override fun stopped(mediaPlayer: MediaPlayer) { if (mediaPlayer === player) playing = false }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { if (mediaPlayer === player) buffering = newCache }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                if (mediaPlayer !== player) return
                timeMs = newTime
                if (!started && newTime > 0) onStarted(mediaPlayer)
            }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { if (mediaPlayer === player) lengthMs = newLength }
            override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) {
                if (newCount > 0 && mediaPlayer === player) mediaPlayer.submit { videoHeight = mediaPlayer.video().videoDimension()?.height ?: 0 }
            }
            override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) {
                if (mediaPlayer === player) mediaPlayer.submit { refreshTracks(mediaPlayer) }
            }
            override fun finished(mediaPlayer: MediaPlayer) {
                if (mediaPlayer !== player) return
                scope.launch {
                    // A dropped stream also "finishes"; only the last minute counts as the end.
                    if (lengthMs > 0 && lengthMs - timeMs < 60_000) {
                        saveProgress(final = true, completed = true)
                        finished = true
                    } else {
                        failover("The stream stopped")
                    }
                }
            }
            override fun error(mediaPlayer: MediaPlayer) {
                if (mediaPlayer !== player) return
                scope.launch { failover("Playback error") }
            }
        })
    }

    private fun start() {
        resolving = scope.launch {
            val servers = runCatching { Sources.servers(type) }.getOrElse {
                error = "Couldn’t get servers: ${it.message}"
                loading = false
                return@launch
            }
            if (servers.isEmpty()) {
                error = "No servers found for this title."
                loading = false
                return@launch
            }
            sources.clear()
            sources.addAll(servers.mapIndexed { i, s -> Source(s, i) })
            status = "Checking ${servers.size} servers…"
            val first = CompletableDeferred<Source?>()
            launch {
                val pick = first.await()
                if (pick == null) {
                    error = if (sources.any { it.state is Source.State.Ready })
                        "No server has this in 1080p or better right now. You can still pick a lower-quality one."
                    else "None of the servers worked. Try again later."
                    loading = false
                } else if (current == null) {
                    play(pick, request.startMs)
                }
            }
            Sources.resolveAll(servers, onUpdate = { src ->
                scope.launch {
                    val i = sources.indexOfFirst { it.order == src.order }
                    if (i >= 0) sources[i] = src
                }
            }, firstPick = first)
        }
        scope.launch {
            while (isActive) {
                delay(5_000)
                saveProgress()
            }
        }
    }

    fun play(source: Source, startMs: Long = timeMs, manual: Boolean = false) {
        upgrading?.cancel()
        guard?.cancel()
        val mp = player ?: return
        val video = source.video ?: return
        tried += source.server.id
        current = source
        started = false
        loading = true
        finished = false
        error = null
        status = "Starting ${source.server.name}" + (source.height?.let { " · ${Source.qualityName(it)}" } ?: "")
        frames.clear()
        proxyUrl?.let(StreamProxy::release)
        val url = StreamProxy.register(video)
        proxyUrl = url
        // Playback starts at 1080p; only a server picked by hand may go straight to 4K.
        val cap = if (manual) 2160 else UserPreferences.START_RESOLUTION
        val options = mutableListOf(
            ":adaptive-logic=highest",
            ":avcodec-hw=" + VlcEngine.hwDecoder,
            ":adaptive-maxheight=$cap",
            ":network-caching=2500",
        )
        if (startMs > 5_000) options += ":start-time=${startMs / 1000.0}"
        Log.i("Player", "play ${source.server.name} ${source.height} -> $url")
        mp.media().play(url, *options.toTypedArray())
        mp.audio().setVolume(volume)
        // External subtitles from the server; which one shows (if any) is decided once it starts.
        video.subtitles.forEach { sub ->
            mp.media().slaves().add(MediaSlaveType.SUBTITLE, MediaSlavePriority.LOWEST, StreamProxy.registerSubtitle(video, sub.file))
        }
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(25_000)
            if (!started) failover("${source.server.name} didn’t start")
        }
    }

    private fun onStarted(mp: MediaPlayer) {
        started = true
        scope.launch {
            loading = false
            status = ""
            watchdog?.cancel()
        }
        guardQuality()
        maybeUpgrade()
        mp.submit {
            // Subtitles follow the setting: the preferred language if there is one, else off.
            val pref = UserPreferences.subtitleLanguage
            val subs = mp.subpictures().trackDescriptions().filter { it.id() >= 0 }
            val match = if (pref.isBlank()) null else subs.firstOrNull { it.description().contains(pref, ignoreCase = true) }
            mp.subpictures().setTrack(match?.id() ?: -1)
            refreshTracks(mp)
        }
    }

    private var guard: Job? = null

    /** A stream that turns out to be well below 1080p (adaptive ones sometimes start low) is dropped. */
    private fun guardQuality() {
        val src = current ?: return
        guard?.cancel()
        guard = scope.launch {
            repeat(12) {
                delay(1_000)
                val d = player?.video()?.videoDimension()
                if (d != null && d.height > 0) {
                    val sharp = d.width >= 1800 || d.height >= 1000
                    if (!sharp && Sources.rank(sources).any { it.server.id !in tried }) {
                        failover("${src.server.name} is only ${d.height}p")
                    }
                    return@launch
                }
            }
        }
    }

    // ---- Background 4K upgrade ---------------------------------------------------------------

    private var upgrading: Job? = null
    private val upgradeTried = mutableSetOf<String>()

    private class Probe {
        @Volatile var playing = false
        @Volatile var failed = false
        @Volatile var time = 0L
        @Volatile var beats = 0
    }

    /**
     * Once the 1080p picture has settled, tests 4K servers one at a time on a second, muted
     * player. A server only counts if it reaches real 4K and then holds real-time speed for
     * a while; then the second player is lined up with the first and swapped in.
     */
    private fun maybeUpgrade() {
        if (!UserPreferences.upgrade4k || VlcEngine.factory == null) return
        val cur = current ?: return
        upgrading?.cancel()
        upgrading = scope.launch {
            delay(8_000)
            if (videoHeight >= 2000) return@launch
            var tests = 0
            var waited = 0
            while (isActive && tests < 3 && waited < 45) {
                val cand = Sources.fourK(sources.toList()).firstOrNull { it.server.id !in upgradeTried && it.server.id != current?.server?.id }
                if (cand == null) {
                    if (sources.none { it.state is Source.State.Pending }) break
                    delay(2_000)
                    waited++
                    continue
                }
                upgradeTried += cand.server.id
                tests++
                if (current === cur && tryUpgrade(cand, cur)) return@launch
            }
        }
    }

    private suspend fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            delay(100)
        }
        return cond()
    }

    private suspend fun tryUpgrade(src: Source, from: Source): Boolean {
        val video = src.video ?: return false
        val target = VideoFrames()
        val mp = newPlayer(target) ?: return false
        val probe = Probe()
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) { probe.playing = true }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { probe.time = newTime; probe.beats++ }
            override fun error(mediaPlayer: MediaPlayer) { probe.failed = true }
            override fun finished(mediaPlayer: MediaPlayer) { probe.failed = true }
        })
        attach(mp)
        val url = StreamProxy.register(video, isolated = true)
        var swapped = false
        return try {
            Log.i("Player", "4K test: ${src.server.name}")
            val options = arrayOf(
                ":adaptive-logic=highest", ":avcodec-hw=" + VlcEngine.hwDecoder, ":adaptive-maxheight=2160", ":network-caching=2500",
                ":start-time=${timeMs / 1000.0}",
            )
            mp.media().play(url, *options)
            mp.audio().isMute = true
            video.subtitles.forEach { sub ->
                mp.media().slaves().add(MediaSlaveType.SUBTITLE, MediaSlavePriority.LOWEST, StreamProxy.registerSubtitle(video, sub.file))
            }

            // 1. It has to start, and reach real 4K.
            fun is4k() = mp.video().videoDimension()?.let { it.height >= 2000 || it.width >= 3600 } == true
            waitFor(25_000) { probe.failed || (probe.playing && probe.beats > 2 && is4k()) }
            if (probe.failed || !is4k()) return false

            // 2. It has to keep up in real time for a while.
            val t0 = probe.time
            val w0 = System.nanoTime()
            waitFor(12_000) { probe.failed }
            val advanced = probe.time - t0
            val wall = (System.nanoTime() - w0) / 1_000_000
            if (probe.failed || advanced < wall * (System.getProperty("streamflix.upgradeRatio")?.toDoubleOrNull() ?: 0.92)) {
                Log.i("Player", "4K test failed: ${src.server.name} advanced $advanced in $wall ms")
                return false
            }

            // 3. Line it up with what's playing, then swap.
            var lead = 2_500L
            repeat(4) {
                if (current !== from) return false
                val want = timeMs + lead
                val seenFrames = target.version.get()
                probe.beats = 0
                mp.controls().setTime(want)
                waitFor(10_000) {
                    probe.failed || (probe.beats > 1 && kotlin.math.abs(probe.time - want) < 2_500 && target.version.get() > seenFrames)
                }
                if (probe.failed || probe.beats <= 1) return false
                val drift = probe.time - timeMs
                if (kotlin.math.abs(drift) <= 400) {
                    swapTo(mp, target, url, src)
                    swapped = true
                    return true
                }
                lead = (lead - drift).coerceIn(500L, 9_000L)
            }
            false
        } finally {
            if (!swapped) {
                runCatching { mp.controls().stop() }
                Thread { runCatching { mp.release() } }.start()
                StreamProxy.release(url)
            }
        }
    }

    private fun swapTo(mp: EmbeddedMediaPlayer, target: VideoFrames, url: String, src: Source) {
        val old = player
        val oldFrames = frames
        val oldUrl = proxyUrl
        val wasPlaying = playing
        val prevSubtitle = subtitleTracks.firstOrNull { it.id == subtitleTrack }?.name
        Log.i("Player", "switching to 4K: ${src.server.name}")
        mp.audio().setVolume(volume)
        mp.audio().isMute = muted
        if (!wasPlaying) mp.controls().pause()
        player = mp
        frames = target
        proxyUrl = url
        current = src
        tried += src.server.id
        playing = wasPlaying
        lengthMs = mp.status().length().takeIf { it > 0 } ?: lengthMs
        videoHeight = mp.video().videoDimension()?.height ?: videoHeight
        mp.submit {
            // Keep the subtitle choice: the same track by name, else the language setting.
            val subs = mp.subpictures().trackDescriptions().filter { it.id() >= 0 }
            val pref = UserPreferences.subtitleLanguage
            val match = if (prevSubtitle != null) subs.firstOrNull { it.description() == prevSubtitle }
            else if (pref.isBlank()) null else subs.firstOrNull { it.description().contains(pref, ignoreCase = true) }
            mp.subpictures().setTrack(match?.id() ?: -1)
            refreshTracks(mp)
        }
        old?.audio()?.isMute = true
        Thread {
            Thread.sleep(1_500)
            runCatching { old?.controls()?.stop() }
            runCatching { old?.release() }
            oldUrl?.let(StreamProxy::release)
            oldFrames.clear()
        }.start()
    }

    private fun refreshTracks(mp: MediaPlayer) {
        val audio = mp.audio().trackDescriptions().filter { it.id() >= 0 }.map { Track(it.id(), it.description()) }
        val subs = mp.subpictures().trackDescriptions().filter { it.id() >= 0 }.map { Track(it.id(), it.description()) }
        val a = mp.audio().track()
        val s = mp.subpictures().track()
        val h = mp.video().videoDimension()?.height ?: 0
        scope.launch {
            audioTracks = audio
            subtitleTracks = subs
            audioTrack = a
            subtitleTrack = s
            if (h > 0) videoHeight = h
        }
    }

    /** Moves on to the next best untried server, keeping the position. */
    private fun failover(reason: String) {
        if (finished) return
        Log.w("Player", "failover: $reason")
        val next = Sources.rank(sources).firstOrNull { it.server.id !in tried }
        if (next != null) {
            status = "$reason — trying ${next.server.name}"
            play(next, timeMs.takeIf { it > 0 } ?: request.startMs)
        } else if (sources.any { it.state is Source.State.Pending }) {
            status = "$reason — waiting for more servers…"
            scope.launch {
                while (sources.any { it.state is Source.State.Pending } && Sources.rank(sources).none { it.server.id !in tried }) delay(500)
                Sources.rank(sources).firstOrNull { it.server.id !in tried }?.let { play(it, timeMs.takeIf { t -> t > 0 } ?: request.startMs) }
                    ?: run { error = "None of the servers worked."; loading = false }
            }
        } else {
            error = "None of the servers worked. Try again later."
            loading = false
        }
    }

    fun togglePause() {
        val mp = player ?: return
        if (finished) {
            play(current ?: return, 0)
            return
        }
        if (mp.status().isPlaying) mp.controls().pause() else mp.controls().play()
    }

    fun seekTo(ms: Long) {
        val mp = player ?: return
        val target = ms.coerceIn(0, (lengthMs - 1000).coerceAtLeast(0))
        mp.controls().setTime(target)
        timeMs = target
    }

    fun seekBy(deltaMs: Long) = seekTo(timeMs + deltaMs)

    fun changeVolume(value: Int) {
        volume = value.coerceIn(0, 150)
        muted = false
        player?.audio()?.setVolume(volume)
        player?.audio()?.isMute = false
        UserPreferences.volume = volume
    }

    fun toggleMute() {
        muted = !muted
        player?.audio()?.isMute = muted
    }

    fun selectAudio(id: Int) {
        player?.audio()?.setTrack(id)
        audioTrack = id
    }

    fun selectSubtitle(id: Int) {
        player?.subpictures()?.setTrack(id)
        subtitleTrack = id
    }

    fun saveProgress(final: Boolean = false, completed: Boolean = false) {
        if (!started && !completed) return
        val position = if (completed) lengthMs else timeMs
        val length = lengthMs
        if (length <= 0) return
        when (type) {
            is Video.Type.Movie -> Library.saveMovieProgress(libraryBase, position, length)
            is Video.Type.Episode -> Library.saveEpisodeProgress(
                libraryBase,
                EpisodeState(type.id, type.season.number, type.number, type.title),
                position,
                length,
            )
        }
        if (final) Library.flush()
    }

    /** The episode after this one, if any (same season, else the first of the next season). */
    suspend fun nextEpisode(): PlayRequest? {
        val ep = type as? Video.Type.Episode ?: return null
        return runCatching {
            val show = Repo.show(ep.tvShow.id)
            val seasons = show.seasons.filter { it.number > 0 }.sortedBy { it.number }
            val season = seasons.firstOrNull { it.number == ep.season.number } ?: return null
            val list = Repo.episodes(season.id)
            val next = list.firstOrNull { it.number == ep.number + 1 }
            val (s, e) = if (next != null) season to next else {
                val ns = seasons.firstOrNull { it.number > season.number } ?: return null
                ns to (Repo.episodes(ns.id).minByOrNull { it.number } ?: return null)
            }
            PlayRequest(
                Repo.episodeType(show, s.number, s.title, e),
                show.title,
                "S${s.number} · E${e.number}" + (e.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            )
        }.getOrNull()
    }

    fun release() {
        saveProgress(final = true)
        upgrading?.cancel()
        guard?.cancel()
        resolving?.cancel()
        scope.cancel()
        player?.let { mp ->
            runCatching { mp.controls().stop() }
            runCatching { mp.release() }
        }
        proxyUrl?.let(StreamProxy::release)
        frames.clear()
    }
}
