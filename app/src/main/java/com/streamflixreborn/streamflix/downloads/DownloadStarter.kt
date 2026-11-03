package com.streamflixreborn.streamflix.downloads

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.fragments.player.engine.ServerRanker
import com.streamflixreborn.streamflix.fragments.player.engine.StickyServers
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.sync.SyncProviders
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * "Resolve for download": the same server ranking as the player (sticky server, health score,
 * provider order), then the first server whose stream actually opens is downloaded. Stream links
 * are often short-lived or IP/token-bound, so the request is built and handed to the service
 * right after resolving; a server whose stream fails to open is skipped automatically.
 */
@OptIn(UnstableApi::class)
object DownloadStarter {

    private const val TAG = "DownloadStarter"
    private const val RESOLVE_TIMEOUT_MS = 15_000L
    private const val PREPARE_TIMEOUT_MS = 30_000L
    private const val MAX_SUBTITLE_BYTES = 5L * 1024 * 1024
    private const val STOP_REASON_PAUSED = 1

    data class Target(
        val providerName: String,
        val videoType: Video.Type,
        val title: String,
        val subtitle: String,
        val poster: String?,
    ) {
        val id: String get() = DownloadIds.of(providerName, videoType)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    fun start(context: Context, target: Target, triedServers: List<String> = emptyList()) {
        val app = context.applicationContext
        if (!DownloadUtil.isSupported(app)) return
        DownloadTracker.ensureStarted(app)
        val id = target.id
        if (jobs[id]?.isActive == true) return

        val baseMeta = DownloadMetadata(
            title = target.title,
            subtitle = target.subtitle,
            poster = target.poster,
            provider = target.providerName,
            triedServers = triedServers,
            videoType = StoredVideoType.of(target.videoType),
        )
        DownloadTracker.setResolving(id, baseMeta)
        jobs[id] = scope.launch {
            try {
                if (DownloadTracker.hasDownload(id)) removeAndWait(app, id)
                startResolved(app, id, target, baseMeta)
            } catch (e: CancellationException) {
                DownloadTracker.clearPending(id)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download of $id failed before starting", e)
                DownloadTracker.setResolveFailed(id, baseMeta, e.message)
            } finally {
                jobs.remove(id)
            }
        }
    }

    /** "Try another server": starts again, skipping every server already tried for this title. */
    fun retry(context: Context, item: DownloadTracker.Item) {
        val meta = item.metadata ?: return
        start(
            context,
            Target(meta.provider, meta.videoType.toVideoType(), meta.title, meta.subtitle, meta.poster),
            triedServers = (meta.triedServers + listOfNotNull(meta.server)).distinct(),
        )
    }

    fun pause(context: Context, id: String) = setStopReason(context, id, STOP_REASON_PAUSED)

    fun resume(context: Context, id: String) = setStopReason(context, id, Download.STOP_REASON_NONE)

    fun remove(context: Context, id: String) {
        val app = context.applicationContext
        jobs.remove(id)?.cancel()
        DownloadTracker.clearPending(id)
        if (!DownloadTracker.hasDownload(id)) return
        runCatching {
            DownloadService.sendRemoveDownload(app, StreamflixDownloadService::class.java, id, false)
        }.onFailure {
            Log.w(TAG, "service unavailable, removing directly", it)
            DownloadUtil.downloadManager(app).removeDownload(id)
        }
    }

    /** Applies the Wi-Fi only preference to the running manager. */
    fun applyRequirements(context: Context) {
        val app = context.applicationContext
        DownloadUtil.downloadManager(app).requirements = DownloadPreferences.requirements(app)
    }

    /** Starts the service when downloads are waiting (after process death, or the Wi-Fi came back). */
    fun resumePending(context: Context) {
        val app = context.applicationContext
        val hasWork = DownloadTracker.items.value.values.any {
            it.status == DownloadTracker.Status.QUEUED ||
                it.status == DownloadTracker.Status.WAITING ||
                it.status == DownloadTracker.Status.DOWNLOADING
        }
        if (!hasWork) return
        runCatching { DownloadService.start(app, StreamflixDownloadService::class.java) }
            .onFailure { Log.w(TAG, "starting the download service failed", it) }
    }

    private fun setStopReason(context: Context, id: String, reason: Int) {
        val app = context.applicationContext
        runCatching {
            DownloadService.sendSetStopReason(app, StreamflixDownloadService::class.java, id, reason, false)
        }.onFailure {
            Log.w(TAG, "service unavailable, updating directly", it)
            DownloadUtil.downloadManager(app).setStopReason(id, reason)
        }
    }

    private suspend fun removeAndWait(context: Context, id: String) {
        val waiter = DownloadTracker.removalWaiter(id)
        withContext(Dispatchers.Main) {
            runCatching {
                DownloadService.sendRemoveDownload(context, StreamflixDownloadService::class.java, id, false)
            }.onFailure { DownloadUtil.downloadManager(context).removeDownload(id) }
        }
        DownloadTracker.awaitRemoval(waiter)
    }

    private suspend fun startResolved(context: Context, id: String, target: Target, baseMeta: DownloadMetadata) {
        val provider = SyncProviders.byName(target.providerName)
            ?: throw IOException(context.getString(R.string.download_error_no_provider))
        val type = target.videoType
        val typeId = when (type) {
            is Video.Type.Movie -> type.id
            is Video.Type.Episode -> type.id
        }
        val servers = provider.getServers(typeId, type)
        val sticky = StickyServers.get(context, StickyServers.key(provider, type))
        val ranked = DownloadRules.untried(
            ServerRanker.rank(servers, stickyName = sticky),
            baseMeta.triedServers,
        ) { it.name }
        Log.d(TAG, "download $id: ${ranked.size} candidate servers: ${ranked.joinToString { it.name }}")

        val tried = baseMeta.triedServers.toMutableList()
        var attempts = 0
        var resolutions = 0
        var lastError: Exception? = null
        for (server in ranked) {
            if (attempts >= DownloadRules.MAX_STREAM_ATTEMPTS || resolutions >= DownloadRules.MAX_RESOLUTIONS) break
            resolutions++
            val video = try {
                withTimeout(RESOLVE_TIMEOUT_MS) { provider.getVideo(server) }
                    .takeIf { it.source.isNotEmpty() } ?: throw IOException("${server.name}: no source")
            } catch (e: TimeoutCancellationException) {
                lastError = IOException("${server.name}: timeout")
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                continue
            }

            attempts++
            tried += server.name
            val entry = DownloadHeaderStore.Entry(video.headers ?: emptyMap(), video.maintainToken)
            val meta = baseMeta.copy(server = server.name, triedServers = tried.distinct())
            val request = try {
                buildRequest(context, id, video, entry, meta)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Typically a link bound to another IP or an expired token: next server.
                Log.w(TAG, "download $id: ${server.name} stream failed: ${e.message}")
                lastError = e
                continue
            }

            val subtitles = saveSubtitles(context, id, video, entry)
            val finalRequest = request.withData(meta.copy(subtitles = subtitles).encode())
            DownloadHeaderStore.put(context, id, entry)
            withContext(Dispatchers.Main) {
                DownloadTracker.markAdding(id)
                send(context, finalRequest)
                Toast.makeText(context, context.getString(R.string.download_started, target.title), Toast.LENGTH_SHORT).show()
            }
            return
        }
        DownloadTracker.setResolveFailed(
            id,
            baseMeta.copy(triedServers = tried.distinct()),
            lastError?.message ?: context.getString(R.string.download_error_no_servers),
        )
    }

    private fun send(context: Context, request: DownloadRequest) {
        try {
            DownloadService.sendAddDownload(context, StreamflixDownloadService::class.java, request, true)
        } catch (e: IllegalStateException) {
            // App went to the background while resolving (foreground service start not allowed):
            // the in-process manager still downloads while the process lives.
            Log.w(TAG, "foreground start refused, adding to the manager directly", e)
            DownloadUtil.downloadManager(context).addDownload(request)
        }
    }

    private fun DownloadRequest.withData(data: ByteArray): DownloadRequest =
        DownloadRequest.Builder(id, uri)
            .setMimeType(mimeType)
            .setStreamKeys(streamKeys)
            .setCustomCacheKey(customCacheKey)
            .setKeySetId(keySetId)
            .setData(data)
            .build()

    private fun mimeTypeOf(video: Video): String? {
        video.type?.let { return it }
        val source = video.source
        if (source.startsWith("data:") && source.substringBefore(',').contains("mpegurl", ignoreCase = true)) {
            return MimeTypes.APPLICATION_M3U8
        }
        return null
    }

    private suspend fun buildRequest(
        context: Context,
        id: String,
        video: Video,
        entry: DownloadHeaderStore.Entry,
        meta: DownloadMetadata,
    ): DownloadRequest {
        val uri = Uri.parse(video.source)
        val mimeType = mimeTypeOf(video)
        return when (Util.inferContentTypeForUriAndMimeType(uri, mimeType)) {
            C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_SS ->
                withTimeout(PREPARE_TIMEOUT_MS) { prepareAdaptive(context, id, uri, mimeType, entry, meta) }
            C.CONTENT_TYPE_RTSP -> throw IOException(context.getString(R.string.download_error_unsupported))
            else -> {
                probeProgressive(video.source, entry)
                DownloadRequest.Builder(id, uri)
                    .setMimeType(mimeType)
                    .setData(meta.encode())
                    .build()
            }
        }
    }

    /** Loads the manifest (this is where expired/IP-bound links fail) and picks the tracks. */
    private suspend fun prepareAdaptive(
        context: Context,
        id: String,
        uri: Uri,
        mimeType: String?,
        entry: DownloadHeaderStore.Entry,
        meta: DownloadMetadata,
    ): DownloadRequest = withContext(Dispatchers.Main) {
        val (maxWidth, maxHeight) = DownloadRules.videoSizeCap(DownloadPreferences.saveSpace(context))
        val spoken = UserPreferences.spokenLanguages.toTypedArray()
        val parameters = DownloadHelper.getDefaultTrackSelectorParameters(context).buildUpon()
            .setMaxVideoSize(maxWidth, maxHeight)
            .setForceHighestSupportedBitrate(true)
            .setPreferredAudioLanguages(*spoken)
            .setPreferredTextLanguages(*spoken)
            .build()
        val mediaItem = MediaItem.Builder().setUri(uri).setMimeType(mimeType).build()
        val helper = DownloadHelper.Factory()
            .setDataSourceFactory(DownloadUtil.upstreamFactory(context, entry))
            .setRenderersFactory(DefaultRenderersFactory(context))
            .setTrackSelectionParameters(parameters)
            .create(mediaItem)
        try {
            suspendCancellableCoroutine { cont ->
                helper.prepare(object : DownloadHelper.Callback {
                    override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                })
            }
            // Video: best track within the size cap. Audio: one per spoken language (the default
            // track when none matches). Text: tracks in spoken languages.
            for (period in 0 until helper.periodCount) {
                helper.clearTrackSelections(period)
                helper.addTrackSelection(period, parameters)
            }
            if (spoken.isNotEmpty()) {
                helper.addAudioLanguagesToSelection(*spoken)
                helper.addTextLanguagesToSelection(false, *spoken)
            }
            helper.getDownloadRequest(id, meta.encode())
        } finally {
            helper.release()
        }
    }

    /** One-byte request so a dead or token-bound progressive link fails here, not mid-download. */
    private fun probeProgressive(url: String, entry: DownloadHeaderStore.Entry) {
        if (!url.startsWith("http", ignoreCase = true)) return
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .apply { entry.headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=0-0")
            .build()
        DownloadUtil.httpClient(entry.maintainToken).newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val type = response.header("Content-Type")?.lowercase().orEmpty()
            if (type.startsWith("text/html")) throw IOException("Not a video (text/html)")
        }
    }

    /** Sideloaded subtitles in spoken languages, saved to `subs/<id>/` for offline use. */
    private fun saveSubtitles(context: Context, id: String, video: Video, entry: DownloadHeaderStore.Entry): List<LocalSubtitle> {
        val wanted = DownloadRules.spokenSubtitles(video.subtitles, UserPreferences.spokenLanguages)
        val dir = DownloadUtil.subtitlesDir(context, id)
        dir.deleteRecursively()
        if (wanted.isEmpty()) return emptyList()
        dir.mkdirs()
        val client = DownloadUtil.httpClient(entry.maintainToken)
        return wanted.mapIndexedNotNull { index, subtitle ->
            if (!subtitle.file.startsWith("http", ignoreCase = true)) return@mapIndexedNotNull null
            try {
                val request = Request.Builder()
                    .url(subtitle.file)
                    .header("User-Agent", NetworkClient.USER_AGENT)
                    .apply { entry.headers.forEach { (k, v) -> header(k, v) } }
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body ?: return@mapIndexedNotNull null
                    if (!response.isSuccessful || body.contentLength() > MAX_SUBTITLE_BYTES) return@mapIndexedNotNull null
                    val bytes = body.bytes()
                    val head = String(bytes, 0, minOf(bytes.size, 16), Charsets.UTF_8)
                    val ext = DownloadRules.subtitleExtension(subtitle.file, response.header("Content-Type"), head)
                    val file = File(dir, "$index.$ext")
                    file.writeBytes(bytes)
                    LocalSubtitle(subtitle.label, file.absolutePath, subtitle.default)
                }
            } catch (e: Exception) {
                Log.w(TAG, "subtitle ${subtitle.label} not saved: ${e.message}")
                null
            }
        }
    }
}
