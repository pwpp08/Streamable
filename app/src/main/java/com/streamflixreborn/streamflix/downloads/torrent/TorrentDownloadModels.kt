package com.streamflixreborn.streamflix.downloads.torrent

import com.streamflixreborn.streamflix.downloads.DownloadMetadata
import com.streamflixreborn.streamflix.downloads.DownloadTracker
import com.streamflixreborn.streamflix.downloads.StoredVideoType
import kotlinx.serialization.Serializable

/** What the viewer picked in the quality popup. */
@Serializable
data class TorrentChoice(
    val infoHash: String,
    val fileIdx: Int?,
    val release: String,
    val sizeBytes: Long,
    val seeders: Int,
    val height: Int,
    val site: String,
    val trackers: List<String> = emptyList(),
)

/** A subtitle the viewer wants inside the file: the language's ISO-639-1 code and a name to show. */
@Serializable
data class SubtitleWish(val iso1: String, val label: String)

enum class TorrentDownloadState { QUEUED, STARTING, DOWNLOADING, PAUSED, WAITING_WIFI, FINISHING, COMPLETED, FAILED }

/** One torrent download, from the popup to the finished file. Kept on disk so it survives the app closing. */
@Serializable
data class TorrentDownloadRecord(
    val id: String,
    val provider: String,
    val videoType: StoredVideoType,
    val title: String,
    val subtitle: String = "",
    val poster: String? = null,
    val choice: TorrentChoice,
    val subtitleWish: SubtitleWish? = null,
    val state: TorrentDownloadState = TorrentDownloadState.QUEUED,
    val bytesDone: Long = 0L,
    val totalBytes: Long = 0L,
    val percent: Int = -1,
    val message: String? = null,
    /** Folder the torrent is downloaded into (inside the app's own storage). */
    val workDir: String? = null,
    /** Where the finished file is: a content:// URI in the Movies folder, or a file:// URI inside the app. */
    val finalUri: String? = null,
    val finalName: String? = null,
    val bundledSubtitle: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isActive: Boolean
        get() = state == TorrentDownloadState.QUEUED || state == TorrentDownloadState.STARTING ||
            state == TorrentDownloadState.DOWNLOADING || state == TorrentDownloadState.WAITING_WIFI ||
            state == TorrentDownloadState.FINISHING

    fun toTrackerItem(): DownloadTracker.Item {
        val status = when (state) {
            TorrentDownloadState.QUEUED -> DownloadTracker.Status.QUEUED
            TorrentDownloadState.STARTING -> DownloadTracker.Status.RESOLVING
            TorrentDownloadState.DOWNLOADING -> DownloadTracker.Status.DOWNLOADING
            TorrentDownloadState.WAITING_WIFI -> DownloadTracker.Status.WAITING
            TorrentDownloadState.FINISHING -> DownloadTracker.Status.DOWNLOADING
            TorrentDownloadState.PAUSED -> DownloadTracker.Status.PAUSED
            TorrentDownloadState.COMPLETED -> DownloadTracker.Status.COMPLETED
            TorrentDownloadState.FAILED -> DownloadTracker.Status.FAILED
        }
        return DownloadTracker.Item(
            id = id,
            status = status,
            metadata = DownloadMetadata(
                title = title,
                subtitle = subtitle,
                poster = poster,
                provider = provider,
                server = "Torrent ${choice.height}p",
                videoType = videoType,
            ),
            percent = if (state == TorrentDownloadState.COMPLETED) 100 else percent,
            bytesDownloaded = bytesDone,
            contentLength = totalBytes.takeIf { it > 0 } ?: choice.sizeBytes,
            startTimeMs = createdAt,
            waitingForWifi = state == TorrentDownloadState.WAITING_WIFI,
            message = message,
        )
    }
}
