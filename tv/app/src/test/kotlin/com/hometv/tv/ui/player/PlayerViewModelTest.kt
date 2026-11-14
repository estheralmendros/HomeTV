package com.hometv.tv.ui.player

import com.hometv.tv.R
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.VideoInfo
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.data.SubtitleFetch
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.PlaybackErrorCodes
import com.hometv.tv.core.playback.PlayerAction
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.PreparingEstimate
import com.hometv.tv.core.playback.RendererType
import com.hometv.tv.core.playback.TrackCheckInput
import com.hometv.tv.core.playback.UrlKind
import com.hometv.tv.core.util.EpisodeCode
import com.hometv.tv.core.util.EpisodeTitle
import com.hometv.tv.core.util.LanguageName
import com.hometv.tv.core.playback.TrackLabel
import com.hometv.tv.core.playback.TrackTag
import com.hometv.tv.testing.TEST_SERVER
import com.hometv.tv.ui.text.UiText
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Player (ViewModel with a fake `PlayerController`, virtual time). */
class PlayerViewModelTest : PlayerTestBase() {
    @Test
    fun `an unknown launchId (process death) pops at once without any request`() = playerTest {
        val vm = vm(launchId = "restored-after-process-death")
        runCurrent()
        assertEquals(listOf(PlayerEffect.Exit), exits)
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(SessionState.Exited, vm.session)
    }

    @Test
    fun `a film loads its detail first, then requests playback with per-file caps, the resume position and no audio index`() = playerTest {
        film(F.file(1, duration = 7200.0), progress = F.progress(1, position = 3723.0))
        answerWith(direct())
        val vm = vm()
        assertEquals(PlayerScreenUi.Loading(null), vm.state.value.screen)
        runCurrent()
        assertEquals(listOf("GET /api/films/12", "POST /api/media-files/1/playback"), api.calls)
        val body = bodies().single()
        assertEquals(3723.0, body.start_position_seconds)
        assertNull(body.audio_stream_index)
        assertNull(body.previous_session_id)
        assertEquals(listOf("h264", "hevc"), body.client_caps.video_codecs)
        assertEquals(listOf("mp4", "mkv", "webm", "ts"), body.client_caps.containers)
        assertEquals("setMediaItem $TEST_SERVER/api/media-files/1/stream @3723000", controller.log.first { it.startsWith("setMediaItem") })
        assertEquals("video/x-matroska", controller.item?.mimeType)
        assertEquals(1, controller.audio) // pinned to the response's audio_stream_index
        assertTrue(controller.playWhenReady)
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `the audio route is read for every playback request`() = playerTest {
        val vm = startDirect()
        codecs.route = com.hometv.tv.core.playback.AudioRouteCaps(setOf(com.hometv.tv.core.playback.AudioEncodings.E_AC3, com.hometv.tv.core.playback.AudioEncodings.DTS), 8)
        answerWith(direct())
        controller.fail(PlayerFailure(PlaybackErrorCodes.PARSING_CONTAINER_MALFORMED, urlKind = UrlKind.STREAM))
        runCurrent()
        val second = bodies().last().client_caps
        assertTrue("eac3" in second.audio_codecs && "dts" in second.audio_codecs)
        assertEquals(8, second.max_audio_channels)
        assertFalse("eac3" in bodies().first().client_caps.audio_codecs)
        assertTrue(vm.session == SessionState.Playing)
    }

    @Test
    fun `a Home episode starts from its payload without waiting for the episode detail`() = playerTest {
        val payload = EpisodeSummary(
            id = 203, show_id = 7, show_title = "The Office", season_number = 2, episode_number = 3, title = "Office Olympics",
            progress = F.progress(1, position = 600.0, duration = 1320.0), media_files = listOf(F.file(1, duration = 1320.0)),
        )
        api.onEpisode = { id -> delay(10_000); episodeDetail(id, listOf(F.file(1, duration = 1320.0)), next = 204) }
        answerWith(direct(duration = 1320.0))
        val vm = vm(itemType = "episode", itemId = 203, payload = payload)
        runCurrent()
        assertEquals(listOf("GET /api/episodes/203", "POST /api/media-files/1/playback"), api.calls)
        assertEquals(600.0, bodies().single().start_position_seconds)
        assertEquals(UiText.Data("The Office"), vm.state.value.title)
        assertEquals(UiText.Joined(listOf(UiText.Value(EpisodeCode(2, 3)), UiText.Value(EpisodeTitle.Named("Office Olympics")))), vm.state.value.subtitle)
        assertNull(vm.state.value.nextButton)
        advance(10_000)
        assertEquals(NextKind.EPISODE, vm.state.value.nextButton)
    }

    @Test
    fun `a removed film shows the item-gone screen and emits ItemGone`() = playerTest {
        repos.recordEvents(backgroundScope)
        val vm = vm()
        runCurrent()
        val error = vm.state.value.screen as PlayerScreenUi.Error
        assertEquals(UiText.Res(R.string.item_gone), error.message)
        assertEquals(listOf(PlayerAction.BACK), error.actions)
        assertEquals(listOf(AppEvent.ItemGone("film", 12)), repos.received)
        assertEquals(emptyList<String>(), posts())
    }

    @Test
    fun `an unsupported answer shows the Spanish reason and offers the other playable versions`() = playerTest {
        film(F.hevc(1), F.file(2, fileName = "Dune 1080p.mkv"))
        codecs.inventory = codecs.inventory.copy(video = codecs.inventory.video.map { it.copy(maxWidth = 3840, maxHeight = 2160) })
        answerWith(unsupported("video_codec_unsupported"), direct(fileId = 2))
        val vm = vm(mediaFileId = 1)
        runCurrent()
        val error = vm.state.value.screen as PlayerScreenUi.Error
        assertEquals(UiText.Res(R.string.player_unsupported), error.message)
        assertEquals(UiText.Res(R.string.reason_video_codec_unsupported, listOf("HEVC")), error.reason)
        assertEquals(listOf(PlayerAction.TRY_OTHER_VERSION, PlayerAction.BACK), error.actions)
        vm.onErrorAction(PlayerAction.TRY_OTHER_VERSION)
        assertEquals(PlayerDialog.Versions(listOf(UiText.Data("Dune 1080p"))), vm.state.value.dialog)
        vm.onVersionChosen(0)
        runCurrent()
        assertEquals("POST /api/media-files/2/playback", posts().last())
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `an unknown unsupported reason shows the generic reason, never the server detail`() = playerTest {
        film(F.file(1))
        answerWith(unsupported("some_future_reason"))
        val vm = vm()
        runCurrent()
        assertEquals(UiText.Res(R.string.reason_unknown), (vm.state.value.screen as PlayerScreenUi.Error).reason)
    }

    @Test
    fun `503 preparing shows the estimate, retries and starts, Back cancels`() = playerTest {
        film(F.file(1))
        answer({ throw httpError(503, "preparing", """{"estimated_seconds": 45}""", retryAfter = "5") }, { hls("S1") })
        val vm = vm()
        runCurrent()
        assertEquals(PlayerScreenUi.Loading(PreparingEstimate.LessThanAMinute), vm.state.value.screen)
        assertTrue(vm.state.value.keepScreenOn)
        advance(5_000)
        assertEquals(2, posts().size)
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `too_many_sessions and hls_disk_full show their own messages with Retry`() = playerTest {
        film(F.file(1))
        answer({ throw httpError(503, "hls_disk_full") }, { throw httpError(503, "too_many_sessions") }, { hls("S1") })
        val vm = vm()
        runCurrent()
        assertEquals(UiText.Res(R.string.player_hls_disk_full), (vm.state.value.screen as PlayerScreenUi.Error).message)
        vm.onErrorAction(PlayerAction.RETRY)
        runCurrent()
        assertEquals(UiText.Res(R.string.player_too_many_sessions), (vm.state.value.screen as PlayerScreenUi.Error).message)
        vm.onErrorAction(PlayerAction.RETRY)
        runCurrent()
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `keyframe_index_failed is terminal for the version`() = playerTest {
        film(F.file(1))
        answerWith()
        answer({ throw httpError(500, "transcoder_failed", """{"detail": "keyframe_index_failed: x"}""") })
        val vm = vm()
        runCurrent()
        val error = vm.state.value.screen as PlayerScreenUi.Error
        assertEquals(UiText.Res(R.string.player_keyframe_index_failed), error.message)
        assertEquals(listOf(PlayerAction.BACK), error.actions)
    }

    @Test
    fun `422 invalid_audio_stream retries once with a null audio index`() = playerTest {
        val vm = startFilm(direct(), F.file(1, audio = listOf(F.audio(1), F.audio(2))))
        answer({ throw httpError(422, "invalid_audio_stream") }, { direct() })
        controller.fail(PlayerFailure(PlaybackErrorCodes.PARSING_CONTAINER_MALFORMED, urlKind = UrlKind.STREAM))
        runCurrent()
        assertEquals(1, bodies()[1].audio_stream_index)
        assertNull(bodies()[2].audio_stream_index)
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `a local audio switch (direct, within caps) sends no request and pins the track`() = playerTest {
        val vm = startFilm(direct(), F.file(1, audio = listOf(F.audio(1), F.audio(2, codec = "ac3", channels = 6))))
        // The manual pick is saved (`PUT /api/track-choices/...`, prd3 FR-T6); no playback request is sent.
        fun playbackCalls() = api.calls.count { !it.startsWith("PUT /api/track-choices/") }
        val before = playbackCalls()
        vm.onOpenTracks()
        val dialog = (vm.state.value.dialog as PlayerDialog.Tracks).tracks
        assertEquals(listOf("1", "2"), dialog.audio.map { it.key })
        assertTrue(dialog.audio.first().selected)
        assertEquals(listOf(PlayerViewModel.OFF_KEY), dialog.subtitles.map { it.key })
        vm.onSelectAudio(2)
        assertEquals(before, playbackCalls())
        assertEquals(2, controller.audio)
        assertNull(vm.state.value.dialog)
    }

    @Test
    fun `audio re-pin - a local switch to track 2 survives a subtitle rebuild and a recovery`() = playerTest {
        val file = F.file(1, audio = listOf(F.audio(1), F.audio(2, codec = "ac3", channels = 6)))
        val vm = startFilm(direct(subs = listOf(F.sub("emb-3"))), file)
        vm.onSelectAudio(2)
        controller.positionMs = 50_000
        vm.onSelectSubtitle("emb-3")
        runCurrent()
        // Rebuild at the same position with the cached VTT; audio and text overrides re-applied after it.
        val rebuild = controller.log.indexOfLast { it.startsWith("setMediaItem") }
        assertEquals("setMediaItem $TEST_SERVER/api/media-files/1/stream @50000", controller.log[rebuild])
        assertEquals(listOf("audio 2", "subtitle emb-3"), controller.log.subList(rebuild + 1, rebuild + 3))
        assertEquals(listOf("emb-3"), controller.item?.subtitles?.map { it.trackId })
        // A recovery request carries the selected index (decoder retry path).
        answerWith(direct(audio = 2, subs = listOf(F.sub("emb-3"))))
        controller.fail(PlayerFailure(PlaybackErrorCodes.PARSING_CONTAINER_MALFORMED, urlKind = UrlKind.STREAM))
        runCurrent()
        assertEquals(2, bodies().last().audio_stream_index)
        assertEquals(2, controller.audio)
        assertEquals("emb-3", controller.subtitle)
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `the track dialog labels tracks with structured labels and marks the converted HLS audio`() = playerTest {
        val file = F.file(1, audio = listOf(F.audio(1, codec = "eac3", channels = 6, default = true)))
        val vm = startFilm(hls("S1", action = "aac", subs = listOf(F.sub("emb-3", language = "spa"), F.sub("emb-4", kind = "image"))), file)
        vm.onOpenTracks()
        val tracks = (vm.state.value.dialog as PlayerDialog.Tracks).tracks
        assertEquals(UiText.Value(TrackLabel(LanguageName.Named("Inglés"), "EAC3 5.1", null, listOf(TrackTag.Default, TrackTag.Converted))), tracks.audio.single().label)
        assertEquals(listOf(PlayerViewModel.OFF_KEY, "emb-3"), tracks.subtitles.map { it.key }) // HLS hides image tracks
    }

    @Test
    fun `a stream 401 renews the session and recovers at the current position (direct)`() = playerTest {
        val vm = startDirect()
        controller.positionMs = 900_000
        clearCalls()
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, httpStatus = 401, urlKind = UrlKind.STREAM))
        runCurrent()
        assertEquals(listOf("POST /api/profiles/5/session"), api.calls)
        assertEquals(listOf("play", "prepare", "seekTo 900000"), controller.log.filter { it == "prepare" || it.startsWith("seekTo") || it == "play" })
        assertEquals(SessionState.Playing, vm.session)
        // A second 401 within 30 s is a generic error (no renewal loop).
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, httpStatus = 401, urlKind = UrlKind.STREAM))
        runCurrent()
        assertEquals(UiText.Res(R.string.player_playback_failed), (vm.state.value.screen as PlayerScreenUi.Error).message)
    }

    @Test
    fun `a stream 401 in HLS renews then sends a recovery request, renewal 404 goes to the Picker only`() = playerTest {
        val vm = startHls()
        controller.positionMs = 900_000
        answerWith(hls("S2"))
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, httpStatus = 401, urlKind = UrlKind.HLS))
        runCurrent()
        assertEquals(900.0, bodies().last().start_position_seconds)
        assertNull(bodies().last().previous_session_id)
        assertEquals(SessionState.Playing, vm.session)

        repos.recordEvents(backgroundScope)
        api.onCreateSession = { throw httpError(404, "profile_not_found") }
        advance(31_000)
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, httpStatus = 401, urlKind = UrlKind.HLS))
        runCurrent()
        assertEquals(listOf(AppEvent.AuthExpired), repos.received)
    }

    @Test
    fun `a stream 401 after a sign-out emits nothing and fails with the generic playback error`() = playerTest {
        val vm = startDirect()
        repos.recordEvents(backgroundScope)
        repos.store.signOut()
        clearCalls()
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, httpStatus = 401, urlKind = UrlKind.STREAM))
        runCurrent()
        assertTrue(api.calls.none { it.startsWith("POST /api/profiles") }, "${api.calls}")
        assertTrue(repos.received.isEmpty())
        val error = vm.state.value.screen as PlayerScreenUi.Error
        assertEquals(UiText.Res(R.string.player_playback_failed), error.message)
        assertEquals(listOf(PlayerAction.BACK), error.actions)
    }

    @Test
    fun `an HLS 404 recovers once per 30 s`() = playerTest {
        val vm = startHls("S1")
        answerWith(hls("S2"))
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, httpStatus = 404, urlKind = UrlKind.HLS, bodyCode = "session_not_found"))
        runCurrent()
        assertEquals(2, posts().size)
        controller.ready()
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, httpStatus = 404, urlKind = UrlKind.HLS))
        runCurrent()
        assertEquals(2, posts().size)
        assertEquals(SessionState.Error, vm.session)
    }

    @Test
    fun `a direct stream 404 shows the file-missing screen`() = playerTest {
        val vm = startDirect()
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, httpStatus = 404, urlKind = UrlKind.STREAM))
        runCurrent()
        assertEquals(UiText.Res(R.string.player_file_missing), (vm.state.value.screen as PlayerScreenUi.Error).message)
        assertEquals(listOf("PUT /api/progress"), puts().takeLast(1))
    }

    @Test
    fun `HLS transcoder failure offers Retry from here, which recovers at that position`() = playerTest {
        val vm = startHls()
        controller.positionMs = 400_000
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, httpStatus = 500, urlKind = UrlKind.HLS, bodyCode = "transcoder_failed"))
        runCurrent()
        val error = vm.state.value.screen as PlayerScreenUi.Error
        assertEquals(UiText.Res(R.string.player_hls_failed), error.message)
        assertEquals(listOf(PlayerAction.RETRY_FROM_HERE, PlayerAction.BACK), error.actions)
        assertEquals(listOf("DELETE /api/hls/S1"), deletes())
        answerWith(hls("S2"))
        vm.onErrorAction(PlayerAction.RETRY_FROM_HERE)
        runCurrent()
        assertEquals(400.0, bodies().last().start_position_seconds)
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `network loss shows the connection error with Retry from here`() = playerTest {
        val vm = startDirect()
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_NETWORK_CONNECTION_TIMEOUT))
        runCurrent()
        val error = vm.state.value.screen as PlayerScreenUi.Error
        assertEquals(UiText.Res(R.string.error_connection, listOf("hometv.tail1234.ts.net")), error.message)
        assertEquals(listOf(PlayerAction.RETRY_FROM_HERE, PlayerAction.BACK), error.actions)
    }

    @Test
    fun `an audio decoder failure retries once without that codec, a video failure without the video codec`() = playerTest {
        val vm = startFilm(direct(), F.file(1, audio = listOf(F.audio(1, codec = "ac3", channels = 6))))
        answerWith(hls("S1", action = "aac"))
        controller.fail(PlayerFailure(PlaybackErrorCodes.DECODER_INIT_FAILED, RendererType.AUDIO, "audio/ac3"))
        runCurrent()
        assertFalse("ac3" in bodies().last().client_caps.audio_codecs)
        assertTrue("h264" in bodies().last().client_caps.video_codecs)
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `a silent video failure retries once without the video codec and shows the unsupported screen`() = playerTest {
        film(F.file(1))
        answerWith(direct(), unsupported("video_codec_unsupported"))
        val vm = vm()
        runCurrent()
        controller.tracks(TrackCheckInput(true, true, videoSupported = false, videoSelected = false, audioSupported = true, audioSelected = true))
        runCurrent()
        assertFalse("h264" in bodies().last().client_caps.video_codecs)
        assertEquals(UiText.Res(R.string.player_unsupported), (vm.state.value.screen as PlayerScreenUi.Error).message)
        assertEquals(UiText.Res(R.string.reason_video_codec_unsupported, listOf("H264")), (vm.state.value.screen as PlayerScreenUi.Error).reason)
    }

    @Test
    fun `a second decoder failure of the same type for the same file is the generic error`() = playerTest {
        val vm = startFilm(direct(), F.file(1, audio = listOf(F.audio(1, codec = "ac3", channels = 6))))
        answerWith(direct(audio = 1))
        controller.fail(PlayerFailure(PlaybackErrorCodes.AUDIO_TRACK_INIT_FAILED))
        runCurrent()
        controller.ready()
        controller.fail(PlayerFailure(PlaybackErrorCodes.AUDIO_TRACK_INIT_FAILED))
        runCurrent()
        assertEquals(2, posts().size)
        assertEquals(UiText.Res(R.string.player_playback_failed), (vm.state.value.screen as PlayerScreenUi.Error).message)
    }

    @Test
    fun `a silent audio failure (expected audio not selected) retries with the audio codec excluded`() = playerTest {
        film(F.file(1, audio = listOf(F.audio(1, codec = "ac3", channels = 6))))
        answerWith(direct(), hls("S1", action = "aac"))
        val vm = vm()
        runCurrent()
        controller.tracks(TrackCheckInput(true, true, true, true, audioSupported = false, audioSelected = false))
        runCurrent()
        assertFalse("ac3" in bodies().last().client_caps.audio_codecs)
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `keepScreenOn is true while playing, preparing or buffering and false while paused`() = playerTest {
        val vm = startDirect()
        assertTrue(vm.state.value.keepScreenOn)
        vm.onPlayPause()
        assertFalse(vm.state.value.keepScreenOn)
        vm.onPlayPause()
        assertTrue(vm.state.value.keepScreenOn)
    }

    @Test
    fun `a pending subtitle shows the chip and attaches through a rebuild at the same position when ready`() = playerTest {
        var ready = false
        onSubtitle = { if (ready) SubtitleFetch.Ready("WEBVTT\n".toByteArray()) else SubtitleFetch.Pending(10) }
        val vm = startFilm(direct(subs = listOf(F.sub("emb-3"))))
        vm.onSelectSubtitle("emb-3")
        runCurrent()
        assertEquals(UiText.Res(R.string.player_subtitles_preparing), vm.state.value.chip)
        vm.onOpenTracks()
        assertTrue((vm.state.value.dialog as PlayerDialog.Tracks).tracks.subtitles.first { it.key == "emb-3" }.let { it.pending && it.selected })
        vm.onDialogDismiss()
        ready = true
        controller.positionMs = 61_000
        advance(10_000)
        assertNull(vm.state.value.chip)
        assertEquals("setMediaItem $TEST_SERVER/api/media-files/1/stream @61000", controller.log.last { it.startsWith("setMediaItem") })
        assertEquals("emb-3", controller.subtitle)
        assertEquals(1, posts().size)
    }

    @Test
    fun `an unavailable subtitle reverts to Off with a toast and shows no disponible`() = playerTest {
        onSubtitle = { SubtitleFetch.Unavailable("subtitle_extraction_failed") }
        val vm = startFilm(direct(subs = listOf(F.sub("emb-3"))))
        vm.onSelectSubtitle("emb-3")
        runCurrent()
        assertEquals(UiText.Res(R.string.player_subtitles_unavailable), vm.state.value.toast)
        assertNull(controller.subtitle)
        vm.onOpenTracks()
        val entry = (vm.state.value.dialog as PlayerDialog.Tracks).tracks.subtitles.first { it.key == "emb-3" }
        assertTrue((entry.label as UiText.Value).value.let { it is TrackLabel && TrackTag.Unavailable in it.tags })
        assertTrue((vm.state.value.dialog as PlayerDialog.Tracks).tracks.subtitles.first().selected)
    }

    @Test
    fun `the default text subtitle is fetched before prepare and included in the first media item`() = playerTest {
        startFilm(direct(subs = listOf(F.sub("emb-3", default = true))))
        assertEquals(listOf("emb-3"), controller.item?.subtitles?.map { it.trackId })
        assertEquals("emb-3", controller.subtitle)
        assertEquals(1, controller.log.count { it.startsWith("setMediaItem") })
    }

    @Test
    fun `a malformed side-loaded VTT turns subtitles off and rebuilds without any playback request`() = playerTest {
        val vm = startFilm(direct(subs = listOf(F.sub("emb-3", default = true))))
        controller.positionMs = 30_000
        controller.fail(PlayerFailure(PlaybackErrorCodes.PARSING_CONTAINER_MALFORMED, urlKind = UrlKind.SUBTITLE))
        runCurrent()
        assertEquals(1, posts().size)
        assertEquals(UiText.Res(R.string.player_subtitles_unavailable), vm.state.value.toast)
        assertEquals(emptyList<String>(), controller.item?.subtitles?.map { it.trackId })
        assertEquals("setMediaItem $TEST_SERVER/api/media-files/1/stream @30000", controller.log.last { it.startsWith("setMediaItem") })
        assertNull(controller.subtitle)
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `seeking - a short press commits one 10 s seek after 600 ms, holding accelerates and commits once on release`() = playerTest {
        val vm = startDirect()
        controller.positionMs = 100_000
        vm.onSeekKey(forward = true, repeatCount = 0)
        assertEquals(110_000, vm.state.value.pendingSeekMs)
        assertEquals(OverlayMode.PROGRESS, vm.state.value.overlay)
        advance(599)
        assertFalse(controller.log.any { it.startsWith("seekTo") })
        advance(1)
        assertEquals(listOf("seekTo 110000"), controller.log.filter { it.startsWith("seekTo") })
        clearCalls()
        (0 until 8).forEach { vm.onSeekKey(forward = true, repeatCount = it) }
        vm.onSeekKeyUp()
        assertEquals(listOf("seekTo ${110_000 + 5 * 10_000 + 3 * 30_000}"), controller.log.filter { it.startsWith("seekTo") })
        advance(2_000)
        assertEquals(listOf("PUT /api/progress"), puts()) // debounced progress after the committed seek
    }

    @Test
    fun `progress is reported every 10 s while playing`() = playerTest {
        startDirect()
        clearCalls()
        advance(30_000)
        assertEquals(3, puts().size)
    }

    @Test
    fun `an unknown duration - no progress until the player knows it, then it is used`() = playerTest {
        val vm = startFilm(direct(duration = null))
        clearCalls()
        advance(30_000)
        assertEquals(emptyList<String>(), puts())
        assertNull(vm.state.value.durationMs)
        controller.durationMs = 5_000_000
        controller.listener?.onDurationChanged(5_000_000)
        advance(10_000)
        assertEquals(5000.0, api.progressBodies.single().duration_seconds)
        assertEquals(5_000_000, vm.state.value.durationMs)
    }

    @Test
    fun `a progress 401 whose renewal fails with an IOException stays in the player and the next report renews`() = playerTest {
        repos.recordEvents(backgroundScope)
        val vm = startDirect()
        var failRenewal = true
        var reject = true
        api.onProgress = {
            if (reject) throw httpError(401, "unauthorized")
            com.hometv.tv.core.api.Progress()
        }
        api.onCreateSession = { id -> if (failRenewal) throw IOException("down") else com.hometv.tv.core.data.FakeHomeTvApi.session("new", id).also { reject = false } }
        advance(10_000)
        assertTrue(repos.received.none { it is AppEvent.ServerUnreachable })
        assertEquals(SessionState.Playing, vm.session)
        failRenewal = false
        clearCalls()
        advance(10_000)
        assertEquals(listOf("PUT /api/progress", "POST /api/profiles/5/session", "PUT /api/progress"), api.calls)
    }

    @Test
    fun `a keep-alive 404 sends the next play straight to the recovery path`() = playerTest {
        val vm = startHls("S1")
        api.onKeepAlive = { com.hometv.tv.core.data.FakeHomeTvApi.empty(404) }
        controller.positionMs = 200_000
        vm.onPlayPause()
        advance(30_000)
        answerWith(hls("S2"))
        clearCalls()
        vm.onPlayPause()
        runCurrent()
        assertEquals(listOf("POST /api/media-files/1/playback"), posts())
        assertEquals(200.0, bodies().single().start_position_seconds)
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `the different-version chip shows for 5 s after READY when the position does not carry`() = playerTest {
        // Progress on a 2160p HEVC version this TV cannot play; the only playable version is another cut.
        codecs.inventory = codecs.inventory.copy(video = codecs.inventory.video.filter { it.mime == "video/avc" })
        film(F.hevc(1, duration = 9360.0), F.file(2, duration = 8100.0), progress = F.progress(1, position = 3723.0, duration = 9360.0))
        answerWith(direct(fileId = 2))
        val vm = vm()
        runCurrent()
        assertEquals(0.0, bodies().single().start_position_seconds)
        assertNull(vm.state.value.chip)
        controller.ready()
        assertEquals(UiText.Res(R.string.player_different_version), vm.state.value.chip)
        advance(5_000)
        assertNull(vm.state.value.chip)
    }

    @Test
    fun `the overlay hides 5 s after input while playing and stays while paused`() = playerTest {
        val vm = startDirect()
        vm.onShowOverlay()
        assertEquals(OverlayMode.FULL, vm.state.value.overlay)
        advance(5_000)
        assertEquals(OverlayMode.HIDDEN, vm.state.value.overlay)
        vm.onPlayPause()
        advance(60_000)
        assertEquals(OverlayMode.FULL, vm.state.value.overlay)
    }

    @Test
    fun `a multi-part film title carries the part, a standalone version never does`() = playerTest {
        val vm = startFilm(direct(), F.part(1, 1), F.part(2, 2))
        assertEquals(UiText.Res(R.string.player_title_part, listOf("Dune", "1", "2")), vm.state.value.title)
        assertEquals(NextKind.PART, vm.state.value.nextButton)

        val standalone = startFilm(direct(), F.file(3), F.part(1, 1), F.part(2, 2))
        assertEquals(UiText.Data("Dune"), standalone.state.value.title)
        assertNull(standalone.state.value.nextButton)
        controller.end()
        runCurrent()
        assertEquals(SessionState.Exited, standalone.session) // ending a standalone version exits, no prompt
    }

    @Test
    fun `the next part prefers a playable file before the same label`() = playerTest {
        codecs.inventory = codecs.inventory.copy(video = codecs.inventory.video.filter { it.mime == "video/avc" })
        val p1 = F.part(1, 1, label = "Remux")
        val p2hevc = F.part(2, 2, video = VideoInfo("hevc", 1920, 1080), label = "Remux")
        val p2web = F.part(3, 2, label = "Web")
        val vm = startFilm(direct(), p1, p2hevc, p2web)
        answerWith(direct(fileId = 3))
        controller.end()
        runCurrent()
        vm.onPlayNow()
        runCurrent()
        assertEquals("POST /api/media-files/3/playback", posts().last())
        assertEquals(0.0, bodies().last().start_position_seconds)
    }

    @Test
    fun `the Next episode button reports progress and hands the session over`() = playerTest {
        episodes(episodeDetail(203, listOf(F.file(1)), next = 204), episodeDetail(204, listOf(F.file(2))))
        answerWith(hls("S1"))
        val vm = vm(itemType = "episode", itemId = 203)
        runCurrent()
        controller.ready()
        runCurrent()
        clearCalls()
        answerWith(hls("S2"))
        vm.onNext()
        runCurrent()
        assertEquals(listOf("GET /api/episodes/204", "PUT /api/progress", "POST /api/media-files/2/playback"), api.calls.filter { !it.contains("203") })
        assertEquals("S1", bodies().single().previous_session_id)
        assertEquals(emptyList<String>(), deletes())
    }

    @Test
    fun `the Next episode button on the last episode shows the toast and hides itself`() = playerTest {
        episodes(episodeDetail(203, listOf(F.file(1)), next = 204), episodeDetail(204, listOf(F.file(1)), next = null))
        answerWith(direct())
        val vm = vm(itemType = "episode", itemId = 203)
        runCurrent()
        controller.ready()
        vm.onNext()
        runCurrent()
        assertEquals(UiText.Res(R.string.player_last_episode), vm.state.value.toast)
        assertNull(vm.state.value.nextButton)
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `progress reports carry the file that is playing`() = playerTest {
        startFilm(direct(fileId = 2), F.part(1, 1), F.part(2, 2), progress = Progress(position_seconds = 2990.0, duration_seconds = 3000.0, media_file_id = 1))
        assertEquals(0.0, bodies().single().start_position_seconds) // finished part 1 → part 2 from 0
        clearCalls()
        advance(10_000)
        assertEquals(2L, api.progressBodies.single().media_file_id)
    }
}
