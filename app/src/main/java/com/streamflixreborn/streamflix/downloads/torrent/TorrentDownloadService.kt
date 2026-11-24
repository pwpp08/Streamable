package com.streamflixreborn.streamflix.downloads.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.downloads.DownloadTracker
import com.streamflixreborn.streamflix.downloads.DownloadUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process (and a CPU wake lock) alive while torrent downloads run, and shows their
 * progress. Stops itself when nothing is active any more.
 */
class TorrentDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        show(buildNotification(TorrentDownloadStore.records.value.values.filter { it.isActive }))
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "streamflix:torrent-download")
                .apply { setReferenceCounted(false); runCatching { acquire(6 * 60 * 60 * 1000L) } }
            scope.launch {
                TorrentDownloadStore.records.collect { all ->
                    val active = all.values.filter { it.isActive }
                    if (active.isEmpty()) {
                        stopSelf()
                    } else {
                        show(buildNotification(active))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android's dataSync time limit: keep the files, carry on next time the app starts them.
        Log.w(TAG, "foreground time limit reached")
        stopSelf()
    }

    private fun show(notification: Notification) {
        if (!foreground) {
            foreground = true
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(active: List<TorrentDownloadRecord>): Notification {
        val running = active.firstOrNull { it.state == TorrentDownloadState.DOWNLOADING } ?: active.firstOrNull()
        val title = running?.title ?: getString(R.string.downloads_channel_name)
        val percent = running?.percent ?: -1
        val builder = NotificationCompat.Builder(this, DownloadUtil.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(DownloadTracker.contentIntent(this))
        when (running?.state) {
            TorrentDownloadState.WAITING_WIFI -> builder.setContentText(getString(R.string.download_torrent_waiting_wifi))
            TorrentDownloadState.FINISHING -> builder.setContentText(getString(R.string.download_torrent_finishing))
            TorrentDownloadState.STARTING, TorrentDownloadState.QUEUED, null ->
                builder.setContentText(getString(R.string.download_torrent_starting)).setProgress(0, 0, true)
            else -> if (percent >= 0) builder.setContentText("$percent%").setProgress(100, percent, false)
            else builder.setProgress(0, 0, true)
        }
        if (active.size > 1) builder.setSubText(getString(R.string.download_torrent_more, active.size - 1))
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(DownloadUtil.NOTIFICATION_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    DownloadUtil.NOTIFICATION_CHANNEL_ID,
                    getString(R.string.downloads_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "TorrentDownloadService"
        private const val NOTIFICATION_ID = 7_298

        /** Starts the service while something is active, and lets it go when nothing is. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val anyActive = TorrentDownloadStore.records.value.values.any { it.isActive }
            val intent = Intent(app, TorrentDownloadService::class.java)
            runCatching {
                if (anyActive) {
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
                }
            }.onFailure { Log.w(TAG, "could not start the service (app in background?)", it) }
        }
    }
}
