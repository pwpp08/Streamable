package com.streamflixreborn.streamflix.fragments.player.engine

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.streamflixreborn.streamflix.downloads.DownloadUtil
import com.streamflixreborn.streamflix.downloads.OfflineMediaProvider
import com.streamflixreborn.streamflix.extractors.TokenManager
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.torrent.TorrentAwareDataSource
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.OkHttpClient

/**
 * HTTP stack used by the player: the shared app client (DoH, TLS compat, cookies) with the
 * browser User-Agent, the per-video headers and the `maintainToken` query rewriting.
 */
@OptIn(UnstableApi::class)
class StreamDataSourceFactory(
    context: Context,
    private val currentVideo: () -> Video?,
    private val logTag: String,
) {
    @Volatile
    private var tokenLogged = false

    val okHttpClient: OkHttpClient = NetworkClient.default.newBuilder()
        .addInterceptor { chain ->
            var request = chain.request()
            if (currentVideo()?.maintainToken == true) {
                val latestQuery = TokenManager.latestQuery
                if (latestQuery != null) {
                    val updatedUrl = request.url.newBuilder().query(latestQuery).build()
                    request = request.newBuilder().url(updatedUrl).build()
                    if (!tokenLogged) {
                        Log.d("TokenManager", "[$logTag-INTERCEPTOR] Token successfully injected (applied to all segments)")
                        tokenLogged = true
                    }
                } else {
                    Log.w("TokenManager", "[$logTag-INTERCEPTOR] maintainToken=true but latestQuery is null! URL: ${request.url.host}")
                }
            }
            chain.proceed(request)
        }
        .build()

    val httpDataSourceFactory: OkHttpDataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
        .setUserAgent(NetworkClient.USER_AGENT)

    private val networkDataSourceFactory: DataSource.Factory =
        DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory)

    /** Read-only view of the download cache, created only when a download is played. */
    private val offlineDataSourceFactory: DataSource.Factory by lazy {
        DownloadUtil.readOnlyCacheFactory(context.applicationContext, networkDataSourceFactory)
    }

    /** Downloaded titles are read from the download cache; everything else from the network. */
    val dataSourceFactory: DataSource.Factory = DataSource.Factory {
        TorrentAwareDataSource(
            if (OfflineMediaProvider.isOffline(currentVideo())) offlineDataSourceFactory.createDataSource()
            else networkDataSourceFactory.createDataSource()
        )
    }

    /** Must be called before preparing [video]. */
    fun applyHeaders(video: Video) {
        tokenLogged = false
        httpDataSourceFactory.setDefaultRequestProperties(
            mapOf("User-Agent" to NetworkClient.USER_AGENT) + (video.headers ?: emptyMap())
        )
    }
}
