package com.hometv.tv.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.playback.PlaybackErrorCodes
import com.hometv.tv.core.playback.PlayerState
import com.hometv.tv.core.playback.RendererType
import com.hometv.tv.core.playback.UrlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** prd3 §10 "Errors during playback": `PlaybackException` → pure `PlayerFailure` (urlKind, status, body code, renderer). */
@RunWith(AndroidJUnit4::class)
class ExoPlayerControllerTest {
    private val stream = "https://hometv.ts.net/api/media-files/91/stream"

    @Test
    fun `urlKind from the most recent load error`() {
        fun kind(uri: String, type: Int = C.TRACK_TYPE_DEFAULT) = ExoPlayerController.urlKind(ExoPlayerController.LoadRecord(uri, type), stream)
        assertEquals(UrlKind.SUBTITLE, kind("file:///data/cache/subtitles/91/emb-3.vtt"))
        assertEquals(UrlKind.SUBTITLE, kind("https://hometv.ts.net/x.vtt", C.TRACK_TYPE_TEXT))
        assertEquals(UrlKind.STREAM, kind(stream))
        assertEquals(UrlKind.HLS, kind("https://hometv.ts.net/api/hls/S1/seg_4.m4s?x=1"))
        assertEquals(UrlKind.OTHER, kind("https://hometv.ts.net/api/artwork/x.jpg"))
        assertEquals(UrlKind.OTHER, ExoPlayerController.urlKind(null, stream))
    }

    @Test
    fun `in HLS mode a failing playlist (the media item URL) is HLS, not STREAM`() {
        val playlist = "https://hometv.ts.net/api/hls/S1/index.m3u8"
        val http = HttpDataSource.InvalidResponseCodeException(404, null, null, emptyMap(), DataSpec(Uri.parse(playlist)), ByteArray(0))
        val error = ExoPlaybackException.createForSource(http, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        val failure = ExoPlayerController.failureOf(error, ExoPlayerController.LoadRecord(playlist, C.TRACK_TYPE_DEFAULT), streamUrl = playlist)
        assertEquals(UrlKind.HLS, failure.urlKind)
        assertEquals(404, failure.httpStatus)
    }

    @Test
    fun `an HTTP status and the envelope code are extracted`() {
        val body = """{"error": {"code": "session_not_found", "message": "x", "details": {}}}""".toByteArray()
        val http = HttpDataSource.InvalidResponseCodeException(404, null, null, emptyMap(), DataSpec(Uri.parse(stream)), body)
        val error = ExoPlaybackException.createForSource(http, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        val failure = ExoPlayerController.failureOf(error, ExoPlayerController.LoadRecord("https://hometv.ts.net/api/hls/S1/index.m3u8", C.TRACK_TYPE_DEFAULT), stream)
        assertEquals(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, failure.errorCode)
        assertEquals(404, failure.httpStatus)
        assertEquals("session_not_found", failure.bodyCode)
        assertEquals(UrlKind.HLS, failure.urlKind)
        assertNull(failure.rendererType)
    }

    @Test
    fun `a renderer failure carries the failing renderer type and MIME`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).build()
        val error = ExoPlaybackException.createForRenderer(
            IllegalStateException("decoder"), "MediaCodecAudioRenderer", 1, format, C.FORMAT_HANDLED, false, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )
        val failure = ExoPlayerController.failureOf(error, null, stream)
        assertEquals(RendererType.AUDIO, failure.rendererType)
        assertEquals(MimeTypes.AUDIO_DTS, failure.rendererMime)
        assertEquals(UrlKind.OTHER, failure.urlKind)
    }

    @Test
    fun `player states map to the core enum`() {
        assertEquals(PlayerState.BUFFERING, ExoPlayerController.stateOf(androidx.media3.common.Player.STATE_BUFFERING))
        assertEquals(PlayerState.READY, ExoPlayerController.stateOf(androidx.media3.common.Player.STATE_READY))
        assertEquals(PlayerState.ENDED, ExoPlayerController.stateOf(androidx.media3.common.Player.STATE_ENDED))
        assertEquals(PlayerState.IDLE, ExoPlayerController.stateOf(androidx.media3.common.Player.STATE_IDLE))
    }
}
