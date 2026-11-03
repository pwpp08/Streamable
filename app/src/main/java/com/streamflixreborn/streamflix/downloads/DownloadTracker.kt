package com.streamflixreborn.streamflix.downloads

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import com.streamflixreborn.streamflix.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Observable state of every download for the UI: Media3's downloads (with live progress while
 * something is downloading) plus the "finding a server" / "no server worked" phases that happen
 * before a [androidx.media3.exoplayer.offline.DownloadRequest] exists.
 */
@OptIn(UnstableApi::class)
object DownloadTracker {

    private const val TAG = "DownloadTracker"
    private const val PROGRESS_INTERVAL_MS = 1_000L
    private const val NOTIFICATION_ID_BASE = 7_300

    enum class Status { NONE, RESOLVING, QUEUED, WAITING, DOWNLOADING, PAUSED, COMPLETED, FAILED, REMOVING }

    data class Item(
        val id: String,
        val status: Status,
        val metadata: DownloadMetadata?,
        /** 0..100, or -1 when unknown. */
        val percent: Int = -1,
        val bytesDownloaded: Long = 0L,
        val contentLength: Long = C.LENGTH_UNSET.toLong(),
        val startTimeMs: Long = 0L,
        val waitingForWifi: Boolean = false,
        val message: String? = null,
    ) {
        /** True when no Media3 download exists yet (resolution running or failed). */
        val isPendingOnly: Boolean get() = status == Status.RESOLVING || (status == Status.FAILED && contentLength == PENDING_MARKER)
    }

    private const val PENDING_MARKER = -2L

    private val _items = MutableStateFlow<Map<String, Item>>(emptyMap())
    val items: StateFlow<Map<String, Item>> = _items

    private val lock = Any()
    private val downloads = LinkedHashMap<String, Download>()
    private val failures = HashMap<String, String?>()
    private val pending = LinkedHashMap<String, Item>()
    private val removalWaiters = ConcurrentHashMap<String, MutableList<CompletableDeferred<Unit>>>()

    @Volatile private var manager: DownloadManager? = null
    @Volatile private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickerRunning = false

    /** Creates the download manager (if needed) so [items] gets filled. */
    fun ensureStarted(context: Context) {
        if (!DownloadUtil.isSupported(context)) return
        DownloadUtil.downloadManager(context)
    }

    fun item(id: String): Item? = _items.value[id]

    internal fun attach(context: Context, manager: DownloadManager) {
        this.manager = manager
        this.appContext = context.applicationContext
        manager.addListener(listener)
        Thread({
            try {
                DownloadUtil.downloadIndex(context).getDownloads().use { cursor ->
                    val loaded = mutableListOf<Download>()
                    while (cursor.moveToNext()) loaded += cursor.download
                    synchronized(lock) { loaded.forEach { downloads.putIfAbsent(it.request.id, it) } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "reading the download index failed", e)
            }
            publish()
        }, "download-index-load").start()
    }

    // --- Pre-request phases -------------------------------------------------------------------

    fun setResolving(id: String, metadata: DownloadMetadata) {
        synchronized(lock) { pending[id] = Item(id, Status.RESOLVING, metadata) }
        publish()
    }

    fun setResolveFailed(id: String, metadata: DownloadMetadata, message: String?) {
        synchronized(lock) {
            pending[id] = Item(id, Status.FAILED, metadata, contentLength = PENDING_MARKER, message = message)
        }
        publish()
    }

    fun clearPending(id: String) {
        synchronized(lock) { pending.remove(id) }
        publish()
    }

    private val adding: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The request for [id] was handed to the service: keep showing "finding a server" until the
     * manager reports the new download, so the button never flickers back to "download".
     */
    fun markAdding(id: String) {
        adding += id
        mainHandler.postDelayed({ if (adding.remove(id)) clearPending(id) }, 10_000L)
    }

    fun isResolving(id: String): Boolean = synchronized(lock) { pending[id]?.status == Status.RESOLVING }

    /** Registers interest in [id]'s removal; call before asking the service to remove it. */
    fun removalWaiter(id: String): CompletableDeferred<Unit> {
        val waiter = CompletableDeferred<Unit>()
        removalWaiters.getOrPut(id) { mutableListOf() }.add(waiter)
        return waiter
    }

    suspend fun awaitRemoval(waiter: CompletableDeferred<Unit>, timeoutMs: Long = 15_000L) {
        withTimeoutOrNull(timeoutMs) { waiter.await() }
    }

    fun hasDownload(id: String): Boolean = synchronized(lock) { downloads.containsKey(id) }

    // --- Media3 listener ---------------------------------------------------------------------

    private val listener = object : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) {
            synchronized(lock) { downloadManager.currentDownloads.forEach { downloads[it.request.id] = it } }
            publish()
            scheduleTicker()
        }

        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            val previous = synchronized(lock) {
                if (adding.remove(download.request.id)) pending.remove(download.request.id)
                val old = downloads.put(download.request.id, download)
                if (download.state == Download.STATE_FAILED) failures[download.request.id] = finalException?.message
                else failures.remove(download.request.id)
                old
            }
            if (previous == null || !previous.isTerminalState) notifyTerminal(download)
            publish()
            scheduleTicker()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            val id = download.request.id
            OfflineMediaProvider.forget(download.request.uri.toString())
            synchronized(lock) {
                downloads.remove(id)
                failures.remove(id)
            }
            appContext?.let { context ->
                Thread({
                    DownloadUtil.deleteSubtitles(context, id)
                    DownloadHeaderStore.remove(context, id)
                    removalWaiters.remove(id)?.forEach { it.complete(Unit) }
                }, "download-cleanup").start()
            } ?: removalWaiters.remove(id)?.forEach { it.complete(Unit) }
            publish()
        }

        override fun onRequirementsStateChanged(downloadManager: DownloadManager, requirements: Requirements, notMetRequirements: Int) {
            publish()
        }

        override fun onWaitingForRequirementsChanged(downloadManager: DownloadManager, waitingForRequirements: Boolean) {
            publish()
        }
    }

    /** Progress is not pushed by Media3; poll it while something downloads. */
    private fun scheduleTicker() {
        mainHandler.post {
            if (tickerRunning) return@post
            tickerRunning = true
            mainHandler.postDelayed(ticker, PROGRESS_INTERVAL_MS)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val manager = manager
            if (manager == null) {
                tickerRunning = false
                return
            }
            val current = manager.currentDownloads
            synchronized(lock) { current.forEach { downloads[it.request.id] = it } }
            publish()
            if (current.any { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }) {
                mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
            } else {
                tickerRunning = false
            }
        }
    }

    private fun publish() {
        val notMet = manager?.notMetRequirements ?: 0
        val map = synchronized(lock) {
            val result = LinkedHashMap<String, Item>()
            pending.values.forEach { result[it.id] = it }
            downloads.values.sortedByDescending { it.startTimeMs }.forEach { download ->
                val id = download.request.id
                val pendingItem = pending[id]
                // A running resolution (retry) hides the old download's state.
                if (pendingItem?.status == Status.RESOLVING) return@forEach
                result[id] = toItem(download, notMet, failures[id])
            }
            result
        }
        _items.value = map
    }

    private fun toItem(download: Download, notMetRequirements: Int, failure: String?): Item {
        val waiting = notMetRequirements != 0
        val status = statusOf(download.state, waiting)
        val percent = download.percentDownloaded.takeIf { it >= 0f }?.toInt()?.coerceIn(0, 100)
            ?: if (download.state == Download.STATE_COMPLETED) 100 else -1
        return Item(
            id = download.request.id,
            status = status,
            metadata = DownloadMetadata.decode(download.request.data),
            percent = percent,
            bytesDownloaded = download.bytesDownloaded,
            contentLength = download.contentLength,
            startTimeMs = download.startTimeMs,
            waitingForWifi = waiting && (notMetRequirements and Requirements.NETWORK_UNMETERED) != 0,
            message = failure,
        )
    }

    fun statusOf(state: Int, waitingForRequirements: Boolean): Status = when (state) {
        Download.STATE_QUEUED, Download.STATE_RESTARTING -> if (waitingForRequirements) Status.WAITING else Status.QUEUED
        Download.STATE_DOWNLOADING -> if (waitingForRequirements) Status.WAITING else Status.DOWNLOADING
        Download.STATE_STOPPED -> Status.PAUSED
        Download.STATE_COMPLETED -> Status.COMPLETED
        Download.STATE_FAILED -> Status.FAILED
        Download.STATE_REMOVING -> Status.REMOVING
        else -> Status.QUEUED
    }

    private fun notifyTerminal(download: Download) {
        val context = appContext ?: return
        val title = DownloadMetadata.decode(download.request.data)?.title ?: return
        val helper = DownloadUtil.notificationHelper(context)
        val notification = when (download.state) {
            Download.STATE_COMPLETED -> helper.buildDownloadCompletedNotification(
                context, R.drawable.ic_download_done, contentIntent(context),
                context.getString(R.string.download_completed_notification, title),
            )
            Download.STATE_FAILED -> helper.buildDownloadFailedNotification(
                context, R.drawable.ic_download_error, contentIntent(context),
                context.getString(R.string.download_failed_notification, title),
            )
            else -> return
        }
        runCatching {
            // The service creates the channel too, but a download added without it must still notify.
            NotificationUtil.createNotificationChannel(
                context, DownloadUtil.NOTIFICATION_CHANNEL_ID, R.string.downloads_channel_name, 0,
                NotificationUtil.IMPORTANCE_LOW,
            )
            NotificationUtil.setNotification(context, NOTIFICATION_ID_BASE + (download.request.id.hashCode() and 0xFFFF), notification)
        }.onFailure { Log.w(TAG, "posting the download notification failed", it) }
    }

    fun contentIntent(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: return null
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context, 0, intent, flags)
    }
}
