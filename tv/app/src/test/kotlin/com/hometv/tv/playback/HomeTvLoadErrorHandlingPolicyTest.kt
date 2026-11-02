package com.hometv.tv.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Retry rules for stream/HLS loads. */
@RunWith(AndroidJUnit4::class)
class HomeTvLoadErrorHandlingPolicyTest {
    private val policy = HomeTvLoadErrorHandlingPolicy()
    private val spec = DataSpec(Uri.parse("https://hometv.ts.net/api/hls/S1/seg_3.m4s"))

    private fun info(error: IOException, count: Int) =
        LoadErrorHandlingPolicy.LoadErrorInfo(LoadEventInfo(1, spec, 0), MediaLoadData(C.DATA_TYPE_MEDIA), error, count)

    private fun status(code: Int) = HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), spec, ByteArray(0))

    @Test
    fun `504 segment_timeout retries after 2 s at most 3 times`() {
        assertEquals(2_000L, policy.getRetryDelayMsFor(info(status(504), 1)))
        assertEquals(2_000L, policy.getRetryDelayMsFor(info(status(504), 3)))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(status(504), 4)))
    }

    @Test
    fun `401, 404, 410, 416, 422 and 500 are never retried`() {
        listOf(401, 404, 410, 416, 422, 500).forEach { code ->
            assertEquals("status $code", C.TIME_UNSET, policy.getRetryDelayMsFor(info(status(code), 1)))
        }
    }

    @Test
    fun `a network error without status uses the default backoff at most 3 times`() {
        val network = HttpDataSource.HttpDataSourceException(IOException("reset"), spec, 2001, HttpDataSource.HttpDataSourceException.TYPE_READ)
        assertTrue(policy.getRetryDelayMsFor(info(network, 1)) >= 0)
        assertTrue(policy.getRetryDelayMsFor(info(network, 3)) > 0)
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(network, 4)))
    }

    @Test
    fun `no fallback selection`() {
        val options = LoadErrorHandlingPolicy.FallbackOptions(1, 0, 2, 0)
        assertNull(policy.getFallbackSelectionFor(options, info(status(500), 1)))
    }
}
