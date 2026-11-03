package com.streamflixreborn.streamflix.fragments.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Free streaming hosts drop segment requests a lot; retrying a few times with backoff
 * avoids a visible stall or a server switch. Manifests that are gone (403/404/410) are
 * not retried, so failover to the next server starts immediately.
 */
@OptIn(UnstableApi::class)
class RetryingLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        if (loadErrorInfo.mediaLoadData.dataType == C.DATA_TYPE_MANIFEST &&
            error is HttpDataSource.InvalidResponseCodeException &&
            error.responseCode in FATAL_MANIFEST_CODES
        ) {
            return C.TIME_UNSET
        }
        if (super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET) return C.TIME_UNSET
        return backoffMs(loadErrorInfo.errorCount)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = when (dataType) {
        C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_INITIALIZATION -> MEDIA_RETRY_COUNT
        else -> super.getMinimumLoadableRetryCount(dataType)
    }

    companion object {
        const val MEDIA_RETRY_COUNT = 6
        private const val BASE_DELAY_MS = 500L
        private const val MAX_DELAY_MS = 4_000L
        private val FATAL_MANIFEST_CODES = setOf(403, 404, 410)

        /** 500, 1000, 2000, 4000, 4000 ... for errorCount 1, 2, 3 ... */
        fun backoffMs(errorCount: Int): Long {
            val shift = (errorCount - 1).coerceIn(0, 10)
            return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
        }
    }
}
