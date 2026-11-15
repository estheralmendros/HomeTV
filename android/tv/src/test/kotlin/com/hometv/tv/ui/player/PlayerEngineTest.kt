package com.hometv.tv.ui.player

import android.content.ContextWrapper
import android.widget.FrameLayout
import com.hometv.tv.core.api.PlaybackRequest
import com.hometv.tv.core.api.RatingPrompt
import com.hometv.tv.core.api.VideoInfo
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.Mimes
import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.core.playback.PlaybackEngine.EXO
import com.hometv.tv.core.playback.PlaybackEngine.VLC
import com.hometv.tv.core.playback.PlaybackErrorCodes
import com.hometv.tv.core.playback.PlayerAction
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.PlayerState
import com.hometv.tv.core.playback.RendererType
import com.hometv.tv.core.playback.UrlKind
import com.hometv.tv.core.playback.VideoDecoder
import com.hometv.tv.core.playback.VlcCapsBuilder
import com.hometv.tv.R
import com.hometv.tv.data.PendingPrompt
import com.hometv.tv.playback.FakeVlcPort
import com.hometv.tv.playback.LibVlcProvider
import com.hometv.tv.playback.VlcEvent
import com.hometv.tv.playback.VlcPlayerController
import com.hometv.tv.playback.VlcPlayerPort
import com.hometv.tv.playback.VlcUnavailableException
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Per-engine controllers in the Player (prd.md FR-2.3 and FR-3): two [com.hometv.tv.testing.FakePlayerController]s. */
class PlayerEngineTest : PlayerTestBase() {
    private val mpeg4 = VideoInfo("mpeg4", 720, 400)
    private val xvid = F.file(1, video = mpeg4, container = "avi", fileName = "film.avi")
    /** H.264 in Blu-ray M2TS: stays on EXO with an HLS remux (FR-1.4 sends AVI to VLC up front), so it exercises decision 14. */
    private val h264M2ts = F.file(1, container = "ts", fileName = "film.m2ts")
    private val vlcFailure = PlayerFailure(PlaybackErrorCodes.VLC_ERROR, engine = VLC)
    private val dune = RatingPrompt("film", 12, "Dune")

    private fun PlaybackRequest.isVlc() = client_caps == VlcCapsBuilder.build()

    private fun renewals() = api.calls.filter { it == "POST /api/profiles/5/session" }

    private fun error(vm: PlayerViewModel) = vm.state.value.screen as PlayerScreenUi.Error

    @Test
    fun `AC-3_2 a mpeg4 AVI plays on the VLC controller with the VLC caps`() = playerTest {
        vlcAvailable = true
        film(xvid)
        answerWith(direct())
        val vm = vm()
        runCurrent()
        val body = bodies().single()
        assertFalse(body.client_caps.hls)
        assertTrue("avi" in body.client_caps.containers)
        val url = vlcController.item!!.url
        assertTrue(url.endsWith("/api/media-files/1/stream"), url)
        assertFalse("token" in url)
        assertTrue("play" in vlcController.log)
        assertEquals(VLC, vm.state.value.engine)
        assertTrue(controller.log.none { it.startsWith("setMediaItem") || it == "play" }, "${controller.log}")
        assertEquals(listOf(VLC), createdEngines)
    }

    @Test
    fun `AC-1_20 a mpeg4 AVI on a TV with a hardware MPEG-4 decoder plays directly on VLC, no HLS request`() = playerTest {
        // Production 2026-11-08 ("Annie (1982).avi": mpeg4 + 2× ac3): it used to go to EXO and an HLS remux that failed.
        vlcAvailable = true
        codecs.inventory = codecs.inventory.copy(video = codecs.inventory.video + VideoDecoder(Mimes.VIDEO_MP4V, true, 1920, 1080))
        film(F.file(1, video = mpeg4, container = "avi", fileName = "Annie (1982).avi", audio = listOf(F.audio(1, codec = "ac3", channels = 2), F.audio(2, codec = "ac3", channels = 2))))
        answerWith(direct())
        val vm = vm()
        runCurrent()
        val body = bodies().single()
        assertEquals(VlcCapsBuilder.build(), body.client_caps)
        assertFalse(body.client_caps.hls)
        assertEquals(VLC, vm.state.value.engine)
        assertTrue(vlcController.item!!.url.endsWith("/api/media-files/1/stream"))
        assertEquals(listOf(VLC), createdEngines)
    }

    @Test
    fun `AC-3_3 without libVLC the same AVI sends today's request and shows today's error`() = playerTest {
        film(xvid)
        answerWith(unsupported("video_codec_unsupported"))
        val vm = vm()
        runCurrent()
        val body = bodies().single()
        assertTrue(body.client_caps.hls)
        assertFalse("avi" in body.client_caps.containers)
        assertEquals(PlayerScreenUi.Error::class, vm.state.value.screen::class)
        assertEquals(SessionState.Error, vm.session)
        assertTrue(createdEngines.isEmpty())
    }

    @Test
    fun `AC-3_4 an EXO video decoder failure continues on VLC at the same position, once`() = playerTest {
        vlcAvailable = true
        val vm = startDirect()
        assertEquals(EXO, vm.state.value.engine)
        controller.positionMs = 600_000
        answerWith(direct())
        controller.fail(PlayerFailure(PlaybackErrorCodes.DECODING_FAILED, RendererType.VIDEO, "video/avc"))
        runCurrent()
        val retry = bodies().last()
        assertEquals(2, bodies().size)
        assertEquals(600.0, retry.start_position_seconds)
        assertTrue(retry.isVlc())
        assertNull(retry.previous_session_id)
        assertTrue(vlcController.log.any { it.startsWith("setMediaItem") && it.endsWith("@600000") }, "${vlcController.log}")
        assertTrue(controller.stopped)
        assertEquals(VLC, vm.state.value.engine)
        assertEquals(SessionState.Playing, vm.session)
        // A late callback of the stopped ExoPlayer is filtered out.
        controller.listener?.onFailure(PlayerFailure(PlaybackErrorCodes.DECODING_FAILED, RendererType.VIDEO, "video/avc"))
        runCurrent()
        assertEquals(SessionState.Playing, vm.session)
        assertEquals(2, bodies().size)
    }

    @Test
    fun `AC-3_5 a VLC failure renews and recovers once, then the error screen, and a retry renews again`() = playerTest {
        vlcAvailable = true
        film(xvid)
        answerWith(direct())
        val vm = vm()
        runCurrent()
        vlcController.ready()
        vlcController.positionMs = 900_000
        clearCalls()
        vlcController.clearLog()
        vlcController.fail(vlcFailure)
        runCurrent()
        assertEquals(listOf("POST /api/profiles/5/session"), api.calls)
        assertEquals(listOf("play", "prepare", "seekTo 900000"), vlcController.log.filter { it == "prepare" || it.startsWith("seekTo") || it == "play" })
        assertEquals(SessionState.Playing, vm.session)

        vlcController.fail(vlcFailure)
        runCurrent()
        assertEquals(listOf(PlayerAction.RETRY_FROM_HERE, PlayerAction.BACK), error(vm).actions)
        assertEquals(1, renewals().size)

        vm.onErrorAction(PlayerAction.RETRY_FROM_HERE)
        runCurrent()
        assertEquals(1, posts().size, "a new request")
        vlcController.ready()
        vlcController.fail(vlcFailure)
        runCurrent()
        assertEquals(2, renewals().size)
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `AC-3_6 and AC-3_13 the next episodes switch engines, a stopped ExoPlayer's callbacks never reach the session`() = playerTest {
        vlcAvailable = true
        episodes(
            episodeDetail(101, listOf(F.file(1, duration = 1320.0)), next = 102),
            episodeDetail(102, listOf(F.file(2, video = mpeg4, container = "avi", fileName = "e2.avi", duration = 1320.0)), next = 103),
            episodeDetail(103, listOf(F.file(3, duration = 1320.0)), next = null),
        )
        answerWith(direct(duration = 1320.0))
        val vm = vm(itemType = "episode", itemId = 101)
        runCurrent()
        controller.ready()
        // The real ExoPlayer reports from inside stop() and again later on its looper.
        var activeDuringStop: PlayerController? = null
        val late = mutableListOf<() -> Unit>()
        controller.onStop = {
            activeDuringStop = vm.playerController
            val l = controller.listener
            l?.onIsPlayingChanged(false)
            l?.onStateChanged(PlayerState.IDLE)
            late += { l?.onIsPlayingChanged(false) }
            late += { l?.onStateChanged(PlayerState.IDLE) }
        }
        controller.end()
        runCurrent()
        assertEquals(SessionState.Countdown, vm.session, "the VLC-only episode is offered, not 'no se puede reproducir'")
        answerWith(direct(fileId = 2, duration = 1320.0))
        vm.onPlayNow()
        runCurrent()
        assertTrue(bodies().last().isVlc())
        assertTrue(vlcController.item!!.url.endsWith("/api/media-files/2/stream"))
        assertEquals(VLC, vm.state.value.engine)
        assertTrue(controller.stopped)
        assertSame(vlcController, activeDuringStop, "the active engine switches before the old one stops")
        vlcController.ready()
        assertTrue(vlcController.isPlaying)
        late.forEach { it() }
        runCurrent()
        assertTrue(vm.state.value.playing)
        clearCalls()
        advance(10_000)
        assertTrue(puts().isNotEmpty(), "the VLC attachment's reporter still runs")

        vlcController.end()
        runCurrent()
        assertEquals(SessionState.Countdown, vm.session)
        answerWith(direct(fileId = 3, duration = 1320.0))
        vm.onPlayNow()
        runCurrent()
        assertFalse(bodies().last().isVlc())
        assertTrue(controller.item!!.url.endsWith("/api/media-files/3/stream"))
        assertTrue(vlcController.stopped)
        assertEquals(EXO, vm.state.value.engine)
        assertEquals(listOf(EXO, VLC), createdEngines)
    }

    @Test
    fun `AC-3_7 other versions list Exo-playable versions first, a VLC-only one attaches to VLC`() = playerTest {
        vlcAvailable = true
        val main = F.file(1, label = "1080p H264", fileName = "Dune.mkv")
        val small = F.file(3, video = F.H264.copy(height = 720), label = "720p H264", fileName = " ")
        val avi = F.file(2, video = mpeg4.copy(height = 1080), container = "avi", label = "1080p MPEG4", fileName = "Dune.Xvid.avi")
        val vm = startFilm(direct(), main, avi, small)
        // An error screen that keeps [Probar otra versión] with libVLC available (a generic one now tries VLC first).
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, httpStatus = 404, urlKind = UrlKind.STREAM))
        runCurrent()
        vm.onErrorAction(PlayerAction.TRY_OTHER_VERSION)
        assertEquals(PlayerDialog.Versions(listOf(UiText.Res(R.string.version_label_unknown), UiText.Data("Dune.Xvid"))), vm.state.value.dialog)
        answerWith(direct(fileId = 2))
        vm.onVersionChosen(1)
        runCurrent()
        assertTrue(bodies().last().isVlc())
        assertTrue(vlcController.item!!.url.endsWith("/api/media-files/2/stream"))
        assertEquals(VLC, vm.state.value.engine)
    }

    @Test
    fun `AC-3_8 embedded text subtitles are native on VLC, external ones are side-loaded`() = playerTest {
        vlcAvailable = true
        film(xvid)
        val subs = listOf(F.sub("s1"), F.sub("s2", kind = "image"), F.sub("s3", source = "external", language = "spa"))
        answerWith(direct(subs = subs))
        val vm = vm()
        runCurrent()
        vlcController.ready()
        runCurrent()
        assertEquals(listOf("s1", "s2"), vlcController.item!!.embeddedSubtitleIds)
        assertTrue(vlcController.item!!.subtitles.isEmpty())
        vlcController.clearLog()
        vm.onSelectSubtitle("s1")
        runCurrent()
        assertEquals("s1", vlcController.subtitle)
        assertTrue(subtitleUrls.isEmpty())
        assertTrue(vlcController.log.none { it.startsWith("setMediaItem") }, "${vlcController.log}")
        assertNull(vm.state.value.chip)

        vm.onSelectSubtitle("s3")
        runCurrent()
        assertEquals(1, subtitleUrls.size)
        assertEquals(listOf("s3"), vlcController.item!!.subtitles.map { it.trackId })
        assertEquals("s3", vlcController.subtitle)
    }

    @Test
    fun `a native default subtitle on VLC is never fetched`() = playerTest {
        vlcAvailable = true
        film(xvid)
        answerWith(direct(subs = listOf(F.sub("s1", default = true))))
        vm()
        runCurrent()
        assertTrue(subtitleUrls.isEmpty())
        assertEquals("s1", vlcController.subtitle)
    }

    @Test
    fun `AC-3_9 an audio switch on VLC is local`() = playerTest {
        vlcAvailable = true
        film(F.file(1, video = mpeg4, container = "avi", audio = listOf(F.audio(1), F.audio(2, codec = "dts", channels = 6, language = "spa"))))
        answerWith(direct())
        val vm = vm()
        runCurrent()
        vlcController.ready()
        clearCalls()
        vm.onSelectAudio(2)
        runCurrent()
        assertEquals(2, vlcController.audio)
        assertTrue(posts().isEmpty())
    }

    @Test
    fun `AC-3_10 progress, the end report and the rating prompt on VLC`() = playerTest {
        vlcAvailable = true
        api.onProgressPrompt = { body -> if (body.position_seconds == 7200.0) dune else null }
        film(xvid)
        answerWith(direct())
        vm()
        runCurrent()
        vlcController.ready()
        vlcController.positionMs = 1_234_000
        advance(10_000)
        assertEquals(1234.0, api.progressBodies.last().position_seconds)
        vlcController.end()
        runCurrent()
        assertEquals(7200.0, api.progressBodies.last().position_seconds)
        assertEquals(listOf(PlayerEffect.Exit), exits)
        assertEquals(PendingPrompt(5, dune), repos.ratingPrompts.pending.value)
    }

    @Test
    fun `AC-3_11 controllers are created lazily, only for the engines used, and released`() = playerTest {
        vlcAvailable = true
        val h264 = startDirect()
        h264.onStop()
        h264.onStart()
        runCurrent()
        clear(h264)
        assertEquals(listOf(EXO), createdEngines)
        assertTrue(controller.released)
        assertFalse(vlcController.released)
    }

    @Test
    fun `AC-3_11 a VLC-only film never builds the ExoPlayer, across stop, start and clear`() = playerTest {
        vlcAvailable = true
        film(xvid)
        answerWith(direct())
        val vm = vm()
        runCurrent()
        vlcController.ready()
        vm.onStop()
        vm.onStart()
        runCurrent()
        clear(vm)
        assertEquals(listOf(VLC), createdEngines)
        assertTrue(vlcController.released)
        assertTrue(controller.log.isEmpty())
    }

    @Test
    fun `AC-3_11 a screen that exits before its first attach creates no controller`() = playerTest {
        vlcAvailable = true
        film(F.file(1))
        answerWith(unsupported("probe_failed"))
        val vm = vm()
        runCurrent()
        vm.onErrorAction(PlayerAction.BACK)
        runCurrent()
        clear(vm)
        assertTrue(createdEngines.isEmpty())

        val gate = CompletableDeferred<Unit>()
        answer({ gate.await(); direct() })
        val loading = vm()
        runCurrent()
        loading.onBack()
        runCurrent()
        clear(loading)
        assertTrue(createdEngines.isEmpty())
    }

    @Test
    fun `AC-3_12 the debug switch moves an H264 MKV to VLC only when libVLC is available`() = playerTest {
        forceVlc = true
        film(F.file(1))
        answerWith(direct())
        val off = vm()
        runCurrent()
        assertTrue(bodies().last().client_caps.hls)
        assertEquals(EXO, off.state.value.engine)
        clear(off)

        vlcAvailable = true
        val on = vm()
        runCurrent()
        assertTrue(bodies().last().isVlc())
        assertEquals(VLC, on.state.value.engine)
    }

    @Test
    fun `AC-3_14 an early end on VLC is a failure - renew and recover, no end report, no prompt`() = playerTest {
        vlcAvailable = true
        api.onProgressPrompt = { dune }
        film(F.file(1, video = mpeg4, container = "avi", duration = 5400.0))
        answerWith(direct(duration = 5400.0))
        val vm = vm()
        runCurrent()
        vlcController.ready()
        vlcController.positionMs = 1_200_000
        clearCalls()
        vlcController.clearLog()
        vlcController.fail(vlcFailure)
        runCurrent()
        assertEquals(1, renewals().size)
        assertTrue("seekTo 1200000" in vlcController.log)
        assertTrue(api.progressBodies.none { it.position_seconds == 5400.0 })
        assertEquals(SessionState.Playing, vm.session)
        assertNull(repos.ratingPrompts.pending.value)
        assertTrue(exits.isEmpty())
    }

    @Test
    fun `AC-3_15 state engine is null while loading, then follows the controller creation and the switch`() = playerTest {
        vlcAvailable = true
        film(F.file(1))
        val gate = CompletableDeferred<Unit>()
        answer({ gate.await(); direct() }, { direct() })
        val vm = vm()
        val engines = mutableListOf<PlaybackEngine?>()
        val createdWhenPublished = mutableListOf<List<PlaybackEngine>>()
        vm.state.onEach { s ->
            if (engines.lastOrNull() != s.engine || engines.isEmpty()) engines += s.engine
            if (s.engine != null) createdWhenPublished += createdEngines.toList()
        }.launchIn(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        assertNull(vm.state.value.engine)
        assertTrue(createdEngines.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(EXO, vm.state.value.engine)
        assertTrue(createdWhenPublished.all { EXO in it })
        controller.ready()
        controller.fail(PlayerFailure(PlaybackErrorCodes.DECODING_FAILED, RendererType.VIDEO, "video/avc"))
        runCurrent()
        assertEquals(VLC, vm.state.value.engine)
        assertEquals(listOf(null, EXO, VLC), engines, "never null again once created")
    }

    // ---- Decision 14: a remux the server can't make -----------------------------------------

    @Test
    fun `AC-2_10 an unsupported remux is retried once on VLC at the same position`() = playerTest {
        vlcAvailable = true
        film(h264M2ts, progress = F.progress(1, position = 1000.0))
        answerWith(unsupported("keyframes_too_sparse"), direct())
        val vm = vm()
        runCurrent()
        assertEquals(2, bodies().size)
        assertFalse(bodies()[0].isVlc())
        assertEquals(1000.0, bodies()[0].start_position_seconds)
        assertTrue(bodies()[1].isVlc())
        assertEquals(1000.0, bodies()[1].start_position_seconds)
        assertNull(bodies()[1].previous_session_id)
        assertTrue(vlcController.item!!.url.endsWith("/api/media-files/1/stream"))
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `AC-2_10 a VLC request that is also unsupported shows today's error, no third request`() = playerTest {
        vlcAvailable = true
        film(h264M2ts)
        answerWith(unsupported("duration_unknown"), unsupported("hls_not_supported"))
        val vm = vm()
        runCurrent()
        assertEquals(2, bodies().size)
        assertEquals(SessionState.Error, vm.session)
        assertTrue(createdEngines.isEmpty())
    }

    @Test
    fun `AC-2_11 without libVLC an unsupported remux shows today's error after one request`() = playerTest {
        film(h264M2ts)
        answerWith(unsupported("keyframes_too_sparse"))
        val vm = vm()
        runCurrent()
        assertEquals(1, bodies().size)
        assertEquals(SessionState.Error, vm.session)
    }

    @Test
    fun `AC-2_9 a keyframe index failure is retried once on VLC`() = playerTest {
        vlcAvailable = true
        film(h264M2ts)
        answer({ throw httpError(500, "transcoder_failed", """{"detail": "keyframe_index_failed: x"}""") }, { direct() })
        val vm = vm()
        runCurrent()
        assertEquals(2, bodies().size)
        assertTrue(bodies()[1].isVlc())
        assertEquals(VLC, vm.state.value.engine)
    }

    @Test
    fun `AC-2_12 an HLS 500 while playing an H264 M2TS on EXO continues on VLC at the recovery position`() = playerTest {
        vlcAvailable = true
        val vm = startFilm(hls(), h264M2ts)
        assertFalse(bodies().single().isVlc())
        controller.positionMs = 600_000
        answerWith(direct())
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, httpStatus = 500, urlKind = UrlKind.HLS))
        runCurrent()
        assertTrue(bodies().last().isVlc())
        assertEquals(600.0, bodies().last().start_position_seconds)
        assertEquals(VLC, vm.state.value.engine)
        assertTrue(deletes().isNotEmpty(), "the failed HLS session is finished")
    }

    @Test
    fun `AC-2_17 the server conversion dies before READY and the failure has no HLS load record - VLC, no error screen`() = playerTest {
        // Production 2026-11-08 ("Annie (1982).avi"): every [Reintentar desde aquí] created another HLS session on EXO.
        vlcAvailable = true
        film(h264M2ts, progress = F.progress(1, position = 1200.0))
        answerWith(hls())
        val vm = vm()
        runCurrent()
        assertFalse(bodies().single().isVlc())
        answerWith(direct())
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, httpStatus = 500, urlKind = UrlKind.OTHER))
        runCurrent()
        assertEquals(2, bodies().size)
        assertTrue(bodies().last().isVlc())
        assertEquals(1200.0, bodies().last().start_position_seconds, "the start position: the failure came before READY")
        assertEquals(VLC, vm.state.value.engine)
        assertFalse(vm.state.value.screen is PlayerScreenUi.Error)
    }

    @Test
    fun `AC-2_17 a server 5xx answer to the EXO request retries once on VLC`() = playerTest {
        vlcAvailable = true
        film(h264M2ts)
        answer({ throw httpError(500, "transcoder_failed", """{"detail": "ffmpeg died"}""") })
        val vm = vm()
        runCurrent()
        assertEquals(2, bodies().size)
        assertFalse(bodies()[0].isVlc())
        assertTrue(bodies()[1].isVlc())
        assertTrue(vm.state.value.screen is PlayerScreenUi.Error, "the VLC request failed the same way: no third request")
    }

    @Test
    fun `an audio exclusion retry stays on EXO`() = playerTest {
        vlcAvailable = true
        val vm = startFilm(direct(), F.file(1, audio = listOf(F.audio(1, codec = "dts", channels = 6))))
        answerWith(direct())
        controller.fail(PlayerFailure(PlaybackErrorCodes.DECODING_FAILED, RendererType.AUDIO, "audio/vnd.dts"))
        runCurrent()
        assertFalse(bodies().last().isVlc())
        assertEquals(EXO, vm.state.value.engine)
    }

    // ---- prd2: a real VlcPlayerController whose LibVLC can't be created --------------------------------

    /** The VLC controller of this test when set (a real [VlcPlayerController]); else the base's fake. */
    private var realVlc: VlcPlayerController? = null

    override fun vlcControllerFor(): PlayerController = realVlc ?: vlcController

    /** A real controller over a failing port factory; its `post` launches on the test dispatcher. */
    private fun TestScope.failingVlc(factory: () -> VlcPlayerPort) = VlcPlayerController(
        portFactory = { factory() to true },
        token = { repos.store.session.value.token },
        post = { block -> launch { block() } },
    ).also { realVlc = it }

    @Test
    fun `AC-8_1e a LibVLC creation failure renews once, then the error screen, never a stuck spinner`() = playerTest {
        vlcAvailable = true
        failingVlc { throw VlcUnavailableException(UnsatisfiedLinkError("no libvlc.so")) }
        film(xvid)
        answerWith(direct())
        val vm = vm()
        runCurrent()
        advance(1_000)
        assertTrue(bodies().single().isVlc())
        assertEquals(1, renewals().size)
        val screen = error(vm)
        assertEquals(UiText.Res(R.string.player_playback_failed), screen.message)
        assertEquals(listOf(PlayerAction.RETRY_FROM_HERE, PlayerAction.BACK), screen.actions)
        assertEquals(SessionState.Error, vm.session)
        assertEquals(listOf(VLC), createdEngines, "no ExoPlayer for a file only libVLC plays")
    }

    @Test
    fun `after a LibVLC creation failure Reintentar desde aqui plays on ExoPlayer`() = playerTest {
        vlcAvailable = true
        val provider = LibVlcProvider<Any>(listOf("arm64-v8a"), { throw UnsatisfiedLinkError("no libvlc.so") }, {}) { _, _ -> }
        vlcAvailability = provider.availability::isAvailable
        failingVlc { error("never returns: ${provider.get(emptyList())}") }
        film(xvid)
        answerWith(direct())
        val vm = vm()
        runCurrent()
        advance(1_000)
        assertEquals(listOf(PlayerAction.RETRY_FROM_HERE, PlayerAction.BACK), error(vm).actions)
        assertFalse(provider.availability.isAvailable(), "the failure turned libVLC off for the process")

        vm.onErrorAction(PlayerAction.RETRY_FROM_HERE)
        runCurrent()
        assertFalse(bodies().last().isVlc(), "the retry asks for the ExoPlayer caps")
        assertTrue(bodies().last().client_caps.hls)
        assertEquals(listOf(VLC, EXO), createdEngines)
        assertTrue(controller.log.any { it.startsWith("setMediaItem") }, "${controller.log}")
        assertEquals(EXO, vm.state.value.engine)
        assertEquals(1, renewals().size, "no renewal for the retry itself")
    }

    // ---- review 1: the play intent reaches a real VlcPlayerController before its open ------------------

    /** A real controller over a [FakeVlcPort] whose layout is already attached, so `prepare()` opens at once. */
    private fun TestScope.attachedVlc(port: FakeVlcPort) = VlcPlayerController(
        portFactory = { port to true },
        token = { repos.store.session.value.token },
        post = { block -> launch { block() } },
    ).also {
        realVlc = it
        it.attach(FrameLayout(ContextWrapper(null)))
    }

    @Test
    fun `a playing attach sets the play intent before prepare, so the VLC open is not start-paused`() = playerTest {
        vlcAvailable = true
        val port = FakeVlcPort()
        attachedVlc(port)
        film(xvid)
        answerWith(direct())
        vm()
        runCurrent()
        assertFalse(":start-paused" in port.opens.single().options, "${port.opens.single().options}")
    }

    @Test
    fun `a paused recovery sets the pause intent before prepare, so the VLC reopen is start-paused`() = playerTest {
        vlcAvailable = true
        val port = FakeVlcPort().apply { audioIds = listOf(1); selectedAudio = 1 }
        attachedVlc(port)
        film(xvid)
        answerWith(direct())
        val vm = vm()
        runCurrent()
        port.emit(VlcEvent.Playing)
        runCurrent()
        vm.onPlayPause()
        runCurrent()
        assertEquals(SessionState.Paused, vm.session)
        port.emit(VlcEvent.EncounteredError) // renew + direct recovery at the position, still paused
        runCurrent()
        assertEquals(2, port.opens.size, "${port.calls}")
        assertTrue(":start-paused" in port.opens.last().options, "${port.opens.last().options}")
    }
}
