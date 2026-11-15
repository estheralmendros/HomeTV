package com.hometv.tv.ui.player

import com.hometv.tv.core.api.AudioChoice
import com.hometv.tv.core.api.AudioTrack
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.SubtitleChoice
import com.hometv.tv.core.api.TrackChoice
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.data.RatingKind
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.PlaybackErrorCodes
import com.hometv.tv.core.playback.PlayerAction
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.UrlKind
import com.hometv.tv.core.playback.VlcCapsBuilder
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** prd3 FR-T4..FR-T6: the remembered audio/subtitle choice at every start, and the saving of manual picks. */
class PlayerTrackChoiceTest : PlayerTestBase() {
    private val spa = F.audio(1, language = "spa", default = true)
    private val eng = F.audio(2, language = "eng")
    private val engChoice = TrackChoice(AudioChoice("eng", null, 2), null)
    private val spaChoice = TrackChoice(AudioChoice("spa", null, 2), null)

    private fun file(id: Long, vararg audio: AudioTrack, duration: Double = 7200.0): MediaFile = F.file(id, audio = audio.toList(), duration = duration)

    private fun filmWith(choice: TrackChoice?, vararg files: MediaFile) {
        api.onFilm = { id -> FilmDetail(id = id, title = "Dune", media_files = files.toList(), track_choice = choice) }
    }

    private fun audioSent() = bodies().map { it.audio_stream_index }

    private fun saved() = api.trackChoiceBodies.map { it.third }

    private fun TestScope.playing(vm: PlayerViewModel): PlayerViewModel {
        runCurrent()
        controller.ready()
        runCurrent()
        return vm
    }

    // ---- T-AC-3: every send call site recomputes the remembered audio per file ----------------

    @Test
    fun `START sends the matched index, null when the match is the file default`() = playerTest {
        filmWith(engChoice, file(1, spa, eng))
        answerWith(direct(audio = 2))
        playing(vm())
        assertEquals(listOf<Int?>(2), audioSent())
        assertEquals(2, controller.audio)

        clearCalls()
        filmWith(spaChoice, file(1, spa, eng))
        answerWith(direct(audio = 1))
        playing(vm())
        assertEquals(listOf<Int?>(null), audioSent())
        assertTrue(api.trackChoiceBodies.isEmpty(), "applying a choice never saves")
    }

    @Test
    fun `NEXT_EPISODE and each of its fallback files recompute it`() = playerTest {
        val first = file(21, spa, eng, duration = 1320.0)
        val second = file(22, F.audio(4, language = "spa", default = true), F.audio(5, language = "eng"), duration = 1320.0)
        episodes(
            episodeDetail(203, listOf(file(1, spa, eng, duration = 1320.0)), next = 204).copy(track_choice = engChoice),
            episodeDetail(204, listOf(first, second)).copy(track_choice = engChoice),
        )
        answerWith(direct(duration = 1320.0, audio = 2))
        val vm = playing(vm(itemType = "episode", itemId = 203))
        controller.positionMs = 1_300_000
        advance(1_000)
        controller.end()
        runCurrent()
        answerWith(unsupported("video_codec_unsupported"), unsupported("video_codec_unsupported"), direct(fileId = 22, duration = 1320.0, audio = 5))
        vm.onPlayNow()
        runCurrent()
        val files = api.playbackBodies.map { it.first }
        assertEquals(listOf(1L, 21L, 21L, 22L), files)
        // 21: the remembered index, then (refused) once with the default; 22: its own eng track.
        assertEquals(listOf(2, 2, null, 5), audioSent())
    }

    @Test
    fun `NEXT_PART recomputes it for the next part`() = playerTest {
        val part1 = F.part(1, 1).copy(audio_tracks = listOf(spa, eng))
        val part2 = F.part(2, 2).copy(audio_tracks = listOf(F.audio(3, language = "spa", default = true), F.audio(7, language = "eng")))
        filmWith(engChoice, part1, part2)
        answerWith(direct(duration = 3000.0, audio = 2))
        val vm = playing(vm(mediaFileId = 1))
        controller.end()
        runCurrent()
        answerWith(direct(fileId = 2, duration = 3000.0, audio = 7))
        vm.onPlayNow()
        runCurrent()
        assertEquals(listOf(2, 7), audioSent())
    }

    @Test
    fun `another version recomputes it`() = playerTest {
        filmWith(engChoice, F.hevc(1).copy(audio_tracks = listOf(spa, eng)), file(2, F.audio(8, language = "eng"), F.audio(9, language = "spa", default = true)))
        codecs.inventory = codecs.inventory.copy(video = codecs.inventory.video.map { it.copy(maxWidth = 3840, maxHeight = 2160) })
        answerWith(unsupported("video_codec_unsupported"), unsupported("video_codec_unsupported"), direct(fileId = 2, audio = 8))
        val vm = vm(mediaFileId = 1)
        runCurrent()
        vm.onErrorAction(PlayerAction.TRY_OTHER_VERSION)
        vm.onVersionChosen(0)
        runCurrent()
        assertEquals(listOf(1L, 1L, 2L), api.playbackBodies.map { it.first })
        assertEquals(listOf(2, null, 8), audioSent())
    }

    // ---- T-AC-4: a refused remembered track ----------------------------------------------------

    @Test
    fun `invalid_audio_stream re-sends once with null, no toast, no save, later recoveries never send the remembered index`() = playerTest {
        filmWith(engChoice, file(1, spa, eng))
        answer({ throw httpError(422, "invalid_audio_stream") }, { direct(audio = 1) })
        val vm = playing(vm())
        assertEquals(listOf(2, null), audioSent())
        assertNull(vm.state.value.toast)
        assertTrue(api.trackChoiceBodies.isEmpty())
        answerWith(hls(audio = 1))
        controller.fail(PlayerFailure(PlaybackErrorCodes.PARSING_CONTAINER_MALFORMED, urlKind = UrlKind.STREAM))
        runCurrent()
        assertTrue(audioSent().drop(2).none { it == 2 }, "${audioSent()}")
    }

    @Test
    fun `unsupported takes the VLC fallback with the remembered index when it applies`() = playerTest {
        vlcAvailable = true
        filmWith(engChoice, file(1, spa, eng))
        answerWith(unsupported("hls_not_supported"), direct(audio = 2))
        val vm = vm()
        runCurrent()
        assertEquals(listOf(2, 2), audioSent())
        assertEquals(VlcCapsBuilder.build(), bodies().last().client_caps)
        assertNull(vm.state.value.toast)
        assertTrue(api.trackChoiceBodies.isEmpty())
    }

    @Test
    fun `unsupported without the VLC fallback re-sends once with null, silently`() = playerTest {
        filmWith(engChoice, file(1, spa, eng))
        answerWith(unsupported("hls_not_supported"), direct(audio = 1))
        val vm = playing(vm())
        assertEquals(listOf(2, null), audioSent())
        assertEquals(SessionState.Playing, vm.session)
        assertNull(vm.state.value.toast)
        assertTrue(api.trackChoiceBodies.isEmpty())
    }

    // ---- T-AC-5: the first decision of each file selects the matched subtitle -------------------

    @Test
    fun `the first decision selects the remembered subtitle, else the default rule`() = playerTest {
        val subs = listOf(F.sub("emb-3", "eng", default = true), F.sub("emb-4", "spa"))
        filmWith(TrackChoice(null, SubtitleChoice("track", "spa", null, false, false, "embedded", "text")), file(1, spa, eng))
        answerWith(direct(subs = subs))
        playing(vm())
        assertEquals("emb-4", controller.subtitle)

        filmWith(null, file(1, spa, eng))
        playing(vm())
        assertEquals("emb-3", controller.subtitle)
    }

    @Test
    fun `an image pick selects the image track in direct mode, natively`() = playerTest {
        filmWith(TrackChoice(null, SubtitleChoice("track", "eng", null, false, false, "embedded", "image")), file(1, spa, eng))
        answerWith(direct(subs = listOf(F.sub("emb-6", "eng", kind = "image"), F.sub("emb-7", "eng"))))
        playing(vm())
        assertEquals("emb-6", controller.subtitle)
        assertTrue(subtitleUrls.isEmpty(), "$subtitleUrls")
    }

    @Test
    fun `Desactivados remembered starts with subtitles off`() = playerTest {
        filmWith(TrackChoice(null, SubtitleChoice("off")), file(1, spa, eng))
        answerWith(direct(subs = listOf(F.sub("emb-3", "eng", default = true))))
        playing(vm())
        assertNull(controller.subtitle)
    }

    // ---- T-AC-6: manual picks save the merged whole choice ----------------------------------------

    @Test
    fun `manual audio and subtitle picks save the whole choice once each, automatic changes never`() = playerTest {
        val text = F.sub("emb-4", "spa")
        filmWith(null, file(1, spa, eng))
        answerWith(direct(subs = listOf(text)))
        val vm = playing(vm())
        assertTrue(api.trackChoiceBodies.isEmpty())
        vm.onSelectAudio(2)
        runCurrent()
        assertEquals(listOf(Triple("films", 12L, engChoice)), api.trackChoiceBodies)
        vm.onSelectSubtitle(null)
        runCurrent()
        assertEquals(TrackChoice(engChoice.audio, SubtitleChoice("off")), saved().last())
        vm.onSelectSubtitle("emb-4")
        runCurrent()
        assertEquals(TrackChoice(engChoice.audio, SubtitleChoice("track", "spa", null, false, false, "embedded", "text")), saved().last())
        assertEquals(3, api.trackChoiceBodies.size)
    }

    @Test
    fun `quick picks send one PUT at a time, the last body being the last pick`() = playerTest {
        val gate = CompletableDeferred<Unit>()
        api.onTrackChoiceWrite = { _, _, _ -> gate.await() }
        filmWith(null, file(1, spa, eng))
        answerWith(direct())
        val vm = playing(vm())
        vm.onSelectAudio(2)
        vm.onSelectAudio(1)
        vm.onSelectSubtitle(null)
        runCurrent()
        assertEquals(1, api.trackChoiceBodies.size)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(engChoice, TrackChoice(spaChoice.audio, SubtitleChoice("off"))), saved())
    }

    @Test
    fun `a failed PUT is only logged, and a wish queued under profile A is dropped after a switch to B`() = playerTest {
        val gate = CompletableDeferred<Unit>()
        api.onTrackChoiceWrite = { _, _, _ ->
            gate.await()
            throw java.io.IOException("down")
        }
        filmWith(null, file(1, spa, eng))
        answerWith(direct())
        val vm = playing(vm())
        vm.onSelectAudio(2)
        vm.onSelectAudio(1)
        repos.store.saveSession(6, "tok-6", Instant.parse("2026-12-20T00:00:00Z"))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(engChoice), saved())
        assertNull(vm.state.value.toast)
    }

    // ---- T-AC-7 / T-AC-8: session precedence and the payload start -----------------------------

    @Test
    fun `a pick on episode 1 starts the next episode even while its PUT is gated and the detail is older`() = playerTest {
        val gate = CompletableDeferred<Unit>()
        api.onTrackChoiceWrite = { _, _, _ -> gate.await() }
        episodes(
            episodeDetail(203, listOf(file(1, spa, eng, duration = 1320.0)), next = 204).copy(track_choice = spaChoice),
            episodeDetail(204, listOf(file(2, spa, eng, duration = 1320.0))).copy(track_choice = spaChoice),
        )
        answerWith(direct(duration = 1320.0))
        val vm = playing(vm(itemType = "episode", itemId = 203))
        vm.onSelectAudio(2)
        controller.positionMs = 1_300_000
        advance(1_000)
        controller.end()
        runCurrent()
        answerWith(direct(fileId = 2, duration = 1320.0, audio = 2))
        vm.onPlayNow()
        runCurrent()
        assertEquals(listOf(null, 2), audioSent())
        assertEquals(listOf(Triple("shows", 7L, engChoice)), api.trackChoiceBodies)
    }

    @Test
    fun `leaving the player and starting the show again keeps the pick, also after a failed PUT, until a profile change`() = playerTest {
        api.onTrackChoiceWrite = { _, _, _ -> throw java.io.IOException("down") }
        episodes(episodeDetail(203, listOf(file(1, spa, eng, duration = 1320.0))).copy(track_choice = spaChoice))
        answerWith(direct(duration = 1320.0))
        val first = playing(vm(itemType = "episode", itemId = 203))
        first.onSelectAudio(2)
        runCurrent()
        clear(first)
        clearCalls()
        playing(vm(itemType = "episode", itemId = 203))
        assertEquals(listOf<Int?>(2), audioSent())
        assertEquals(engChoice, repos.trackChoiceMemory.get(5, RatingKind.SHOW, 7))

        repos.store.saveSession(6, "tok-6", Instant.parse("2026-12-20T00:00:00Z"))
        runCurrent()
        assertNull(repos.trackChoiceMemory.get(5, RatingKind.SHOW, 7))
        clearCalls()
        playing(vm(itemType = "episode", itemId = 203))
        assertEquals(listOf<Int?>(null), audioSent())
    }

    @Test
    fun `a confirmed pick gives way to a detail fetched after the confirmation`() = playerTest {
        episodes(episodeDetail(203, listOf(file(1, spa, eng, duration = 1320.0))).copy(track_choice = spaChoice))
        answerWith(direct(duration = 1320.0))
        val first = playing(vm(itemType = "episode", itemId = 203))
        first.onSelectAudio(2)
        runCurrent()
        clear(first)
        // Another device picked Spanish meanwhile: the confirmed local pick is dropped by the newer fetch.
        clearCalls()
        playing(vm(itemType = "episode", itemId = 203))
        assertEquals(listOf<Int?>(null), audioSent())
        assertNull(repos.trackChoiceMemory.get(5, RatingKind.SHOW, 7))
    }

    @Test
    fun `a Seguir viendo start applies the payload choice without waiting for the gated episode detail`() = playerTest {
        val gate = CompletableDeferred<Unit>()
        val payload = EpisodeSummary(
            id = 203, show_id = 7, show_title = "The Office", season_number = 2, episode_number = 3, title = "Office Olympics",
            media_files = listOf(file(1, spa, eng, duration = 1320.0)), track_choice = engChoice,
        )
        api.onEpisode = { id ->
            gate.await()
            episodeDetail(id, listOf(file(1, spa, eng, duration = 1320.0)))
        }
        answerWith(direct(duration = 1320.0, audio = 2))
        vm(itemType = "episode", itemId = 203, payload = payload)
        runCurrent()
        assertEquals(listOf("GET /api/episodes/203", "POST /api/media-files/1/playback"), api.calls)
        assertEquals(listOf<Int?>(2), audioSent())
        gate.complete(Unit)
        runCurrent()
    }

    // ---- T-AC-19 / T-AC-19b: re-picking the playing track ---------------------------------------

    private val dts = F.audio(2, codec = "dts", channels = 6, language = "eng")

    @Test
    fun `re-picking the playing track while a server switch is pending saves the re-picked track`() = playerTest {
        filmWith(null, file(1, spa, dts))
        answer({ direct(audio = 1) }, { CompletableDeferred<PlaybackResponse>().await() })
        val vm = playing(vm())
        vm.onSelectAudio(2)
        runCurrent()
        assertEquals(2, posts().size, "the server switch is pending")
        vm.onSelectAudio(1)
        runCurrent()
        assertEquals(listOf(TrackChoice(AudioChoice("eng", null, 6), null), spaChoice), saved())
        assertEquals(SessionState.Playing, vm.session)
    }

    @Test
    fun `re-picking the pending switch's target or the playing remembered track sends no PUT`() = playerTest {
        filmWith(null, file(1, spa, dts))
        answer({ direct(audio = 1) }, { CompletableDeferred<PlaybackResponse>().await() })
        val vm = playing(vm())
        vm.onSelectAudio(2)
        vm.onSelectAudio(2)
        runCurrent()
        assertEquals(1, api.trackChoiceBodies.size)

        clearCalls()
        api.trackChoiceBodies.clear()
        filmWith(engChoice, file(1, spa, eng))
        answerWith(direct(audio = 2))
        val other = playing(vm())
        other.onSelectAudio(2)
        runCurrent()
        assertTrue(api.trackChoiceBodies.isEmpty())
    }

    @Test
    fun `re-picking the playing default over a missing remembered language saves it, no send and no switch`() = playerTest {
        filmWith(engChoice, file(1, spa))
        answerWith(direct(audio = 1))
        val vm = playing(vm())
        assertEquals(listOf<Int?>(null), audioSent())
        controller.clearLog()
        vm.onSelectAudio(1)
        runCurrent()
        assertEquals(listOf(spaChoice), saved())
        assertEquals(1, posts().size)
        assertTrue(controller.log.none { it.startsWith("audio") }, "${controller.log}")
    }

    @Test
    fun `re-picking the default after a refused remembered track, or with no audio part held, saves it`() = playerTest {
        filmWith(engChoice, file(1, spa, eng))
        answerWith(unsupported("hls_not_supported"), direct(audio = 1))
        val vm = playing(vm())
        vm.onSelectAudio(1)
        runCurrent()
        assertEquals(listOf(spaChoice), saved())

        api.trackChoiceBodies.clear()
        api.onFilm = { id -> FilmDetail(id = id, title = "Alien", media_files = listOf(file(5, spa, eng))) }
        answerWith(direct(fileId = 5, audio = 1))
        val fresh = playing(vm(itemId = 13))
        fresh.onSelectAudio(1)
        runCurrent()
        assertEquals(listOf(Triple("films", 13L, spaChoice)), api.trackChoiceBodies)
    }
}
