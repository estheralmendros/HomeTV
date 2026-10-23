package com.hometv.tv.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Retry rules of prd3 Playback Flow rule 9 for stream/HLS loads (subtitles are local files and never
 * reach it): 504 `segment_timeout` → retry after 2 s, at most 3 times; an `IOException` without a
 * status (network, read timeout) → default backoff, at most 3 times; 401, 404, 410, 416, 422, 500 →
 * no retry (the error reaches §10 at once: renewal, recovery, `file_missing`…). No fallback selection.
 */
@OptIn(UnstableApi::class)
class HomeTvLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        val count = loadErrorInfo.errorCount
        if (error is HttpDataSource.InvalidResponseCodeException) {
            return when (error.responseCode) {
                SEGMENT_TIMEOUT -> if (count <= MAX_RETRIES) SEGMENT_TIMEOUT_RETRY_MS else C.TIME_UNSET
                in NO_RETRY -> C.TIME_UNSET
                else -> if (count <= MAX_RETRIES) super.getRetryDelayMsFor(loadErrorInfo) else C.TIME_UNSET
            }
        }
        if (count > MAX_RETRIES) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = null

    companion object {
        const val MAX_RETRIES = 3
        const val SEGMENT_TIMEOUT = 504
        const val SEGMENT_TIMEOUT_RETRY_MS = 2_000L
        val NO_RETRY = setOf(401, 404, 410, 416, 422, 500)
    }
}
