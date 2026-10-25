package com.hometv.tv.ui.player

import com.hometv.tv.R
import com.hometv.tv.core.api.PlaybackRequest
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.PlaybackErrorCodes
import com.hometv.tv.core.playback.PlayerAction
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.PlayerState
import com.hometv.tv.core.playback.UrlKind
import com.hometv.tv.ui.text.UiText
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * prd3 §8 Player session states, one test (or more) per row of the transition table: the requests
 * sent (progress PUT, keep-alive, DELETE, `POST .../playback` with its `previous_session_id`,
 * `audio_stream_index`, `start_position_seconds`), the timers running and the next state.
 */
class PlayerSessionStateTest : PlayerTestBase() {
    private val twoAudio = F.file(1, audio = listOf(F.audio(1, default = true), F.audio(2, codec = "dts", channels = 6, language = "spa")))
    private val tenMinutes = 10 * 60_000L

    private fun TestScope.paused(vm: PlayerViewModel) {
        vm.onPlayPause()
        runCurrent()
        assertEquals(SessionState.Paused, vm.session)
    }

    private fun TestScope.released(): PlayerViewModel {
        val vm = startHls()
        controller.positionMs = 600_000
        paused(vm)
        advance(PlayerViewModel.PAUSE_RELEASE_MS)
        assertEquals(SessionState.Released, vm.session)
        clearCalls()
        return vm
    }

    // ---- Rows 1–3: pause, play, the 30 min release -----------------------------------------

    @Test
    fun `row 1 HLS - pause sends a progress PUT, starts the keep-alive and arms the 30 min timer`() = playerTest {
        val vm = startHls()
        clearCalls()
        paused(vm)
        assertEquals(listOf("PUT /api/progress"), api.calls)
        advance(30_000)
        assertEquals(listOf("POST /api/hls/S1/keepalive"), keepAlives())
        advance(60_000)
        assertEquals(3, keepAlives().size)
        assertFalse(vm.state.value.keepScreenOn)
    }

    @Test
    fun `row 1 direct - pause sends a PUT but no keep-alive and no timer`() = playerTest {
        val vm = startDirect()
        clearCalls()
        paused(vm)
        advance(PlayerViewModel.PAUSE_RELEASE_MS + 60_000)
        assertEquals(listOf("PUT /api/progress"), api.calls)
        assertEquals(SessionState.Paused, vm.session)
    }

    @Test
    fun `row 2 - play cancels the timer and stops the keep-alive`() = playerTest {
        val vm = startHls()
        paused(vm)
        advance(10 * 60_000)
        vm.onPlayPause()
        runCurrent()
        assertEquals(SessionState.Playing, vm.session)
        clearCalls()
        advance(PlayerViewModel.PAUSE_RELEASE_MS)
        assertEquals(emptyList<String>(), keepAlives())
        assertEquals(emptyList<String>(), deletes())
        assertEquals(SessionState.Playing, vm.session)
        assertTrue(vm.state.value.keepScreenOn)
    }

    @Test
    fun `row 3 - 30 min of HLS pause stops the keep-alive, sends PUT and DELETE and stops the player`() = playerTest {
        val vm = startHls()
        controller.positionMs = 600_000
        paused(vm)
        clearCalls()
        advance(PlayerViewModel.PAUSE_RELEASE_MS - 1)
        assertEquals(SessionState.Paused, vm.session)
        advance(1)
        assertEquals(SessionState.Released, vm.session)
        assertEquals(listOf("DELETE /api/hls/S1"), deletes())
        assertEquals(600.0, api.progressBodies.last().position_seconds)
        assertTrue("stop" in controller.log)
        assertEquals(OverlayMode.FULL, vm.state.value.overlay)
        val keepAlivesAtRelease = keepAlives().size
        advance(5 * 60_000)
        assertEquals(keepAlivesAtRelease, keepAlives().size)
    }

    // ---- Rows 4–5: background / foreground -------------------------------------------------

    @Test
    fun `row 4 - ON_STOP while playing pauses, sends PUT and DELETE, stops the player and the timers`() = playerTest {
        val vm = startHls()
        controller.positionMs = 120_000
        clearCalls()
        vm.onStop()
        runCurrent()
        assertEquals(SessionState.Background, vm.session)
        assertEquals(listOf("PUT /api/progress", "DELETE /api/hls/S1"), api.calls)
        assertTrue(controller.log.containsAll(listOf("pause", "stop")))
        advance(PlayerViewModel.PAUSE_RELEASE_MS)
        assertEquals(listOf("PUT /api/progress", "DELETE /api/hls/S1"), api.calls)
    }

    @Test
    fun `row 4 - ON_STOP while paused cancels the pause timer`() = playerTest {
        val vm = startHls()
        paused(vm)
        vm.onStop()
        runCurrent()
        clearCalls()
        advance(PlayerViewModel.PAUSE_RELEASE_MS)
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(SessionState.Background, vm.session)
    }

    @Test
    fun `row 5 HLS - ON_START recovers paused at the saved position on a new session and re-arms the timer`() = playerTest {
        val vm = startFilm(hls("S1", audio = 2), twoAudio)
        controller.positionMs = 1_234_000
        vm.onStop()
        runCurrent()
        answerWith(hls("S2", audio = 2))
        clearCalls()
        vm.onStart()
        runCurrent()
        assertEquals(1, posts().size)
        val body = bodies().single()
        assertEquals(1234.0, body.start_position_seconds)
        assertEquals(2, body.audio_stream_index)
        assertNull(body.previous_session_id)
        assertEquals(SessionState.Paused, vm.session)
        assertFalse(controller.playWhenReady)
        advance(30_000)
        assertEquals(listOf("POST /api/hls/S2/keepalive"), keepAlives())
        advance(PlayerViewModel.PAUSE_RELEASE_MS)
        assertEquals(SessionState.Released, vm.session)
        assertEquals(listOf("DELETE /api/hls/S2"), deletes())
    }

    @Test
    fun `row 5 direct - ON_START prepares and seeks to the saved position, paused, with no request`() = playerTest {
        val vm = startDirect()
        controller.positionMs = 300_000
        vm.onStop()
        runCurrent()
        clearCalls()
        vm.onStart()
        runCurrent()
        assertEquals(emptyList<String>(), posts())
        assertTrue(controller.log.contains("prepare"))
        assertEquals(300_000, controller.positionMs)
        assertEquals(SessionState.Paused, vm.session)
        // A new attachment: Back sends its final PUT.
        vm.onBack() // hides the overlay
        vm.onBack()
        runCurrent()
        assertEquals(listOf("PUT /api/progress"), puts())
    }

    @Test
    fun `row 5 - ON_START runs ensureFresh before recovering`() = playerTest {
        val vm = startDirect()
        vm.onStop()
        runCurrent()
        repos.store.saveSession(5, "tok", Instant.parse("2026-10-22T18:00:00Z")) // within 24 h of the test clock
        clearCalls()
        vm.onStart()
        runCurrent()
        assertEquals(listOf("POST /api/profiles/5/session"), api.calls.filter { it.contains("session") })
    }

    @Test
    fun `ON_STOP then ON_START (S2) then Back sends PUT and DELETE S2, S1 deleted exactly once`() = playerTest {
        val vm = startHls("S1")
        vm.onStop()
        runCurrent()
        answerWith(hls("S2"))
        vm.onStart()
        runCurrent()
        controller.ready()
        vm.onBack() // overlay
        vm.onBack()
        runCurrent()
        assertEquals(listOf("DELETE /api/hls/S1", "DELETE /api/hls/S2"), deletes())
        assertEquals(listOf(PlayerEffect.Exit), exits)
    }

    // ---- Rows 6–12: Released ---------------------------------------------------------------

    @Test
    fun `row 6 - OK while released recovers at the saved position and plays`() = playerTest {
        val vm = released()
        answerWith(hls("S2"))
        vm.onPlayPause()
        runCurrent()
        assertEquals(600.0, bodies().single().start_position_seconds)
        assertNull(bodies().single().previous_session_id)
        assertEquals(1, bodies().single().audio_stream_index)
        assertEquals(SessionState.Playing, vm.session)
        assertTrue(controller.playWhenReady)
    }

    @Test
    fun `row 7 - a seek while released moves the saved position and the debounced PUT carries it`() = playerTest {
        val vm = released()
        vm.onSeekKey(forward = true, repeatCount = 0)
        advance(600)
        assertEquals(610_000, vm.state.value.positionMs)
        assertEquals(emptyList<String>(), api.calls)
        advance(2_000)
        assertEquals(listOf("PUT /api/progress"), api.calls)
        assertEquals(610.0, api.progressBodies.single().position_seconds)
        assertEquals(SessionState.Released, vm.session)
        // OK resumes there.
        answerWith(hls("S2"))
        vm.onPlayPause()
        runCurrent()
        assertEquals(610.0, bodies().single().start_position_seconds)
    }

    // ---- §8 Headset and Bluetooth play/pause (the media session's commands) ----------------

    @Test
    fun `a media-session pause is row 1 - PUT, keep-alive and the 30 min timer, then a media-session play is row 2`() = playerTest {
        val vm = startHls()
        clearCalls()
        controller.clearLog()
        controller.remotePause()
        runCurrent()
        assertEquals(SessionState.Paused, vm.session)
        assertEquals(listOf("pause"), controller.log)
        assertEquals(listOf("PUT /api/progress"), api.calls)
        assertEquals(OverlayMode.FULL, vm.state.value.overlay)
        advance(30_000)
        assertEquals(listOf("POST /api/hls/S1/keepalive"), keepAlives())
        controller.remotePlay()
        runCurrent()
        assertEquals(SessionState.Playing, vm.session)
        assertTrue(controller.playWhenReady)
        clearCalls()
        advance(PlayerViewModel.PAUSE_RELEASE_MS)
        assertEquals(emptyList<String>(), keepAlives())
        assertEquals(emptyList<String>(), deletes())
    }

    @Test
    fun `a media-session play while released runs the recovery (row 6)`() = playerTest {
        val vm = released()
        answerWith(hls("S2"))
        controller.remotePlay()
        runCurrent()
        assertEquals(600.0, bodies().single().start_position_seconds)
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `a media-session play while playing and a pause while paused change nothing`() = playerTest {
        val vm = startDirect()
        controller.clearLog()
        controller.remotePlay()
        runCurrent()
        assertEquals(SessionState.Playing, vm.session)
        controller.remotePause()
        controller.remotePause()
        runCurrent()
        assertEquals(SessionState.Paused, vm.session)
        assertEquals(listOf("pause"), controller.log)
    }

    @Test
    fun `a media-session seek forward is the 10 s step of the overlay button`() = playerTest {
        val vm = startDirect()
        controller.positionMs = 100_000
        controller.remoteSeek(forward = true)
        advance(600)
        assertEquals(110_000, controller.positionMs)
        assertEquals(SessionState.Playing, vm.session)
    }

    /** Every media-session command, then the 600 ms seek commit: nothing may reach the player or the API. */
    private fun TestScope.allRemoteCommands() {
        controller.clearLog()
        clearCalls()
        controller.remotePlay()
        controller.remoteSeek(forward = true)
        controller.remoteSeek(forward = false)
        controller.remotePause()
        runCurrent()
        advance(600)
    }

    private fun assertNoPlaybackCommand() {
        assertEquals(emptyList<String>(), controller.log.filter { it == "play" || it.startsWith("seekTo") || it.startsWith("setMediaItem") || it == "prepare" })
    }

    @Test
    fun `media-session commands in the background change nothing`() = playerTest {
        val vm = startHls()
        controller.positionMs = 120_000
        vm.onStop()
        runCurrent()
        assertEquals(SessionState.Background, vm.session)
        allRemoteCommands()
        assertNoPlaybackCommand()
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(SessionState.Background, vm.session)
    }

    @Test
    fun `media-session commands during the next-episode countdown change nothing`() = playerTest {
        val vm = episodePlaying()
        prefetch(vm)
        controller.end()
        runCurrent()
        assertEquals(SessionState.Countdown, vm.session)
        allRemoteCommands()
        assertNoPlaybackCommand()
        assertEquals(emptyList<String>(), api.calls.filter { !it.endsWith("/keepalive") })
        assertEquals(SessionState.Countdown, vm.session)
        assertTrue(vm.state.value.screen is PlayerScreenUi.Countdown)
    }

    @Test
    fun `media-session commands on the error screen change nothing`() = playerTest {
        val vm = startDirect()
        answer({ throw httpError(500, "transcoder_failed", """{"detail": "keyframe_index_failed: x"}""") })
        controller.fail(PlayerFailure(PlaybackErrorCodes.PARSING_CONTAINER_MALFORMED, urlKind = UrlKind.STREAM))
        runCurrent()
        assertTrue(vm.state.value.screen is PlayerScreenUi.Error)
        val session = vm.session
        allRemoteCommands()
        assertNoPlaybackCommand()
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(session, vm.session)
        assertTrue(vm.state.value.screen is PlayerScreenUi.Error)
    }

    @Test
    fun `row 8 - an audio change while released sends one recovery request and stays paused on the new session`() = playerTest {
        film(twoAudio)
        answerWith(hls("S1"))
        val vm = vm()
        runCurrent()
        controller.ready()
        controller.positionMs = 600_000
        paused(vm)
        advance(PlayerViewModel.PAUSE_RELEASE_MS)
        clearCalls()
        answerWith(hls("S2", audio = 2, action = "aac"))
        vm.onSelectAudio(2)
        runCurrent()
        assertEquals(1, posts().size)
        assertEquals(2, bodies().single().audio_stream_index)
        assertEquals(600.0, bodies().single().start_position_seconds)
        assertNull(bodies().single().previous_session_id)
        assertEquals(SessionState.Paused, vm.session)
        assertFalse(controller.playWhenReady)
        assertEquals(2, controller.audio)
        advance(PlayerViewModel.PAUSE_RELEASE_MS)
        assertEquals(SessionState.Released, vm.session) // timer re-armed
    }

    @Test
    fun `row 8 - an unsupported audio change while released shows the toast and stays released`() = playerTest {
        film(twoAudio)
        answerWith(hls("S1"))
        val vm = vm()
        runCurrent()
        controller.ready()
        paused(vm)
        advance(PlayerViewModel.PAUSE_RELEASE_MS)
        answerWith(unsupported("keyframes_too_sparse"))
        vm.onSelectAudio(2)
        runCurrent()
        assertEquals(SessionState.Released, vm.session)
        assertEquals(UiText.Res(R.string.audio_track_unplayable), vm.state.value.toast)
    }

    @Test
    fun `row 9 - a subtitle change while released only fetches the VTT`() = playerTest {
        film(F.file(1))
        answerWith(hls("S1", subs = listOf(F.sub("emb-3", language = "spa"))))
        val vm = vm()
        runCurrent()
        controller.ready()
        paused(vm)
        advance(PlayerViewModel.PAUSE_RELEASE_MS)
        clearCalls()
        vm.onSelectSubtitle("emb-3")
        runCurrent()
        assertEquals(emptyList<String>(), posts())
        assertEquals(1, subtitleUrls.size)
        assertTrue(subtitles.get(1, "emb-3") != null)
        // Applied by the next recovery.
        answerWith(hls("S2", subs = listOf(F.sub("emb-3", language = "spa"))))
        vm.onPlayPause()
        runCurrent()
        assertEquals(listOf("emb-3"), controller.item?.subtitles?.map { it.trackId })
        assertEquals("emb-3", controller.subtitle)
    }

    @Test
    fun `row 10 - Next episode while released starts it with previous_session_id null`() = playerTest {
        episodes(episodeDetail(203, listOf(F.file(1)), next = 204), episodeDetail(204, listOf(F.file(2))))
        answerWith(hls("S1"))
        val vm = vm(itemType = "episode", itemId = 203)
        runCurrent()
        controller.ready()
        paused(vm)
        advance(PlayerViewModel.PAUSE_RELEASE_MS)
        clearCalls()
        answerWith(hls("S2"))
        vm.onNext()
        runCurrent()
        assertEquals(listOf("POST /api/media-files/2/playback"), posts())
        assertNull(bodies().single().previous_session_id)
        assertEquals(emptyList<String>(), deletes())
    }

    @Test
    fun `row 11 - Back while released sends no DELETE, and a PUT only when the position moved`() = playerTest {
        val vm = released()
        vm.onBack() // hides the overlay
        vm.onBack()
        runCurrent()
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(listOf(PlayerEffect.Exit), exits)

        val moved = released()
        moved.onSeekKey(forward = false, repeatCount = 0)
        advance(600)
        moved.onBack()
        moved.onBack()
        runCurrent()
        assertEquals(listOf("PUT /api/progress"), api.calls)
        assertEquals(590.0, api.progressBodies.single().position_seconds)
    }

    @Test
    fun `row 12 - ON_STOP and ON_START while released send nothing`() = playerTest {
        val vm = released()
        vm.onStop()
        vm.onStart()
        advance(60_000)
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(SessionState.Released, vm.session)
    }

    // ---- Rows 13–14 ------------------------------------------------------------------------

    @Test
    fun `row 13 - Back while playing sends PUT and DELETE and releases the player`() = playerTest {
        val vm = startHls()
        clearCalls()
        vm.onBack()
        runCurrent()
        assertEquals(listOf("PUT /api/progress", "DELETE /api/hls/S1"), api.calls)
        assertTrue(controller.released)
        assertEquals(SessionState.Exited, vm.session)
        assertEquals(listOf(PlayerEffect.Exit), exits)
    }

    @Test
    fun `row 13 - Back with the overlay visible hides it first`() = playerTest {
        val vm = startHls()
        paused(vm)
        clearCalls()
        vm.onBack()
        assertEquals(OverlayMode.HIDDEN, vm.state.value.overlay)
        assertEquals(SessionState.Paused, vm.session)
        vm.onBack()
        runCurrent()
        assertEquals(listOf("PUT /api/progress", "DELETE /api/hls/S1"), api.calls)
    }

    @Test
    fun `row 14 - an HLS audio switch hands the session over (no DELETE, no keep-alive for it)`() = playerTest {
        val vm = startFilm(hls("S1"), twoAudio)
        controller.positionMs = 100_000
        paused(vm)
        clearCalls()
        answerWith(hls("S2", audio = 2, action = "aac"))
        vm.onSelectAudio(2)
        runCurrent()
        val body = bodies().single()
        assertEquals("S1", body.previous_session_id)
        assertEquals(2, body.audio_stream_index)
        assertEquals(100.0, body.start_position_seconds)
        assertEquals(emptyList<String>(), deletes())
        assertEquals(listOf("PUT /api/progress"), puts())
        advance(60_000)
        assertTrue(keepAlives().all { it.contains("S2") })
        // Row 32: a switch requested from Paused attaches paused.
        assertEquals(SessionState.Paused, vm.session)
        vm.onBack()
        vm.onBack()
        runCurrent()
        assertEquals(listOf("DELETE /api/hls/S2"), deletes())
    }

    // ---- Rows 15–19: end of an item --------------------------------------------------------

    @Test
    fun `row 15 - a film ending sends PUT(end) and DELETE and exits`() = playerTest {
        startHls()
        clearCalls()
        controller.end()
        runCurrent()
        assertEquals(listOf("PUT /api/progress", "DELETE /api/hls/S1"), api.calls)
        assertEquals(7200.0, api.progressBodies.single().position_seconds)
        assertEquals(listOf(PlayerEffect.Exit), exits)
    }

    private fun TestScope.episodePlaying(sid: String = "S1", nextFiles: List<com.hometv.tv.core.api.MediaFile> = listOf(F.file(2)), hasNext: Boolean = true): PlayerViewModel {
        val list = buildList {
            add(episodeDetail(203, listOf(F.file(1)), next = if (hasNext) 204 else null))
            if (hasNext) add(episodeDetail(204, nextFiles, title = "Halloween", episode = 4))
        }
        episodes(*list.toTypedArray())
        answerWith(hls(sid))
        val vm = vm(itemType = "episode", itemId = 203)
        runCurrent()
        controller.ready()
        runCurrent()
        return vm
    }

    private fun TestScope.prefetch(vm: PlayerViewModel) {
        controller.positionMs = 7_175_000
        advance(1_000)
        assertTrue(api.calls.contains("GET /api/episodes/204"))
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `row 16 - an episode ending with a prefetched next starts the countdown and keeps the session alive`() = playerTest {
        val vm = episodePlaying()
        prefetch(vm)
        clearCalls()
        controller.end()
        runCurrent()
        assertEquals(SessionState.Countdown, vm.session)
        assertEquals(listOf("PUT /api/progress"), api.calls)
        val countdown = vm.state.value.screen as PlayerScreenUi.Countdown
        assertEquals(10, countdown.next.seconds)
        assertEquals(UiText.Value(com.hometv.tv.core.util.EpisodeTitle.Named("Halloween")), countdown.next.title)
        advance(5_000)
        assertEquals(5, (vm.state.value.screen as PlayerScreenUi.Countdown).next.seconds)
        advance(25_000) // countdown done at 10 s; S1 was kept alive until then
    }

    @Test
    fun `row 16 - the countdown holds the session (no DELETE) and the part prompt runs 5 s`() = playerTest {
        val vm = startFilm(hls("S1"), F.part(1, 1), F.part(2, 2))
        clearCalls()
        controller.end()
        runCurrent()
        assertEquals(SessionState.Countdown, vm.session)
        assertEquals(5, (vm.state.value.screen as PlayerScreenUi.Countdown).next.seconds)
        assertEquals(NextKind.PART, (vm.state.value.screen as PlayerScreenUi.Countdown).next.kind)
        assertEquals(emptyList<String>(), deletes())
        answerWith(hls("S2"))
        advance(5_000)
        assertEquals(listOf("POST /api/media-files/2/playback"), posts())
        assertEquals("S1", bodies().single().previous_session_id)
        assertEquals(0.0, bodies().single().start_position_seconds)
        assertEquals(emptyList<String>(), deletes())
    }

    @Test
    fun `row 17 - an episode ending before the next is resolved resolves it and keeps the session alive`() = playerTest {
        val vm = episodePlaying()
        api.onEpisode = { id ->
            delay(45_000)
            episodeDetail(id, listOf(F.file(2)), title = "Halloween")
        }
        clearCalls()
        controller.end()
        runCurrent()
        assertEquals(SessionState.EndedResolving, vm.session)
        assertEquals(listOf("PUT /api/progress", "GET /api/episodes/204"), api.calls)
        advance(30_000)
        assertEquals(listOf("POST /api/hls/S1/keepalive"), keepAlives())
        advance(15_000)
        assertEquals(SessionState.Countdown, vm.session) // row 18
        assertEquals(emptyList<String>(), deletes())
    }

    @Test
    fun `row 18 - resolving to no next episode (D12 skips) stops the keep-alive, DELETEs and exits`() = playerTest {
        // E204 holds the playing file 1 (multi-episode file) and is the last one.
        episodes(episodeDetail(203, listOf(F.file(1)), next = 204), episodeDetail(204, listOf(F.file(1)), next = null))
        answerWith(hls("S1"))
        val vm = vm(itemType = "episode", itemId = 203)
        runCurrent()
        controller.ready()
        clearCalls()
        controller.end()
        runCurrent()
        assertEquals(listOf("DELETE /api/hls/S1"), deletes())
        assertEquals(listOf(PlayerEffect.Exit), exits)
        assertEquals(SessionState.Exited, vm.session)
    }

    @Test
    fun `row 19 - an empty nextPlan shows the end screen and DELETEs at once, Back sends no second DELETE`() = playerTest {
        val av1 = F.file(2, video = com.hometv.tv.core.api.VideoInfo("av1", 1920, 1080))
        val vm = episodePlaying(nextFiles = listOf(av1))
        prefetch(vm)
        clearCalls()
        controller.end()
        runCurrent()
        assertEquals(SessionState.EndScreen, vm.session)
        assertEquals(listOf("DELETE /api/hls/S1"), deletes())
        val screen = vm.state.value.screen as PlayerScreenUi.EndScreen
        assertEquals(UiText.Res(R.string.next_unplayable), screen.message)
        assertFalse(screen.canRetry)
        advance(60_000)
        assertEquals(emptyList<String>(), keepAlives())
        vm.onBack() // row 29
        runCurrent()
        assertEquals(listOf("DELETE /api/hls/S1"), deletes())
        assertEquals(listOf(PlayerEffect.Exit), exits)
    }

    @Test
    fun `row 19 - a prefetch that fails twice shows the load-failed end screen`() = playerTest {
        val vm = episodePlaying()
        api.onEpisode = { throw IOException("down") }
        prefetch(vm) // first failure
        clearCalls()
        controller.end()
        runCurrent()
        assertEquals(listOf("PUT /api/progress", "GET /api/episodes/204", "DELETE /api/hls/S1"), api.calls)
        val screen = vm.state.value.screen as PlayerScreenUi.EndScreen
        assertEquals(UiText.Res(R.string.next_load_failed), screen.message)
        assertTrue(screen.canRetry)
    }

    // ---- Rows 20–29: countdown, EndedIdle, end screens -------------------------------------

    @Test
    fun `row 20 - the countdown reaching 0 starts the next episode with previous_session_id S1`() = playerTest {
        val vm = episodePlaying()
        prefetch(vm)
        controller.end()
        runCurrent()
        clearCalls()
        answerWith(hls("S2"))
        advance(10_000)
        assertEquals(listOf("POST /api/media-files/2/playback"), posts())
        assertEquals("S1", bodies().single().previous_session_id)
        assertEquals(emptyList<String>(), deletes())
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `row 20 - Play now starts at once`() = playerTest {
        val vm = episodePlaying()
        prefetch(vm)
        controller.end()
        runCurrent()
        answerWith(hls("S2"))
        clearCalls()
        vm.onPlayNow()
        runCurrent()
        assertEquals("S1", bodies().single().previous_session_id)
    }

    @Test
    fun `row 21 - Cancel or Back during the countdown DELETEs at once and exits`() = playerTest {
        val vm = episodePlaying()
        prefetch(vm)
        controller.end()
        runCurrent()
        clearCalls()
        vm.onBack()
        runCurrent()
        assertEquals(listOf("DELETE /api/hls/S1"), api.calls)
        assertEquals(listOf(PlayerEffect.Exit), exits)
        advance(20_000)
        assertEquals(emptyList<String>(), posts())
    }

    @Test
    fun `row 22 then 24 and 25 - ON_STOP during the countdown DELETEs, ON_START shows it stopped, Play now sends prev null`() = playerTest {
        val vm = episodePlaying()
        prefetch(vm)
        controller.end()
        runCurrent()
        clearCalls()
        vm.onStop()
        runCurrent()
        assertEquals(SessionState.EndedIdle, vm.session)
        assertEquals(listOf("DELETE /api/hls/S1"), api.calls)
        advance(60_000)
        vm.onStart()
        runCurrent()
        assertEquals(listOf("DELETE /api/hls/S1"), api.calls) // row 24: no request
        assertNull((vm.state.value.screen as PlayerScreenUi.Countdown).next.seconds)
        answerWith(hls("S2"))
        vm.onPlayNow()
        runCurrent()
        assertNull(bodies().single().previous_session_id)
    }

    @Test
    fun `row 23 - ON_STOP while resolving DELETEs, the resolution continues to EndedIdle`() = playerTest {
        val vm = episodePlaying()
        api.onEpisode = { id ->
            delay(5_000)
            episodeDetail(id, listOf(F.file(2)))
        }
        controller.end()
        runCurrent()
        clearCalls()
        vm.onStop()
        runCurrent()
        assertEquals(listOf("DELETE /api/hls/S1"), api.calls)
        advance(5_000)
        assertEquals(SessionState.EndedIdle, vm.session)
        advance(60_000)
        assertEquals(listOf("DELETE /api/hls/S1"), api.calls)
    }

    @Test
    fun `row 26 - Back from EndedIdle sends no DELETE`() = playerTest {
        val vm = episodePlaying()
        prefetch(vm)
        controller.end()
        runCurrent()
        vm.onStop()
        runCurrent()
        clearCalls()
        vm.onBack()
        runCurrent()
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(listOf(PlayerEffect.Exit), exits)
    }

    @Test
    fun `row 27 - ON_STOP and ON_START on an end screen send nothing`() = playerTest {
        val av1 = F.file(2, video = com.hometv.tv.core.api.VideoInfo("av1", 1920, 1080))
        val vm = episodePlaying(nextFiles = listOf(av1))
        prefetch(vm)
        controller.end()
        runCurrent()
        clearCalls()
        vm.onStop()
        vm.onStart()
        runCurrent()
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(SessionState.EndScreen, vm.session)
    }

    @Test
    fun `row 28 - Retry on the load-failed end screen resolves again and the countdown starts with no session held`() = playerTest {
        val vm = episodePlaying()
        api.onEpisode = { throw IOException("down") }
        controller.end()
        runCurrent()
        assertEquals(SessionState.EndScreen, vm.session)
        episodes(episodeDetail(203, listOf(F.file(1)), next = 204), episodeDetail(204, listOf(F.file(2))))
        vm.onEndScreenRetry()
        runCurrent()
        assertEquals(SessionState.Countdown, vm.session)
        clearCalls()
        answerWith(hls("S2"))
        vm.onPlayNow()
        runCurrent()
        assertNull(bodies().single().previous_session_id)
        assertEquals(emptyList<String>(), deletes())
    }

    // ---- Row 30: onCleared -----------------------------------------------------------------

    @Test
    fun `row 30 - onCleared sends the final PUT and DELETE through appScope`() = playerTest {
        val vm = startHls()
        api.onProgress = { delay(3_000); com.hometv.tv.core.api.Progress() }
        api.onDeleteHls = { delay(3_000); com.hometv.tv.core.data.FakeHomeTvApi.empty(204) }
        clearCalls()
        clear(vm)
        advance(3_000)
        assertEquals(listOf("PUT /api/progress", "DELETE /api/hls/S1"), api.calls)
        assertTrue(controller.released)
    }

    // ---- Rows 31–33: Loading, ON_STOP, late responses --------------------------------------

    @Test
    fun `row 31 and 5 - ON_STOP during preparing sends nothing more, ON_START re-sends with prev null and attaches paused`() = playerTest {
        film(F.file(1))
        answer({ throw httpError(503, "preparing", """{"estimated_seconds": 200}""", retryAfter = "5") })
        val vm = vm()
        runCurrent()
        assertEquals(PlayerScreenUi.Loading(com.hometv.tv.core.playback.PreparingEstimate.Minutes(4)), vm.state.value.screen)
        vm.onStop()
        runCurrent()
        assertEquals(SessionState.Background, vm.session)
        advance(60_000)
        assertEquals(1, posts().size)
        answerWith(hls("S1"))
        vm.onStart()
        runCurrent()
        assertEquals(2, posts().size)
        assertNull(bodies().last().previous_session_id)
        assertEquals(SessionState.Paused, vm.session)
        assertFalse(controller.playWhenReady)
    }

    @Test
    fun `row 33 - a POST on the wire at ON_STOP completes, its session is DELETEd once, nothing plays, and ON_START waits for it`() = playerTest {
        film(F.file(1))
        answer({ delay(3_000); hls("S1") })
        val vm = vm()
        runCurrent()
        advance(1_000)
        vm.onStop()
        runCurrent()
        advance(1_000)
        answerWith(hls("S2"))
        vm.onStart() // at 2 s
        runCurrent()
        assertEquals(1, posts().size) // the re-issue waits for the orphan and its DELETE
        advance(1_000)
        assertEquals(listOf("POST /api/media-files/1/playback", "DELETE /api/hls/S1", "POST /api/media-files/1/playback"), api.calls.filter { !it.startsWith("GET") })
        assertFalse(controller.log.any { it.startsWith("setMediaItem /api/hls/S1") || it.contains("S1/index") })
        assertEquals(SessionState.Paused, vm.session)
    }

    @Test
    fun `row 33 - a 200 arriving after Back is never played and its session is DELETEd`() = playerTest {
        film(F.file(1))
        answer({ delay(3_000); hls("S1") })
        val vm = vm()
        runCurrent()
        advance(1_000)
        vm.onBack()
        runCurrent()
        assertEquals(listOf(PlayerEffect.Exit), exits)
        clear(vm)
        advance(5_000)
        assertEquals(listOf("DELETE /api/hls/S1"), deletes())
        assertFalse(controller.log.contains("prepare"))
    }

    @Test
    fun `row 35 - Back while loading cancels the preparing loop and exits`() = playerTest {
        film(F.file(1))
        answer({ throw httpError(503, "preparing", "{}", retryAfter = "5") })
        val vm = vm()
        runCurrent()
        vm.onBack()
        advance(60_000)
        assertEquals(1, posts().size)
        assertEquals(listOf(PlayerEffect.Exit), exits)
    }

    // ---- Rows 34, 36–41: direct → HLS audio switch -----------------------------------------

    private fun TestScope.switching(answerSwitch: suspend (PlaybackRequest) -> PlaybackResponse): PlayerViewModel {
        val vm = startFilm(direct(), twoAudio)
        controller.positionMs = 100_000
        clearCalls()
        answer(answerSwitch)
        vm.onSelectAudio(2)
        runCurrent()
        return vm
    }

    @Test
    fun `row 34 - Back during a switch's preparing keeps the direct media playing on the old track`() = playerTest {
        val vm = switching { throw httpError(503, "preparing", """{"estimated_seconds": 300}""", retryAfter = "5") }
        assertEquals(PlayerScreenUi.Video, vm.state.value.screen)
        assertEquals(UiText.Value(com.hometv.tv.ui.text.PreparingText(com.hometv.tv.core.playback.PreparingEstimate.Minutes(5), R.string.player_audio_preparing)), vm.state.value.switchChip)
        assertTrue(vm.state.value.keepScreenOn)
        vm.onBack()
        advance(60_000)
        assertEquals(1, posts().size)
        assertEquals(SessionState.Playing, vm.session)
        assertNull(vm.state.value.switchChip)
        assertTrue(controller.isPlaying)
        assertEquals(1, controller.audio)
        assertEquals(emptyList<PlayerEffect>(), exits)
    }

    @Test
    fun `row 36 and 32 - pausing during a switch reports progress and the 200 attaches paused at the position at attach time`() = playerTest {
        var release = false
        val vm = switching {
            while (!release) delay(1_000)
            hls("S1", audio = 2, action = "aac")
        }
        assertEquals(UiText.Res(R.string.player_audio_switching), vm.state.value.switchChip)
        vm.onPlayPause()
        runCurrent()
        assertEquals(listOf("PUT /api/progress"), puts())
        assertFalse(controller.isPlaying)
        controller.positionMs = 130_000
        release = true
        advance(1_000)
        assertEquals(SessionState.Paused, vm.session)
        assertEquals("setMediaItem https://hometv.tail1234.ts.net/api/hls/S1/index.m3u8 @130000", controller.log.last { it.startsWith("setMediaItem") })
        assertFalse(controller.playWhenReady)
        assertEquals(2, controller.audio)
        assertEquals(100.0, bodies().single().start_position_seconds)
        assertNull(bodies().single().previous_session_id)
    }

    @Test
    fun `row 36 - a preparing retry of a switch sends the direct position at that moment`() = playerTest {
        val vm = switching { throw httpError(503, "preparing", "{}", retryAfter = "5") }
        controller.positionMs = 105_000
        advance(5_000)
        assertEquals(listOf(100.0, 105.0), bodies().map { it.start_position_seconds })
        assertEquals(SessionState.Loading, vm.session)
    }

    @Test
    fun `row 37 - the direct media ending during a switch drops it, DELETEs the late response, and the film exits`() = playerTest {
        val vm = switching { delay(5_000); hls("S9", audio = 2) }
        controller.end()
        runCurrent()
        assertEquals(listOf(PlayerEffect.Exit), exits)
        advance(5_000)
        assertEquals(listOf("DELETE /api/hls/S9"), deletes())
        assertFalse(controller.log.any { it.contains("S9") })
        assertEquals(SessionState.Exited, vm.session)
    }

    @Test
    fun `row 38 - Next episode during a switch abandons it first`() = playerTest {
        val e1 = F.file(1, audio = listOf(F.audio(1), F.audio(2, codec = "dts", channels = 6)))
        episodes(episodeDetail(203, listOf(e1), next = 204), episodeDetail(204, listOf(F.file(2))))
        answerWith(direct())
        val vm = vm(itemType = "episode", itemId = 203)
        runCurrent()
        controller.ready()
        answer({ delay(5_000); hls("S9", audio = 2) }, { direct(fileId = 2) })
        vm.onSelectAudio(2)
        runCurrent()
        vm.onNext()
        advance(5_000)
        assertEquals(listOf("POST /api/media-files/1/playback", "POST /api/media-files/1/playback", "POST /api/media-files/2/playback").size, posts().size)
        assertEquals(listOf("DELETE /api/hls/S9"), deletes())
        assertTrue(controller.item!!.url.endsWith("/api/media-files/2/stream"))
    }

    @Test
    fun `row 39 - a 200 arriving in the direct media's last 2 s is dropped and DELETEd`() = playerTest {
        val vm = switching { delay(1_000); hls("S9", audio = 2) }
        controller.positionMs = 7_199_000
        advance(1_000)
        assertEquals(listOf("DELETE /api/hls/S9"), deletes())
        assertEquals(SessionState.Playing, vm.session)
        assertTrue(controller.item!!.url.endsWith("/stream"))
    }

    @Test
    fun `row 41 - a direct media failure during a switch drops it and shows the connection error`() = playerTest {
        val vm = switching { delay(5_000); hls("S9", audio = 2) }
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_NETWORK_CONNECTION_FAILED, urlKind = UrlKind.STREAM))
        runCurrent()
        val error = vm.state.value.screen as PlayerScreenUi.Error
        assertEquals(listOf(PlayerAction.RETRY_FROM_HERE, PlayerAction.BACK), error.actions)
        advance(5_000)
        assertEquals(listOf("DELETE /api/hls/S9"), deletes())
        assertEquals(1, posts().size)
    }

    @ParameterizedTest(name = "row 40 - {0}")
    @MethodSource("switchFailures")
    fun `row 40 - a failed switch is a toast and the direct media keeps playing`(case: SwitchCase) = playerTest {
        val vm = switching(case.answer)
        advance(case.waitMs)
        assertEquals(UiText.Res(case.toast), vm.state.value.toast)
        val sent = posts().size
        if (case.waitMs == 0L) assertEquals(1, sent) // no `audio_stream_index = null` retry, no re-send
        assertEquals(SessionState.Playing, vm.session)
        assertTrue(controller.isPlaying)
        assertEquals(1, controller.audio)
        assertTrue(vm.state.value.screen == PlayerScreenUi.Video)
        assertNull(vm.state.value.switchChip)
        advance(PlayerViewModel.PAUSE_RELEASE_MS)
        assertEquals(sent, posts().size)
    }

    @Test
    fun `row 40 - an unplayable track is marked no disponible in the dialog and can be chosen again`() = playerTest {
        val vm = switching { PlaybackResponse("unsupported", reason = "keyframes_too_sparse") }
        vm.onOpenTracks()
        val label = (vm.state.value.dialog as PlayerDialog.Tracks).tracks.audio.first { it.key == "2" }.label as UiText.Value
        assertTrue(com.hometv.tv.core.playback.TrackTag.Unavailable in (label.value as com.hometv.tv.core.playback.TrackLabel).tags)
        answerWith(hls("S1", audio = 2, action = "aac"))
        vm.onSelectAudio(2)
        runCurrent()
        assertEquals(2, posts().size)
        assertEquals(2, controller.audio)
    }

    @Test
    fun `keepScreenOn follows the direct media while a switch runs (paused switchBase = off)`() = playerTest {
        val vm = switching { delay(60_000); hls("S1", audio = 2) }
        assertTrue(vm.state.value.keepScreenOn)
        vm.onPlayPause()
        assertFalse(vm.state.value.keepScreenOn)
    }

    @Test
    fun `row 40 - a 401 renews once and re-sends the POST once`() = playerTest {
        var first = true
        val vm = switching {
            if (first) {
                first = false
                throw httpError(401, "unauthorized")
            }
            hls("S1", audio = 2, action = "aac")
        }
        assertEquals(listOf("POST /api/media-files/1/playback", "POST /api/profiles/5/session", "POST /api/media-files/1/playback"), api.calls.filter { it.startsWith("POST") })
        assertEquals(SessionState.Playing, vm.session)
        assertEquals(2, controller.audio)
    }

    @Test
    fun `row 40 - a 401 whose renewal fails with an IOException is a toast, not the Connection Error`() = playerTest {
        api.onCreateSession = { throw IOException("down") }
        val received = mutableListOf<com.hometv.tv.core.data.AppEvent>()
        repos.recordEvents(backgroundScope)
        val vm = switching { throw httpError(401, "unauthorized") }
        assertEquals(UiText.Res(R.string.audio_switch_failed), vm.state.value.toast)
        assertEquals(1, posts().size)
        assertTrue(repos.received.none { it is com.hometv.tv.core.data.AppEvent.ServerUnreachable })
        assertTrue(received.isEmpty())
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `rows 31 and 5 with a switchBase - Home during a switch, return paused on the old track with no request`() = playerTest {
        val vm = switching { delay(3_000); hls("S1", audio = 2, action = "aac") }
        answer({ unsupported("keyframes_too_sparse") }) // must never be called
        controller.positionMs = 150_000
        vm.onStop()
        runCurrent()
        assertEquals(listOf("PUT /api/progress"), puts())
        assertTrue("stop" in controller.log)
        advance(3_000)
        assertEquals(listOf("DELETE /api/hls/S1"), deletes())
        clearCalls()
        vm.onStart()
        runCurrent()
        assertEquals(emptyList<String>(), posts())
        assertEquals(SessionState.Paused, vm.session)
        assertEquals(150_000, controller.positionMs)
        assertEquals(1, controller.audio)
        assertEquals(UiText.Res(R.string.audio_change_cancelled), vm.state.value.toast)
        assertTrue(vm.state.value.screen == PlayerScreenUi.Video)
    }

    @Test
    fun `rows 31 and 5 without a switchBase - an HLS switch in flight at ON_STOP is re-sent with the requested index and prev null`() = playerTest {
        val vm = startFilm(hls("S1"), twoAudio)
        controller.positionMs = 100_000
        answer({ throw httpError(503, "preparing", "{}", retryAfter = "5") })
        vm.onSelectAudio(2)
        runCurrent()
        assertEquals("S1", bodies().last().previous_session_id)
        vm.onStop()
        runCurrent()
        clearCalls()
        answerWith(hls("S2", audio = 2, action = "aac"))
        vm.onStart()
        runCurrent()
        assertEquals(2, bodies().single().audio_stream_index)
        assertNull(bodies().single().previous_session_id)
        assertEquals(SessionState.Paused, vm.session)
        assertEquals(emptyList<String>(), deletes().filter { it.endsWith("S1") })
    }

    @Test
    fun `a failing HLS audio switch shows the error and Retry re-sends it with prev null`() = playerTest {
        val vm = startFilm(hls("S1"), twoAudio)
        answer({ throw httpError(503, "too_many_sessions") })
        vm.onSelectAudio(2)
        runCurrent()
        val error = vm.state.value.screen as PlayerScreenUi.Error
        assertEquals(UiText.Res(R.string.player_too_many_sessions), error.message)
        answerWith(hls("S2", audio = 2))
        vm.onErrorAction(PlayerAction.RETRY)
        runCurrent()
        assertEquals("S1", bodies()[1].previous_session_id)
        assertNull(bodies().last().previous_session_id)
        assertEquals(2, bodies().last().audio_stream_index)
        assertEquals(emptyList<String>(), deletes().filter { it.endsWith("S1") })
    }

    data class SwitchCase(val name: String, val answer: suspend (PlaybackRequest) -> PlaybackResponse, val toast: Int, val waitMs: Long = 0) {
        override fun toString() = name
    }

    companion object {
        @JvmStatic
        fun switchFailures() = listOf(
            SwitchCase("unsupported keyframes_too_sparse", { PlaybackResponse("unsupported", reason = "keyframes_too_sparse") }, R.string.audio_track_unplayable),
            SwitchCase("500 keyframe_index_failed", { throw httpError(500, "transcoder_failed", """{"detail": "keyframe_index_failed: no cues"}""") }, R.string.audio_track_unplayable),
            SwitchCase("422 invalid_audio_stream (no null retry)", { throw httpError(422, "invalid_audio_stream") }, R.string.audio_track_unplayable),
            SwitchCase("503 too_many_sessions", { throw httpError(503, "too_many_sessions") }, R.string.player_too_many_sessions),
            SwitchCase("503 hls_disk_full", { throw httpError(503, "hls_disk_full") }, R.string.player_hls_disk_full),
            SwitchCase("preparing cap", { throw httpError(503, "preparing", "{}", retryAfter = "5") }, R.string.audio_preparing_timeout, waitMs = 121_000),
            SwitchCase("404 file_missing", { throw httpError(404, "file_missing") }, R.string.audio_switch_failed),
            SwitchCase("IOException", { throw IOException("reset") }, R.string.audio_switch_failed),
            SwitchCase("generic 503", { throw httpError(503, "something_else") }, R.string.audio_switch_failed),
            SwitchCase("500 transcoder_failed other detail", { throw httpError(500, "transcoder_failed", """{"detail": "ffmpeg exited"}""") }, R.string.audio_switch_failed),
        )
    }
}
