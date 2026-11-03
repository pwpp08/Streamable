package com.streamflixreborn.streamflix.downloads

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.streamflixreborn.streamflix.R

/**
 * Foreground service (type dataSync) running the downloads, with a progress notification in the
 * "Downloads" channel. Media3 stops it when the system's dataSync time limit is hit
 * ([DownloadService.onTimeout]); downloads then continue the next time the app starts them.
 */
@OptIn(UnstableApi::class)
class StreamflixDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DownloadUtil.NOTIFICATION_CHANNEL_ID,
    R.string.downloads_channel_name,
    0,
) {

    override fun getDownloadManager(): DownloadManager = DownloadUtil.downloadManager(this)

    /**
     * No scheduler: a [androidx.media3.exoplayer.scheduler.PlatformScheduler] would restart the
     * service from the background when Wi-Fi comes back, which Android 12+ forbids for
     * foreground services. Waiting downloads resume when the app is opened again.
     */
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        val titles = downloads
            .filter { it.state == Download.STATE_DOWNLOADING }
            .mapNotNull { DownloadMetadata.decode(it.request.data)?.title }
        return DownloadUtil.notificationHelper(this).buildProgressNotification(
            this,
            R.drawable.ic_download,
            DownloadTracker.contentIntent(this),
            titles.joinToString(", ").ifEmpty { null },
            downloads,
            notMetRequirements,
        )
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 7_299
    }
}
