package com.streamflixreborn.streamflix.fragments.player.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.utils.SubtitleOffsetRenderersFactory
import com.streamflixreborn.streamflix.utils.UserPreferences

/** Builds the ExoPlayer instance shared by the mobile and TV player screens. */
@OptIn(UnstableApi::class)
object PlayerFactory {

    private const val TAG = "PlayerFactory"
    private const val MB = 1024 * 1024
    private const val INITIAL_BITRATE_ESTIMATE = 8_000_000L

    enum class Profile(val minBufferMs: Int, val maxBufferMs: Int) {
        LOW_MEMORY(20_000, 50_000),
        NORMAL(30_000, 120_000),
        LARGE(60_000, 240_000),
    }

    class Result(val player: ExoPlayer, val analytics: PlaybackAnalytics?, val profile: Profile)

    /** Kept for the whole process so the bandwidth estimate survives player re-creation. */
    @Volatile
    private var bandwidthMeter: DefaultBandwidthMeter? = null

    fun bandwidthMeter(context: Context): DefaultBandwidthMeter =
        bandwidthMeter ?: synchronized(this) {
            bandwidthMeter ?: DefaultBandwidthMeter.Builder(context.applicationContext)
                .setInitialBitrateEstimate(INITIAL_BITRATE_ESTIMATE)
                .build()
                .also { bandwidthMeter = it }
        }

    fun isLowMemoryDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.isLowRamDevice || am.memoryClass <= 256
    }

    fun resolveProfile(context: Context, extraBuffering: Boolean): Profile = when {
        extraBuffering -> Profile.LARGE
        UserPreferences.bufferProfile == UserPreferences.BufferProfile.LOW_MEMORY -> Profile.LOW_MEMORY
        UserPreferences.bufferProfile == UserPreferences.BufferProfile.LARGE -> Profile.LARGE
        isLowMemoryDevice(context) -> Profile.LOW_MEMORY
        else -> Profile.NORMAL
    }

    /** ExoPlayer's buffer lives on the Java heap, so the byte cap follows the heap size. */
    private fun targetBufferBytes(context: Context, profile: Profile): Int {
        val heapMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass ?: 192
        return when (profile) {
            Profile.LOW_MEMORY -> 48 * MB
            Profile.NORMAL -> (heapMb * 0.3f).toInt().coerceIn(48, 128) * MB
            Profile.LARGE -> (heapMb * 0.4f).toInt().coerceIn(48, 200) * MB
        }
    }

    fun buildLoadControl(context: Context, profile: Profile): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(profile.minBufferMs, profile.maxBufferMs, 1_500, 4_000)
            .setTargetBufferBytes(targetBufferBytes(context, profile))
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(20_000, true)
            .build()

    fun buildTrackSelector(context: Context): DefaultTrackSelector {
        val builder = DefaultTrackSelector.Parameters.Builder(context)
        TrackPreferences.apply(context, builder)
        builder.setTunnelingEnabled(UserPreferences.tunneledPlayback)
        return DefaultTrackSelector(context, builder.build())
    }

    fun buildRenderersFactory(context: Context, softwareDecoder: Boolean): DefaultRenderersFactory =
        SubtitleOffsetRenderersFactory(context).apply {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1 || softwareDecoder) {
                setEnableDecoderFallback(true)
                if (softwareDecoder) {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                }
            }
        }

    fun build(
        context: Context,
        dataSources: StreamDataSourceFactory,
        softwareDecoder: Boolean,
        extraBuffering: Boolean,
        seekIncrementMs: Long? = null,
    ): Result {
        val profile = resolveProfile(context, extraBuffering)
        Log.d(TAG, "buffer profile=$profile, software decoder=$softwareDecoder, tunneling=${UserPreferences.tunneledPlayback}")

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSources.dataSourceFactory)
            .setLoadErrorHandlingPolicy(RetryingLoadErrorPolicy())

        val builder = ExoPlayer.Builder(context, buildRenderersFactory(context, softwareDecoder))
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(buildLoadControl(context, profile))
            .setTrackSelector(buildTrackSelector(context))
            .setBandwidthMeter(bandwidthMeter(context))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
        seekIncrementMs?.let {
            builder.setSeekBackIncrementMs(it)
            builder.setSeekForwardIncrementMs(it)
        }

        val player = builder.build()
        player.addAnalyticsListener(QualityMemory.Listener())
        val analytics = if (BuildConfig.DEBUG) PlaybackAnalytics().also(player::addAnalyticsListener) else null
        return Result(player, analytics, profile)
    }
}
