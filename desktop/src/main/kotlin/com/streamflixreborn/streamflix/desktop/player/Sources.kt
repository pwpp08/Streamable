package com.streamflixreborn.streamflix.desktop.player

import android.util.Log
import com.streamflixreborn.streamflix.desktop.data.Repo
import com.streamflixreborn.streamflix.extractors.BROWSER_ONLY
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.health.HlsPlaylistParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/** A server and what came of resolving it. */
data class Source(
    val server: Video.Server,
    val order: Int,
    val state: State = State.Pending,
) {
    sealed interface State {
        data object Pending : State
        data class Ready(val video: Video, val height: Int?) : State
        data class Failed(val reason: String) : State
    }

    val video get() = (state as? State.Ready)?.video
    val height get() = (state as? State.Ready)?.height

    val label: String
        get() = when (val s = state) {
            State.Pending -> "…"
            is State.Failed -> "Failed"
            is State.Ready -> s.height?.let { qualityName(it) } ?: "Auto"
        }

    companion object {
        fun qualityName(h: Int) = when {
            h >= 2160 -> "4K"
            h >= 1440 -> "1440p"
            else -> "${h}p"
        }
    }
}

/**
 * Resolves a title's servers several at a time and ranks them: 4K first, then the sharpest
 * picture down to 1080p, then streams of unknown height (usually adaptive), then the
 * provider's order. Anything known to be below 1080p is never picked automatically.
 */
object Sources {
    private const val TAG = "Sources"

    suspend fun servers(type: Video.Type): List<Video.Server> = withContext(Dispatchers.IO) {
        val id = when (type) {
            is Video.Type.Movie -> type.id
            is Video.Type.Episode -> type.id
        }
        Repo.provider.getServers(id, type).filter { it.name !in BROWSER_ONLY && !it.src.startsWith("torrent:") }
    }

    suspend fun resolve(server: Video.Server): Source.State = withContext(Dispatchers.IO) {
        runCatching {
            val video = withTimeoutOrNull(25_000) { Repo.provider.getVideo(server) } ?: error("timed out")
            Source.State.Ready(video, probeHeight(video))
        }.getOrElse { Source.State.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /** Reads the master playlist's best variant, so servers can be compared before playing. */
    private fun probeHeight(video: Video): Int? = runCatching {
        val text = if (video.source.startsWith("data:")) {
            val payload = video.source.substringAfter(',')
            String(java.util.Base64.getMimeDecoder().decode(payload))
        } else {
            val looksHls = video.type?.contains("mpegurl", true) == true || video.source.substringBefore('?').endsWith(".m3u8", true)
            if (!looksHls) return@runCatching null
            val req = Request.Builder().url(video.source).apply { video.headers?.forEach { (k, v) -> header(k, v) } }.build()
            NetworkClient.default.newCall(req).execute().use { r -> if (r.isSuccessful) r.peekBody(256 * 1024).string() else null }
                ?: return@runCatching null
        }
        HlsPlaylistParser.maxHeight(text)
    }.getOrNull()

    /** Ready and at least 1080p (or of unknown height). */
    fun eligible(source: Source): Boolean =
        source.state is Source.State.Ready && (source.height ?: UserPreferences.MIN_RESOLUTION) >= UserPreferences.MIN_RESOLUTION

    fun rank(sources: List<Source>): List<Source> {
        val cap = UserPreferences.maxResolution
        return sources.filter(::eligible).sortedWith(
            compareByDescending<Source> { s -> s.height?.let { minOf(it, cap) } ?: (UserPreferences.MIN_RESOLUTION - 1) }
                .thenBy { it.order },
        )
    }

    /**
     * Resolves everything (four at a time), reporting each result to [onUpdate]. Completes
     * [firstPick] with the best source once one at the cap is ready, or a few seconds after
     * the first usable one, whichever comes first.
     */
    suspend fun resolveAll(
        servers: List<Video.Server>,
        onUpdate: (Source) -> Unit,
        firstPick: CompletableDeferred<Source?>,
    ) = coroutineScope {
        val cap = UserPreferences.maxResolution
        val results = java.util.concurrent.ConcurrentHashMap<Int, Source>()
        val gate = Semaphore(4)
        var firstReadyAt = 0L
        val deadline = launch {
            // Settle for the best so far a few seconds after the first stream resolved.
            while (!firstPick.isCompleted) {
                kotlinx.coroutines.delay(250)
                if (firstReadyAt > 0 && System.currentTimeMillis() - firstReadyAt > 6_000) {
                    firstPick.complete(rank(results.values.toList()).firstOrNull())
                }
            }
        }
        servers.mapIndexed { i, server ->
            async {
                gate.withPermit {
                    val state = resolve(server)
                    val source = Source(server, i, state)
                    results[i] = source
                    onUpdate(source)
                    if (eligible(source)) {
                        if (firstReadyAt == 0L) firstReadyAt = System.currentTimeMillis()
                        val h = source.height
                        if (h != null && h >= cap) firstPick.complete(source)
                    } else if (state is Source.State.Ready) {
                        Log.i(TAG, "${server.name}: below 1080p (${source.height}p), skipped")
                    } else {
                        Log.i(TAG, "${server.name}: ${(state as Source.State.Failed).reason}")
                    }
                }
            }
        }.forEach { it.await() }
        firstPick.complete(rank(results.values.toList()).firstOrNull())
        deadline.cancel()
    }
}
