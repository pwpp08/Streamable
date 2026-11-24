package com.streamflixreborn.streamflix.downloads

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.downloads.torrent.DownloadPublisher
import com.streamflixreborn.streamflix.downloads.torrent.TorrentDownloadState
import com.streamflixreborn.streamflix.downloads.torrent.TorrentDownloadStore
import com.streamflixreborn.streamflix.models.Video
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Player hook: a title with a completed download plays from the download cache, online or
 * offline, without resolving any server.
 */
@OptIn(UnstableApi::class)
object OfflineMediaProvider {

    private const val TAG = "OfflineMediaProvider"

    class OfflineMedia(val video: Video, val server: Video.Server, val request: DownloadRequest?)

    /** Sources that are plain files on the phone (torrent downloads), not entries of the download cache. */
    private val localFiles = ConcurrentHashMap.newKeySet<String>()

    /** Sources handed to the player, by [Video.source], with their download request. */
    private val active = ConcurrentHashMap<String, DownloadRequest>()

    /**
     * The completed download of [videoType] from [providerName], as a playable [Video] whose
     * subtitles are the saved local files. Null when there is none. Blocking (database read).
     */
    fun lookup(context: Context, videoType: Video.Type, providerName: String?): OfflineMedia? {
        providerName ?: return null
        torrentFile(context, videoType, providerName)?.let { return it }
        if (!DownloadUtil.isSupported(context) || !DownloadUtil.hasDownloadStore(context)) return null
        val id = DownloadIds.of(providerName, videoType)
        val download = try {
            DownloadUtil.downloadIndex(context).getDownload(id)
        } catch (e: Exception) {
            Log.w(TAG, "download index lookup failed", e)
            null
        } ?: return null
        if (download.state != Download.STATE_COMPLETED) return null

        val metadata = DownloadMetadata.decode(download.request.data)
        val subtitles = metadata?.subtitles.orEmpty()
            .filter { File(it.path).isFile }
            .map { Video.Subtitle(label = it.label, file = Uri.fromFile(File(it.path)).toString(), default = it.default) }
        val video = Video(
            source = download.request.uri.toString(),
            subtitles = subtitles,
            type = download.request.mimeType,
        )
        active[video.source] = download.request
        Log.d(TAG, "playing $id from the download cache")
        return OfflineMedia(
            video = video,
            server = Video.Server(id = "offline:$id", name = context.getString(R.string.downloads_offline_server)),
            request = download.request,
        )
    }

    private fun torrentFile(context: Context, videoType: Video.Type, providerName: String): OfflineMedia? {
        val id = DownloadIds.of(providerName, videoType)
        val record = TorrentDownloadStore.get(id)
            ?.takeIf { it.state == TorrentDownloadState.COMPLETED && DownloadPublisher.exists(context, it.finalUri) }
            ?: return null
        val uri = record.finalUri ?: return null
        localFiles += uri
        Log.d(TAG, "playing $id from its downloaded file")
        return OfflineMedia(
            video = Video(source = uri, subtitles = emptyList(), type = DownloadPublisher.mimeFor(record.finalName.orEmpty().substringAfterLast('.', "mkv"))),
            server = Video.Server(id = "offline:$id", name = context.getString(R.string.downloads_offline_server)),
            request = null,
        )
    }

    /** True when [video] must be read through the download cache (not a plain local file). */
    fun isCacheBacked(video: Video?): Boolean = video != null && active.containsKey(video.source)

    /** Called when a download is deleted. */
    fun forget(source: String) {
        active.remove(source)
    }

    fun isOffline(video: Video?): Boolean = video != null && (active.containsKey(video.source) || localFiles.contains(video.source))

    fun isOfflineServer(server: Video.Server?): Boolean = server?.id?.startsWith("offline:") == true

    /**
     * Media item for an offline [video]: carries the download's stream keys (only the
     * downloaded variants are played) and cache key. Null for normal streams.
     */
    fun mediaItemBuilder(video: Video): MediaItem.Builder? =
        active[video.source]?.toMediaItem()?.buildUpon()
}
