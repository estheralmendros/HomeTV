package com.hometv.tv.ui.player

import com.hometv.tv.R
import com.hometv.tv.core.api.PlaybackRequest
import com.hometv.tv.core.api.VideoInfo
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.Mimes
import com.hometv.tv.core.playback.PlaybackEngine.EXO
import com.hometv.tv.core.playback.PlaybackEngine.VLC
import com.hometv.tv.core.playback.PlayerAction
import com.hometv.tv.core.playback.PlayerState
import com.hometv.tv.core.playback.VideoDecoder
import com.hometv.tv.core.playback.VlcCapsBuilder
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The first-frame watchdog in the Player (specs/video-transcoding/prd2.md FR-T5). */
class PlayerFirstFrameTest : PlayerTestBase() {
    private val av1 = F.file(1, video = VideoInfo("av1", 1920, 1080))

    /** Fires within 5 s of counted time plus one tick: every "no frame" wait advances this much. */
    private val past = 7_000L

    private fun claimAv1() {
        codecs.inventory = codecs.inventory.copy(video = codecs.inventory.video + VideoDecoder(Mimes.VIDEO_AV1, true, 3840, 2160))
    }

    private fun PlaybackRequest.isVlc() = client_caps == VlcCapsBuilder.build()

    private fun TestScope.playingAv1(): PlayerViewModel {
        claimAv1()
        val vm = startFilm(direct(), av1)
        vm.onVideoSurface(true)
        return vm
    }

    private fun converted() = hls("S2").copy(video_action = "h264")

    private fun error(vm: PlayerViewModel) = vm.state.value.screen as PlayerScreenUi.Error

    @Test
    fun `AV1 playing 5 s without a frame re-requests without av1 at the current position, then a second silence is generic`() = playerTest {
        val vm = playingAv1()
        controller.positionMs = 123_000
        answerWith(converted())
        advance(past)
        assertEquals(2, bodies().size)
        assertFalse("av1" in bodies()[1].client_caps.video_codecs)
        assertTrue("av1" in bodies()[0].client_caps.video_codecs)
        assertEquals(123.0, bodies()[1].start_position_seconds)
        assertFalse(bodies()[1].isVlc())
        assertEquals(EXO, vm.state.value.engine)
        controller.ready()
        advance(past)
        assertEquals(2, bodies().size)
        assertEquals(UiText.Res(R.string.player_playback_failed), error(vm).message)
        assertEquals(PlayerAction.RETRY_FROM_HERE, error(vm).actions.first())
    }

    @Test
    fun `a frame within 5 s never fires`() = playerTest {
        playingAv1()
        advance(3_000)
        controller.emitFirstFrame()
        advance(60_000)
        assertEquals(1, bodies().size)
    }

    @Test
    fun `a slow start buffering for 20 s then a frame 1 s into READY never fires`() = playerTest {
        claimAv1()
        film(av1)
        answerWith(direct())
        val vm = vm()
        runCurrent()
        vm.onVideoSurface(true)
        controller.state = PlayerState.BUFFERING
        advance(20_000)
        controller.ready()
        advance(1_000)
        controller.emitFirstFrame()
        advance(60_000)
        assertEquals(1, bodies().size)
    }

    @Test
    fun `without a bound surface nothing counts, binding it fires 5 s later`() = playerTest {
        claimAv1()
        val vm = startFilm(direct(), av1)
        answerWith(converted())
        advance(10_000)
        assertEquals(1, bodies().size)
        vm.onVideoSurface(true)
        advance(4_000)
        assertEquals(1, bodies().size)
        advance(3_000)
        assertEquals(2, bodies().size)
    }

    @Test
    fun `an unbound surface stops the count again`() = playerTest {
        val vm = playingAv1()
        answerWith(converted())
        advance(2_000)
        vm.onVideoSurface(false)
        advance(60_000)
        assertEquals(1, bodies().size)
    }

    @Test
    fun `paused and background time does not count`() = playerTest {
        val vm = playingAv1()
        answerWith(converted())
        vm.onPlayPause()
        runCurrent()
        assertEquals(SessionState.Paused, vm.session)
        advance(60_000)
        assertEquals(1, bodies().size)
        vm.onPlayPause()
        runCurrent()
        vm.onStop()
        runCurrent()
        assertEquals(SessionState.Background, vm.session)
        advance(60_000)
        assertEquals(1, bodies().size)
    }

    @Test
    fun `on VLC a file without a frame is re-requested on EXO`() = playerTest {
        vlcAvailable = true
        val vc1 = F.file(1, video = VideoInfo("vc1", 1920, 1080))
        film(vc1)
        answerWith(direct(), unsupported("video_codec_unsupported"))
        val vm = vm()
        runCurrent()
        assertEquals(VLC, vm.state.value.engine)
        vlcController.ready()
        vm.onVideoSurface(true)
        advance(past)
        assertEquals(2, bodies().size)
        assertFalse(bodies()[1].isVlc())
    }

    @Test
    fun `on forced VLC a file without a frame is the generic screen`() = playerTest {
        vlcAvailable = true
        forceVlc = true
        film(av1)
        answerWith(direct())
        val vm = vm()
        runCurrent()
        assertEquals(VLC, vm.state.value.engine)
        vlcController.ready()
        vm.onVideoSurface(true)
        advance(past)
        assertEquals(1, bodies().size)
        assertEquals(UiText.Res(R.string.player_playback_failed), error(vm).message)
    }

    @Test
    fun `a legacy file without a frame is the generic screen with no new request`() = playerTest {
        vlcAvailable = true
        val xvid = F.file(1, video = VideoInfo("mpeg4", 720, 400), container = "avi", fileName = "film.avi")
        film(xvid)
        answerWith(direct())
        val vm = vm()
        runCurrent()
        vlcController.ready()
        vm.onVideoSurface(true)
        advance(past)
        assertEquals(1, bodies().size)
        assertEquals(UiText.Res(R.string.player_playback_failed), error(vm).message)
    }
}
