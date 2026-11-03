package com.streamflixreborn.streamflix.downloads

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloaderFactory
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.extractors.TokenManager
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executors

/**
 * Process-wide download objects: one [DownloadManager], its database and the [SimpleCache]
 * holding the downloaded media.
 *
 * Files live in `noBackupFilesDir/downloads` (media in `media/`, sideloaded subtitles in
 * `subs/<id>/`): the app has `allowBackup` on, and gigabytes of video in `filesDir` would push
 * Auto Backup over its quota and stop the app's normal backups.
 */
@OptIn(UnstableApi::class)
object DownloadUtil {

    private const val TAG = "DownloadUtil"
    const val NOTIFICATION_CHANNEL_ID = "downloads"
    private const val MAX_PARALLEL_DOWNLOADS = 2

    @Volatile private var databaseProvider: StandaloneDatabaseProvider? = null
    @Volatile private var downloadIndex: DefaultDownloadIndex? = null
    @Volatile private var cache: SimpleCache? = null
    @Volatile private var downloadManager: DownloadManager? = null
    @Volatile private var notificationHelper: DownloadNotificationHelper? = null

    private val downloadExecutor by lazy { Executors.newFixedThreadPool(3) }

    /** Downloads are a phone feature; every entry point checks this. */
    fun isSupported(context: Context): Boolean {
        val isTv = BuildConfig.APP_LAYOUT == "tv" ||
            (BuildConfig.APP_LAYOUT != "mobile" &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        return !isTv
    }

    fun rootDir(context: Context): File = File(context.applicationContext.noBackupFilesDir, "downloads")

    private fun mediaDir(context: Context): File = File(rootDir(context), "media")

    fun subtitlesDir(context: Context, id: String): File =
        File(File(rootDir(context), "subs"), DownloadIds.folderName(id))

    /** False until the first download was ever started (avoids opening databases on TV). */
    fun hasDownloadStore(context: Context): Boolean = rootDir(context).exists()

    fun databaseProvider(context: Context): StandaloneDatabaseProvider =
        databaseProvider ?: synchronized(this) {
            databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext).also { databaseProvider = it }
        }

    fun downloadIndex(context: Context): DefaultDownloadIndex =
        downloadIndex ?: synchronized(this) {
            downloadIndex ?: DefaultDownloadIndex(databaseProvider(context)).also { downloadIndex = it }
        }

    fun cache(context: Context): Cache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(mediaDir(context).apply { mkdirs() }, NoOpCacheEvictor(), databaseProvider(context))
                .also { cache = it }
        }

    fun downloadManager(context: Context): DownloadManager =
        downloadManager ?: synchronized(this) {
            downloadManager ?: run {
                val app = context.applicationContext
                rootDir(app).mkdirs()
                DownloadManager(app, downloadIndex(app), HeaderAwareDownloaderFactory(app)).apply {
                    maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
                    requirements = DownloadPreferences.requirements(app)
                }.also {
                    downloadManager = it
                    DownloadTracker.attach(app, it)
                }
            }
        }

    fun notificationHelper(context: Context): DownloadNotificationHelper =
        notificationHelper ?: synchronized(this) {
            notificationHelper ?: DownloadNotificationHelper(context.applicationContext, NOTIFICATION_CHANNEL_ID)
                .also { notificationHelper = it }
        }

    /** OkHttp client of the player stack plus the `maintainToken` query rewriting. */
    fun httpClient(maintainToken: Boolean): OkHttpClient {
        if (!maintainToken) return NetworkClient.default
        return NetworkClient.default.newBuilder()
            .addInterceptor { chain ->
                var request = chain.request()
                val latestQuery = TokenManager.latestQuery
                if (latestQuery != null) {
                    request = request.newBuilder()
                        .url(request.url.newBuilder().query(latestQuery).build())
                        .build()
                }
                chain.proceed(request)
            }
            .build()
    }

    /** Network data source with the browser User-Agent and a stream's own headers. */
    fun upstreamFactory(context: Context, entry: DownloadHeaderStore.Entry?): DataSource.Factory {
        val http = OkHttpDataSource.Factory(httpClient(entry?.maintainToken == true))
            .setUserAgent(NetworkClient.USER_AGENT)
            .setDefaultRequestProperties(
                mapOf("User-Agent" to NetworkClient.USER_AGENT) + (entry?.headers ?: emptyMap())
            )
        return DefaultDataSource.Factory(context.applicationContext, http)
    }

    /**
     * Player data source for downloaded titles: reads the download cache and never writes to it;
     * anything not downloaded (or a local subtitle file) falls through to [upstream].
     */
    fun readOnlyCacheFactory(context: Context, upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Bytes used by downloads (media + subtitles). Call off the main thread. */
    fun usedBytes(context: Context): Long {
        val media = runCatching { cache(context).cacheSpace }.getOrDefault(0L)
        val subs = File(rootDir(context), "subs").walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        return media + subs
    }

    fun freeBytes(context: Context): Long =
        rootDir(context).let { if (it.exists()) it.usableSpace else context.noBackupFilesDir.usableSpace }

    fun deleteSubtitles(context: Context, id: String) {
        runCatching { subtitlesDir(context, id).deleteRecursively() }
            .onFailure { Log.w(TAG, "deleting subtitles of $id failed", it) }
    }

    /**
     * Each download gets a data source with its own stored headers. The headers are looked up
     * when the downloader is created, so this also works for downloads resumed after a restart.
     */
    private class HeaderAwareDownloaderFactory(private val context: Context) : DownloaderFactory {
        override fun createDownloader(request: androidx.media3.exoplayer.offline.DownloadRequest) =
            DefaultDownloaderFactory(
                CacheDataSource.Factory()
                    .setCache(cache(context))
                    .setUpstreamDataSourceFactory(upstreamFactory(context, DownloadHeaderStore.get(context, request.id))),
                downloadExecutor,
            ).createDownloader(request)
    }
}
