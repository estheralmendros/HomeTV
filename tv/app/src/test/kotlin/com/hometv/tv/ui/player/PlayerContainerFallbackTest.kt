package com.hometv.tv.ui.player

import com.hometv.tv.R
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.PlaybackErrorCodes
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.UrlKind
import com.hometv.tv.testing.TEST_SERVER
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Container/parse failure in direct mode (user decision: automatic HLS fallback) and M2TS. */
class PlayerContainerFallbackTest : PlayerTestBase() {
    @Test
    fun `a parse failure in direct mode sends one request without the container and continues in HLS`() = playerTest {
        val vm = startFilm(direct(audio = 1), F.file(1))
        controller.positionMs = 42_000
        answerWith(hls("S1"))
        controller.fail(PlayerFailure(PlaybackErrorCodes.PARSING_CONTAINER_UNSUPPORTED, urlKind = UrlKind.STREAM))
        runCurrent()
        val body = bodies().last()
        assertFalse("mkv" in body.client_caps.containers)
        assertEquals(42.0, body.start_position_seconds)
        assertEquals(1, body.audio_stream_index)
        assertEquals("application/x-mpegURL", controller.item?.mimeType)
        assertEquals("setMediaItem $TEST_SERVER/api/hls/S1/index.m3u8 @42000", controller.log.last { it.startsWith("setMediaItem") })
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `a parse failure before READY retries at the requested start position`() = playerTest {
        film(F.file(1), progress = F.progress(1, position = 1000.0))
        answerWith(direct(), hls("S1"))
        val vm = vm()
        runCurrent()
        controller.positionMs = 0
        controller.fail(PlayerFailure(PlaybackErrorCodes.PARSING_CONTAINER_MALFORMED, urlKind = UrlKind.STREAM))
        runCurrent()
        assertEquals(1000.0, bodies().last().start_position_seconds)
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `an unsupported answer to the fallback shows the unsupported screen with its reason`() = playerTest {
        val vm = startDirect()
        answerWith(unsupported("hls_not_supported"))
        controller.fail(PlayerFailure(PlaybackErrorCodes.PARSING_CONTAINER_UNSUPPORTED, urlKind = UrlKind.STREAM))
        runCurrent()
        val error = vm.state.value.screen as PlayerScreenUi.Error
        assertEquals(UiText.Res(R.string.reason_hls_not_supported), error.reason)
    }

    @Test
    fun `a second parse failure for the same file is the generic error with no further request`() = playerTest {
        val vm = startDirect()
        answerWith(direct())
        controller.fail(PlayerFailure(PlaybackErrorCodes.PARSING_CONTAINER_UNSUPPORTED, urlKind = UrlKind.STREAM))
        runCurrent()
        controller.fail(PlayerFailure(PlaybackErrorCodes.PARSING_CONTAINER_UNSUPPORTED, urlKind = UrlKind.STREAM))
        runCurrent()
        assertEquals(2, posts().size)
        assertEquals(UiText.Res(R.string.player_playback_failed), (vm.state.value.screen as PlayerScreenUi.Error).message)
    }

    @Test
    fun `a parse error with urlKind OTHER is generic, never the fallback`() = playerTest {
        val vm = startDirect()
        controller.fail(PlayerFailure(PlaybackErrorCodes.PARSING_CONTAINER_UNSUPPORTED, urlKind = UrlKind.OTHER))
        runCurrent()
        assertEquals(1, posts().size)
        assertEquals(SessionState.Error, vm.session)
    }

    @Test
    fun `an m2ts file is requested without ts in the caps, a ts file keeps it`() = playerTest {
        startFilm(hls("S1"), F.file(1, fileName = "00001.m2ts", container = "ts"))
        assertFalse("ts" in bodies().single().client_caps.containers)
        startFilm(direct(), F.file(2, fileName = "show.ts", container = "ts"))
        assertTrue("ts" in bodies().last().client_caps.containers)
    }
}
