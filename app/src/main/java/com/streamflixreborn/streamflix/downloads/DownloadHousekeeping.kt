package com.streamflixreborn.streamflix.downloads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.streamflixreborn.streamflix.downloads.torrent.TorrentDownloadState
import com.streamflixreborn.streamflix.downloads.torrent.TorrentDownloadStore
import com.streamflixreborn.streamflix.downloads.torrent.TorrentDownloader
import com.streamflixreborn.streamflix.models.Video

/** Tidying that follows watching: the "delete a download once I have finished it" option. */
@OptIn(UnstableApi::class)
object DownloadHousekeeping {
    private const val TAG = "DownloadHousekeeping"
    private val main = Handler(Looper.getMainLooper())

    /** Called when playback of [videoType] reached the end. Does nothing unless the option is on. */
    fun afterFinished(context: Context, providerName: String, videoType: Video.Type) {
        val app = context.applicationContext
        if (!DownloadPreferences.deleteWhenWatched(app)) return
        val id = DownloadIds.of(providerName, videoType)

        val torrent = TorrentDownloadStore.get(id)
        if (torrent != null) {
            if (torrent.state == TorrentDownloadState.COMPLETED) {
                Log.i(TAG, "deleting the finished download $id")
                main.post { TorrentDownloader.remove(id) }
            }
            return
        }
        if (!DownloadUtil.isSupported(app) || !DownloadUtil.hasDownloadStore(app)) return
        val completed = runCatching { DownloadUtil.downloadIndex(app).getDownload(id) }.getOrNull()
            ?.takeIf { it.state == Download.STATE_COMPLETED } ?: return
        Log.i(TAG, "deleting the finished download ${completed.request.id}")
        main.post { runCatching { DownloadUtil.downloadManager(app).removeDownload(id) } }
    }
}
