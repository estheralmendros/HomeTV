package com.hometv.tv.ui.player

import com.hometv.tv.R
import com.hometv.tv.core.api.AudioChoice
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.Me
import com.hometv.tv.core.api.PlaybackRequest
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.SubtitleStyle
import com.hometv.tv.core.api.TrackChoice
import com.hometv.tv.core.api.VideoInfo
import com.hometv.tv.core.data.RatingKind
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.core.playback.PlaybackErrorCodes
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.PlayerState
import com.hometv.tv.core.playback.StyleRow
import com.hometv.tv.core.playback.SubtitleEdgeColor
import com.hometv.tv.core.playback.SubtitleLook
import com.hometv.tv.core.playback.SubtitleSize
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** prd3 FR-T8..FR-T10: the style dialog's draft, saving on every close, the libVLC reload rules. */
class PlayerSubtitleStyleTest : PlayerTestBase() {
    private val large = SubtitleLook.DEFAULT.copy(size = SubtitleSize.LARGE)

    private fun styleDialog(vm: PlayerViewModel) = vm.state.value.dialog as? PlayerDialog.SubtitleStyle

    private fun TestScope.exo(): PlayerViewModel = startDirect()

    /** A film playing on libVLC ("Usar siempre VLC"). */
    private fun TestScope.vlc(): PlayerViewModel {
        vlcAvailable = true
        forceVlc = true
        film()
        answerWith(direct())
        val vm = vm()
        runCurrent()
        vlcController.ready()
        runCurrent()
        assertEquals(PlaybackEngine.VLC, vm.state.value.engine)
        return vm
    }

    private fun PlayerViewModel.openAndGrow() {
        onOpenTracks()
        onOpenSubtitleStyle()
        onStyleStep(StyleRow.SIZE, forward = true)
    }

    @Test
    fun `the draft drives subtitleLook live and the overlay hides while the dialog is open`() = playerTest {
        val vm = exo()
        vm.onShowOverlay()
        assertEquals(OverlayMode.FULL, vm.state.value.overlay)
        vm.onOpenTracks()
        vm.onOpenSubtitleStyle()
        assertEquals(OverlayMode.HIDDEN, vm.state.value.overlay)
        assertEquals(SubtitleLook.DEFAULT, styleDialog(vm)?.ui?.draft)
        vm.onStyleStep(StyleRow.SIZE, forward = true)
        assertEquals(large, vm.state.value.subtitleLook)
        // Media keys and media-session commands never bring the overlay back under the dialog.
        vm.onShowOverlay()
        vm.onRemotePlay()
        vm.onRemoteSeek(forward = true)
        vm.onRemotePause()
        assertEquals(OverlayMode.HIDDEN, vm.state.value.overlay)
        advance(10_000)
        vm.onStyleDialogClosed()
        assertNull(vm.state.value.dialog)
        assertEquals(OverlayMode.FULL, vm.state.value.overlay)
        assertEquals(large, vm.state.value.subtitleLook)
        assertEquals(large, subtitleStyles.style.value)
    }

    @Test
    fun `the overlay stays hidden under the dialog when a paused HLS session is released`() = playerTest {
        val vm = startHls()
        vm.onPlayPause()
        runCurrent()
        assertEquals(SessionState.Paused, vm.session)
        vm.onOpenTracks()
        vm.onOpenSubtitleStyle()
        advance(PlayerViewModel.PAUSE_RELEASE_MS + 1_000)
        assertEquals(SessionState.Released, vm.session)
        assertTrue(vm.state.value.dialog is PlayerDialog.SubtitleStyle)
        assertEquals(OverlayMode.HIDDEN, vm.state.value.overlay)
        vm.onStyleDialogClosed()
        assertEquals(OverlayMode.FULL, vm.state.value.overlay)
    }

    @Test
    fun `Back saves once only when changed, vm onBack and a dismiss save too`() = playerTest {
        val vm = exo()
        vm.onOpenSubtitleStyle()
        vm.onBack()
        runCurrent()
        assertTrue(api.subtitleStyleBodies.isEmpty(), "unchanged: nothing saved")
        vm.openAndGrow()
        vm.onBack()
        runCurrent()
        assertEquals(listOf(SubtitleStyle(size = "large")), api.subtitleStyleBodies)
        vm.onOpenSubtitleStyle()
        vm.onStyleStep(StyleRow.SIZE, forward = false)
        vm.onDialogDismiss()
        runCurrent()
        assertEquals(listOf(SubtitleStyle(size = "large"), SubtitleStyle()), api.subtitleStyleBodies)
        assertEquals(0, controller.reloadCalls, "ExoPlayer applies the look live")
    }

    @Test
    fun `a stored white-on-white opens with Negro and saves it`() = playerTest {
        account.setSubtitleStyle(SubtitleLook.DEFAULT.copy(edgeColor = SubtitleEdgeColor.WHITE))
        val vm = exo()
        vm.onOpenSubtitleStyle()
        assertEquals(SubtitleEdgeColor.BLACK, styleDialog(vm)?.ui?.draft?.edgeColor)
        vm.onBack()
        runCurrent()
        assertEquals(listOf(SubtitleStyle()), api.subtitleStyleBodies)
    }

    @Test
    fun `a failed save shows the toast once and keeps the look for the session`() = playerTest {
        api.onSubtitleStyleWrite = { throw java.io.IOException("down") }
        val vm = exo()
        vm.openAndGrow()
        vm.onBack()
        runCurrent()
        assertEquals(UiText.Res(R.string.subtitle_style_save_failed), vm.state.value.toast)
        assertEquals(large, vm.state.value.subtitleLook)
    }

    @Test
    fun `two quick saves send at most one PUT at a time, the last one winning`() = playerTest {
        val gate = CompletableDeferred<Unit>()
        api.onSubtitleStyleWrite = { gate.await() }
        val vm = exo()
        vm.openAndGrow()
        vm.onBack()
        vm.onOpenSubtitleStyle()
        vm.onStyleStep(StyleRow.POSITION, forward = true)
        vm.onBack()
        runCurrent()
        assertEquals(1, api.subtitleStyleBodies.size)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(SubtitleStyle(size = "large"), SubtitleStyle(size = "large", position = "middle")), api.subtitleStyleBodies)
    }

    @Test
    fun `a late api me while the dialog is open never overwrites the draft`() = playerTest {
        val vm = exo()
        vm.openAndGrow()
        api.onMe = { Me(5, "Esther", "avatar-05", subtitle_style = SubtitleStyle(position = "high")) }
        account.refresh()
        runCurrent()
        assertEquals(large, vm.state.value.subtitleLook)
    }

    // ---- libVLC: reload only for a user close on the active VLC engine -----------------------------

    @Test
    fun `on VLC a user close with a change reloads once, the progress ticker keeps its phase`() = playerTest {
        val vm = vlc() // the 10 s progress ticker started at this READY
        vlcController.onReload = {
            vlcController.isPlaying = false
            vlcController.state = PlayerState.BUFFERING
            vlcController.listener?.onIsPlayingChanged(false)
            vlcController.listener?.onStateChanged(PlayerState.BUFFERING)
        }
        val puts = puts().size
        advance(4_000)
        vm.openAndGrow()
        vm.onBack()
        runCurrent()
        assertEquals(1, vlcController.reloadCalls)
        vlcController.ready()
        runCurrent()
        assertEquals(puts, puts().size, "the reopen's transitions are not progress")
        // Not restarted by the reopen: the next periodic PUT comes at the original 10 s phase.
        advance(6_000)
        assertEquals(puts + 1, puts().size)
        assertNull(vm.state.value.toast)
        // No change: no reload.
        vm.onOpenSubtitleStyle()
        vm.onBack()
        assertEquals(1, vlcController.reloadCalls)
    }

    @Test
    fun `a false reload shows the toast`() = playerTest {
        val vm = vlc()
        vlcController.reloadResult = false
        vm.openAndGrow()
        vm.onDialogDismiss()
        runCurrent()
        assertEquals(1, vlcController.reloadCalls)
        assertEquals(UiText.Res(R.string.subtitle_style_save_failed), vm.state.value.toast)
        assertEquals(listOf(SubtitleStyle(size = "large")), api.subtitleStyleBodies, "the style is saved anyway")
    }

    @Test
    fun `teardown closes save but never reload - the end of playback`() = playerTest {
        val vm = vlc()
        vm.openAndGrow()
        vlcController.end()
        runCurrent()
        assertNull(vm.state.value.dialog)
        assertEquals(listOf(SubtitleStyle(size = "large")), api.subtitleStyleBodies)
        assertEquals(0, vlcController.reloadCalls)
    }

    @Test
    fun `teardown closes save but never reload - an error screen`() = playerTest {
        val vm = vlc()
        vm.openAndGrow()
        // A libVLC failure: the one renew + recovery of the file, then the error screen.
        val failure = PlayerFailure(PlaybackErrorCodes.VLC_ERROR, engine = PlaybackEngine.VLC)
        vlcController.fail(failure)
        runCurrent()
        vlcController.fail(failure)
        runCurrent()
        assertEquals(SessionState.Error, vm.session)
        assertNull(vm.state.value.dialog)
        assertEquals(listOf(SubtitleStyle(size = "large")), api.subtitleStyleBodies)
        assertEquals(0, vlcController.reloadCalls)
    }

    @Test
    fun `teardown closes save but never reload - ON_STOP`() = playerTest {
        val vm = vlc()
        vm.openAndGrow()
        vm.onStop()
        runCurrent()
        assertNull(vm.state.value.dialog)
        assertTrue(vlcController.stopped)
        assertEquals(listOf(SubtitleStyle(size = "large")), api.subtitleStyleBodies)
        assertEquals(0, vlcController.reloadCalls)
    }

    @Test
    fun `teardown closes save but never reload - leaving the player (onCleared)`() = playerTest {
        val vm = vlc()
        vm.openAndGrow()
        clear(vm)
        runCurrent()
        assertEquals(listOf(SubtitleStyle(size = "large")), api.subtitleStyleBodies)
        assertEquals(0, vlcController.reloadCalls)
    }

    /** A film on libVLC whose second audio track (10 channels) needs the server: picking it starts a direct audio switch. */
    private fun TestScope.vlcSwitching(answerSwitch: suspend (PlaybackRequest) -> PlaybackResponse = { awaitCancellation() }): PlayerViewModel {
        vlcAvailable = true
        forceVlc = true
        film(F.file(1, audio = listOf(F.audio(1, default = true), F.audio(2, channels = 10, language = "spa"))))
        answerWith(direct())
        val vm = vm()
        runCurrent()
        vlcController.ready()
        runCurrent()
        answer(answerSwitch)
        vm.onSelectAudio(2)
        runCurrent()
        assertNotNull(vm.state.value.switchChip, "a direct audio switch is pending")
        return vm
    }

    @Test
    fun `on VLC a user close during a pending audio switch reloads once the switch is abandoned`() = playerTest {
        val vm = vlcSwitching()
        vm.openAndGrow()
        vm.onBack()
        runCurrent()
        assertEquals(listOf(SubtitleStyle(size = "large")), api.subtitleStyleBodies)
        assertEquals(0, vlcController.reloadCalls, "the base keeps playing until the switch settles")
        vm.onBack() // abandons the switch: the base media stays, so it is reloaded now
        runCurrent()
        assertNull(vm.state.value.switchChip)
        assertEquals(1, vlcController.reloadCalls)
        assertNull(vm.state.value.toast)
    }

    @Test
    fun `on VLC an attached audio switch owes no reload - its new stream opens with the new look`() = playerTest {
        val gate = CompletableDeferred<Unit>()
        val vm = vlcSwitching { gate.await(); hls("S1", audio = 2, action = "aac") }
        vm.openAndGrow()
        vm.onBack()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertNull(vm.state.value.switchChip)
        assertEquals(2, vlcController.audio)
        vm.onPlayPause()
        vm.onPlayPause()
        runCurrent()
        assertEquals(0, vlcController.reloadCalls)
    }

    @Test
    fun `on VLC an owed reload that fails shows the toast when the switch is abandoned`() = playerTest {
        val vm = vlcSwitching()
        vlcController.reloadResult = false
        vm.openAndGrow()
        vm.onBack()
        runCurrent()
        vm.onBack()
        runCurrent()
        assertEquals(1, vlcController.reloadCalls)
        assertEquals(UiText.Res(R.string.subtitle_style_save_failed), vm.state.value.toast)
    }

    @Test
    fun `an inactive VLC controller is never reloaded`() = playerTest {
        // Episode 1 (an MPEG-4 AVI) plays on libVLC, episode 2 on ExoPlayer: the VLC controller exists but is inactive.
        vlcAvailable = true
        val mpeg4 = VideoInfo("mpeg4", 720, 400)
        episodes(
            episodeDetail(101, listOf(F.file(1, video = mpeg4, container = "avi", fileName = "e1.avi", duration = 1320.0)), next = 102),
            episodeDetail(102, listOf(F.file(2, duration = 1320.0)), next = null),
        )
        answerWith(direct(duration = 1320.0))
        val vm = vm(itemType = "episode", itemId = 101)
        runCurrent()
        assertEquals(PlaybackEngine.VLC, vm.state.value.engine)
        vlcController.ready()
        vlcController.end()
        runCurrent()
        answerWith(direct(fileId = 2, duration = 1320.0))
        vm.onPlayNow()
        runCurrent()
        controller.ready()
        runCurrent()
        assertEquals(PlaybackEngine.EXO, vm.state.value.engine)
        assertTrue(vlcController.stopped)
        vm.openAndGrow()
        vm.onBack()
        runCurrent()
        assertEquals(listOf(SubtitleStyle(size = "large")), api.subtitleStyleBodies)
        assertEquals(0, controller.reloadCalls)
        assertEquals(0, vlcController.reloadCalls)
    }

    // ---- Track choice confirmation outlives the player --------------------------------------------

    @Test
    fun `a pick then leaving before the gated PUT answers still confirms the memory entry`() = playerTest {
        val gate = CompletableDeferred<Unit>()
        api.onTrackChoiceWrite = { _, _, _ -> gate.await() }
        api.onFilm = { id -> FilmDetail(id = id, title = "Dune", media_files = listOf(F.file(1, audio = listOf(F.audio(1, default = true), F.audio(2, language = "spa"))))) }
        answerWith(direct())
        val vm = vm()
        runCurrent()
        controller.ready()
        runCurrent()
        vm.onSelectAudio(2)
        clear(vm)
        gate.complete(Unit)
        runCurrent()
        val memory = repos.trackChoiceMemory
        assertEquals(TrackChoice(AudioChoice("spa", null, 2), null), memory.get(5, RatingKind.FILM, 12))
        memory.fetched(5, RatingKind.FILM, 12, memory.ticket())
        assertNull(memory.get(5, RatingKind.FILM, 12), "confirmed: a later fetch drops it")
    }
}
