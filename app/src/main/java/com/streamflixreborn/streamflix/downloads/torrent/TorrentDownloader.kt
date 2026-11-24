package com.streamflixreborn.streamflix.downloads.torrent

import android.content.Context
import android.net.ConnectivityManager
import android.os.Environment
import android.util.Log
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.downloads.DownloadPreferences
import com.streamflixreborn.streamflix.downloads.mkv.MkvSubtitleMuxer
import com.streamflixreborn.streamflix.downloads.mkv.SrtParser
import com.streamflixreborn.streamflix.downloads.mp4.Mp4SubtitleMuxer
import com.streamflixreborn.streamflix.torrent.TorrentEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads whole torrent files to the phone: one at a time, kept on disk across restarts, then
 * (optionally) a subtitle is bundled in and the finished file is put where other players can open it.
 */
object TorrentDownloader {
    private const val TAG = "TorrentDownloader"
    private const val METADATA_TIMEOUT_S = 45
    private const val POLL_MS = 1_000L
    private const val NO_PROGRESS_LIMIT_MS = 10 * 60_000L
    private const val MAX_CONNECTIONS = 80
    private val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val gate = Semaphore(1)
    private val sessionLock = Any()
    private var session: SessionManager? = null
    private var sessionUsers = 0

    private lateinit var app: Context

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        TorrentDownloadStore.init(app)
    }

    /** Where downloads are kept while they run: the phone's app storage (the SD card when there is one). */
    private fun workRoot(): File =
        File(app.getExternalFilesDir(null) ?: app.filesDir, "torrent-downloads").apply { mkdirs() }

    // --- Commands ----------------------------------------------------------------------------

    fun enqueue(record: TorrentDownloadRecord) {
        val dir = File(workRoot(), safe(record.id)).apply { mkdirs() }
        TorrentDownloadStore.put(
            record.copy(state = TorrentDownloadState.QUEUED, workDir = dir.absolutePath, message = null),
        )
        launch(record.id)
    }

    fun pause(id: String) {
        TorrentDownloadStore.update(id) { it.copy(state = TorrentDownloadState.PAUSED, message = null) }
        jobs.remove(id)?.cancel()
        TorrentDownloadService.refresh(app)
    }

    fun resume(id: String) {
        val r = TorrentDownloadStore.get(id) ?: return
        if (r.state == TorrentDownloadState.COMPLETED || jobs.containsKey(id)) return
        TorrentDownloadStore.update(id) { it.copy(state = TorrentDownloadState.QUEUED, message = null) }
        launch(id)
    }

    fun retry(id: String) = resume(id)

    /** Deletes the download, its partial data and the finished file. */
    fun remove(id: String) {
        val r = TorrentDownloadStore.get(id)
        jobs.remove(id)?.cancel()
        TorrentDownloadStore.remove(id)
        scope.launch {
            if (r != null) {
                DownloadPublisher.delete(app, r.finalUri)
                r.workDir?.let { runCatching { File(it).deleteRecursively() } }
            }
            TorrentDownloadService.refresh(app)
        }
    }

    /**
     * At app start: a download that was still active means the app was stopped while it ran (killed,
     * or crashed). It is NOT restarted automatically: if the stop was a crash, restarting would crash
     * the app on every launch. It shows as paused, one tap resumes it.
     */
    fun pauseInterrupted() {
        TorrentDownloadStore.records.value.values
            .filter { it.isActive && !jobs.containsKey(it.id) }
            .forEach { r ->
                TorrentDownloadStore.update(r.id) { it.copy(state = TorrentDownloadState.PAUSED, message = null) }
            }
    }

    private fun launch(id: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                gate.withPermit { run(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable on purpose: an out-of-memory or linkage error here must not take the app down.
                Log.w(TAG, "download $id failed", e)
                TorrentDownloadStore.update(id) {
                    it.copy(state = TorrentDownloadState.FAILED, message = e.message ?: app.getString(R.string.download_torrent_failed))
                }
            } finally {
                jobs.remove(id)
                TorrentDownloadService.refresh(app)
            }
        }
        jobs[id] = job
        job.start()
        TorrentDownloadService.refresh(app)
    }

    // --- The download itself -----------------------------------------------------------------

    private suspend fun run(id: String) {
        val record = TorrentDownloadStore.get(id) ?: return
        val dir = File(record.workDir ?: File(workRoot(), safe(id)).absolutePath).apply { mkdirs() }
        waitForNetwork(id)
        TorrentDownloadStore.update(id) { it.copy(state = TorrentDownloadState.STARTING, message = null) }

        val sm = acquireSession()
        var handle: TorrentHandle? = null
        try {
            val info = withContext(Dispatchers.IO) { loadInfo(sm, record, dir) }
            val fileIdx = chooseFile(info, record.choice.fileIdx)
            val total = info.files().fileSize(fileIdx)
            val priorities = Array(info.numFiles()) { if (it == fileIdx) Priority.DEFAULT else Priority.IGNORE }
            withContext(Dispatchers.IO) { sm.download(info, dir, null, priorities, null, torrent_flags_t()) }
            val h = findHandle(sm, info) ?: error(app.getString(R.string.download_torrent_failed))
            handle = h
            TorrentDownloadStore.update(id) { it.copy(state = TorrentDownloadState.DOWNLOADING, totalBytes = total) }

            var lastDone = -1L
            var lastProgressAt = System.currentTimeMillis()
            var paused = false
            while (true) {
                delay(POLL_MS)
                if (!networkAllowed()) {
                    if (!paused) { h.pause(); paused = true }
                    TorrentDownloadStore.update(id, persist = false) { it.copy(state = TorrentDownloadState.WAITING_WIFI) }
                    lastProgressAt = System.currentTimeMillis()
                    continue
                }
                if (paused) { h.resume(); paused = false }
                val st = h.status()
                val done = st.totalDone().coerceAtMost(total)
                val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
                TorrentDownloadStore.update(id, persist = false) {
                    it.copy(state = TorrentDownloadState.DOWNLOADING, bytesDone = done, percent = percent, message = null)
                }
                if (done != lastDone) { lastDone = done; lastProgressAt = System.currentTimeMillis() }
                if (finished(st, done, total)) break
                if (System.currentTimeMillis() - lastProgressAt > NO_PROGRESS_LIMIT_MS) {
                    throw IllegalStateException(app.getString(R.string.download_torrent_stalled))
                }
            }
            val source = File(dir, info.files().filePath(fileIdx))
            runCatching { sm.remove(h) }
            handle = null
            finish(id, record, dir, source)
        } finally {
            handle?.let { h -> runCatching { sm.remove(h) } }
            releaseSession()
        }
    }

    private fun finished(st: TorrentStatus, done: Long, total: Long): Boolean =
        (total > 0 && done >= total) ||
            st.state() == TorrentStatus.State.FINISHED || st.state() == TorrentStatus.State.SEEDING

    private suspend fun finish(id: String, record: TorrentDownloadRecord, dir: File, source: File) = withContext(Dispatchers.IO) {
        TorrentDownloadStore.update(id) { it.copy(state = TorrentDownloadState.FINISHING, percent = 100) }
        if (!source.isFile) error(app.getString(R.string.download_torrent_failed))
        val ext = source.extension.lowercase().ifBlank { "mkv" }
        val base = DownloadPublisher.baseName(record)
        var video = source
        var bundled = false
        var note: String? = null

        record.subtitleWish?.let { wish ->
            val bytes = DownloadSubtitles.find(record.videoType.toVideoType(), wish, record.choice.release)
            if (bytes == null) {
                note = app.getString(R.string.download_torrent_no_subtitle)
            } else if (ext == "mkv" || ext == "mp4" || ext == "m4v") {
                val out = File(dir, "bundled.$ext")
                try {
                    val events = SrtParser.parse(SrtParser.decode(bytes, legacyCharset(wish.iso1)))
                    if (ext == "mkv") {
                        MkvSubtitleMuxer.mux(source, out, events, language = iso3(wish.iso1), name = wish.label)
                    } else {
                        Mp4SubtitleMuxer.mux(source, out, events, language = iso3(wish.iso1), name = wish.label)
                    }
                    video = out
                    bundled = true
                } catch (e: Exception) {
                    Log.w(TAG, "bundling the subtitle failed", e)
                    out.delete()
                    DownloadPublisher.publishSubtitle(app, bytes, "$base.${wish.iso1}.srt")
                    note = app.getString(R.string.download_torrent_subtitle_beside)
                }
            } else {
                DownloadPublisher.publishSubtitle(app, bytes, "$base.${wish.iso1}.srt")
                note = app.getString(R.string.download_torrent_subtitle_beside)
            }
        }

        val displayName = "$base.${video.extension.ifBlank { ext }}"
        val published = runCatching { DownloadPublisher.publishVideo(app, video, displayName, DownloadPublisher.mimeFor(ext)) }
            .onFailure { Log.w(TAG, "publishing failed", it) }.getOrNull()
        val finalUri = published?.toString() ?: run {
            val folder = File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: app.filesDir, DownloadPublisher.FOLDER).apply { mkdirs() }
            val target = File(folder, displayName)
            if (!video.renameTo(target)) video.copyTo(target, overwrite = true)
            android.net.Uri.fromFile(target).toString()
        }
        runCatching { dir.deleteRecursively() }
        TorrentDownloadStore.update(id) {
            it.copy(
                state = TorrentDownloadState.COMPLETED, percent = 100, bytesDone = it.totalBytes,
                finalUri = finalUri, finalName = displayName, bundledSubtitle = bundled, message = note, workDir = null,
            )
        }
    }

    private fun loadInfo(sm: SessionManager, record: TorrentDownloadRecord, dir: File): TorrentInfo {
        val cached = File(dir, "meta.torrent")
        if (cached.isFile) runCatching { return TorrentInfo(cached) }
        val magnet = buildString {
            append("magnet:?xt=urn:btih:").append(record.choice.infoHash)
            (record.choice.trackers + TorrentEngine.DEFAULT_TRACKERS).distinct().forEach {
                append("&tr=").append(java.net.URLEncoder.encode(it, "UTF-8"))
            }
        }
        repeat(2) {
            val data = runCatching { sm.fetchMagnet(magnet, METADATA_TIMEOUT_S, dir) }.getOrNull()
            if (data != null) {
                runCatching { cached.writeBytes(data) }
                return TorrentInfo.bdecode(data)
            }
        }
        error(app.getString(R.string.download_torrent_no_peers))
    }

    private suspend fun findHandle(sm: SessionManager, info: TorrentInfo): TorrentHandle? {
        repeat(50) {
            sm.find(info.infoHash())?.takeIf { it.isValid }?.let { return it }
            delay(100)
        }
        return null
    }

    private fun chooseFile(info: TorrentInfo, requested: Int?): Int {
        if (requested != null && requested in 0 until info.numFiles()) return requested
        val fs = info.files()
        val videos = (0 until info.numFiles()).filter { fs.filePath(it).substringAfterLast('.', "").lowercase() in VIDEO_EXT }
        return videos.ifEmpty { (0 until info.numFiles()).toList() }.maxByOrNull { fs.fileSize(it) } ?: 0
    }

    // --- Network -----------------------------------------------------------------------------

    private fun networkAllowed(): Boolean {
        if (!DownloadPreferences.wifiOnly(app)) return true
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }

    private suspend fun waitForNetwork(id: String) {
        while (!networkAllowed()) {
            TorrentDownloadStore.update(id, persist = false) { it.copy(state = TorrentDownloadState.WAITING_WIFI) }
            delay(3_000)
        }
    }

    // --- Session -----------------------------------------------------------------------------

    private fun acquireSession(): SessionManager = synchronized(sessionLock) {
        sessionUsers++
        session?.let { return it }
        val pack = SettingsPack().apply {
            connectionsLimit(MAX_CONNECTIONS)
            activeDownloads(1)
            activeSeeds(0)
            alertQueueSize(256)
            setEnableLsd(false)
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
            setInteger(settings_pack.int_types.max_peerlist_size.swigValue(), 600)
            setInteger(settings_pack.int_types.aio_threads.swigValue(), 2)
            setEnableDht(true)
            setDhtBootstrapNodes(
                "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,dht.libtorrent.org:25401",
            )
        }
        val sm = SessionManager(false)
        sm.start(SessionParams(pack))
        session = sm
        sm
    }

    private fun releaseSession() = synchronized(sessionLock) {
        sessionUsers--
        if (sessionUsers <= 0) {
            sessionUsers = 0
            session?.let { runCatching { it.stop() } }
            session = null
        }
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80) + "_" + Integer.toHexString(id.hashCode())

    /** Subtitle sites still serve old code pages; used only when the text is not valid UTF-8. */
    private fun legacyCharset(iso1: String): java.nio.charset.Charset = runCatching {
        java.nio.charset.Charset.forName(
            when (iso1.lowercase()) {
                "hu", "cs", "sk", "pl", "ro" -> "windows-1250"
                "ru" -> "windows-1251"
                "tr" -> "windows-1254"
                else -> "windows-1252"
            },
        )
    }.getOrDefault(Charsets.ISO_8859_1)

    private fun iso3(iso1: String): String = when (iso1.lowercase()) {
        "en" -> "eng"
        "hu" -> "hun"
        "de" -> "ger"
        "fr" -> "fre"
        "es" -> "spa"
        "it" -> "ita"
        "pt" -> "por"
        "ro" -> "rum"
        "ru" -> "rus"
        "pl" -> "pol"
        "cs" -> "cze"
        "sk" -> "slo"
        "nl" -> "dut"
        "tr" -> "tur"
        "sv" -> "swe"
        "ja" -> "jpn"
        "ko" -> "kor"
        "zh" -> "chi"
        else -> "und"
    }
}
