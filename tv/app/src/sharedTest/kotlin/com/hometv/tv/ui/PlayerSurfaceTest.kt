package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.EpisodeDetail
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.VideoInfo
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.testing.UiTestBase
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The video surface follows the lazily created controller (prd.md FR-3.5, AC-3.16) and the container
 * builds no player before the first attach (AC-3.11b); with libVLC available each engine's surface binds that
 * engine's fake, and only the engines a screen uses are built (prd2 AC-9.2, AC-11.1).
 */
@RunWith(AndroidJUnit4::class)
class PlayerSurfaceTest : UiTestBase() {
    private val direct = PlaybackResponse("direct", url = "/api/media-files/1/stream", audio_stream_index = 1, duration_seconds = 9360.0)

    private var vlc = false

    override val vlcAvailable: Boolean get() = vlc

    private val mpeg4 = VideoInfo("mpeg4", 720, 400)

    /** Home → OK on "Seguir viendo" Dune → Player; the playback answer waits for [gate]. */
    private fun openFilm(gate: CompletableDeferred<Unit>, file: MediaFile = F.file(1)) {
        useLibrary()
        api.onFilm = { id -> FilmDetail(id = id, title = "Dune", media_files = listOf(file)) }
        api.onPlayback = { _, _ ->
            gate.await()
            direct
        }
        launch()
        assertFocused("card_continue:film:12")
        press(Key.DirectionCenter)
        node("player").assertIsDisplayed()
    }

    @Test
    fun theSurfaceBindsTheExoControllerCreatedAtTheFirstAttach() {
        val gate = CompletableDeferred<Unit>()
        openFilm(gate)
        assertEquals("no player before the answer", 0, playerBuilds)
        gate.complete(Unit)
        settle()
        onPlayer { ready() }
        assertEquals(1, playerBuilds)
        val last = surfaceBindings.last()
        assertEquals(PlaybackEngine.EXO, last.first)
        assertSame(player, last.second)
    }

    @Test
    fun backWhileLoadingBuildsNoPlayer() {
        val gate = CompletableDeferred<Unit>()
        openFilm(gate)
        back()
        assertTrue(absent("player"))
        gate.complete(Unit)
        settle()
        assertEquals(0, playerBuilds)
        assertTrue(surfaceBindings.all { it.second == null })
    }

    @Test
    fun anH264FilmBindsTheExoFakeEvenWithLibVlc() {
        vlc = true
        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        openFilm(gate)
        onPlayer { ready() }
        assertEquals(PlaybackEngine.EXO to player, surfaceBindings.last())
        assertEquals("AC-11.1: only the EXO fake", 1 to 0, playerBuilds to vlcPlayerBuilds)
    }

    @Test
    fun aMpeg4FilmBindsTheVlcFake() {
        vlc = true
        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        openFilm(gate, F.file(1, video = mpeg4, container = "avi", fileName = "dune.avi"))
        onVlcPlayer { ready() }
        assertEquals(PlaybackEngine.VLC to vlcPlayer, surfaceBindings.last())
        assertEquals("AC-11.1: only the VLC fake", 0 to 1, playerBuilds to vlcPlayerBuilds)
        node("player_video").assertExists()
    }

    @Test
    fun withoutLibVlcAMpeg4FilmSendsTodaysRequestAndBuildsNoPlayer() {
        // AC-5.2: vlcAvailability false (the default) keeps today's request for every file.
        useLibrary()
        val bodies = mutableListOf<com.hometv.tv.core.api.PlaybackRequest>()
        api.onFilm = { id -> FilmDetail(id = id, title = "Dune", media_files = listOf(F.file(1, video = mpeg4, container = "avi", fileName = "dune.avi"))) }
        api.onPlayback = { _, body ->
            bodies += body
            PlaybackResponse("unsupported", reason = "video_codec_unsupported", detail = "English detail")
        }
        launch()
        press(Key.DirectionCenter)
        val body = bodies.single()
        assertTrue("today's ExoPlayer caps ask for HLS", body.client_caps.hls)
        assertTrue("avi" !in body.client_caps.containers)
        assertEquals(0 to 0, playerBuilds to vlcPlayerBuilds)
        assertTrue(surfaceBindings.none { it.first == PlaybackEngine.VLC })
    }

    @Test
    fun aSeriesSwitchingEnginesThroughTheCountdownRebindsEachEngine() {
        vlc = true
        useLibrary()
        val files = mapOf(203L to F.file(2, duration = 1320.0), 204L to F.file(3, video = mpeg4, container = "avi", duration = 1320.0), 205L to F.file(4, duration = 1320.0))
        api.onEpisode = { id ->
            EpisodeDetail(id, 7, "The Office", season_number = 2, episode_number = (id - 200).toInt(), title = "Episode $id", media_files = listOf(files.getValue(id)), next_episode_id = (id + 1).takeIf { it <= 205 })
        }
        api.onPlayback = { id, _ -> PlaybackResponse("direct", url = "/api/media-files/$id/stream", audio_stream_index = 1, duration_seconds = 1320.0) }
        launch()
        press(Key.DirectionRight)
        assertFocused("card_continue:episode:203")
        press(Key.DirectionCenter)
        onPlayer { ready() }
        endThenPlayNow { onPlayer(it) }
        onVlcPlayer { ready() }
        endThenPlayNow { onVlcPlayer(it) }
        onPlayer { ready() }
        val engines = surfaceBindings.filter { it.first != null }
        assertEquals(listOf(PlaybackEngine.EXO to player, PlaybackEngine.VLC to vlcPlayer, PlaybackEngine.EXO to player), engines.dedupeRuns())
        assertEquals(1 to 1, playerBuilds to vlcPlayerBuilds)
    }

    /** Near the end, ENDED, then "Reproducir ahora" on the countdown. */
    private fun endThenPlayNow(drive: (com.hometv.tv.testing.FakePlayerController.() -> Unit) -> Unit) {
        drive { positionMs = 1_300_000 }
        com.hometv.tv.testing.UiTestPlatform.advance(1_100)
        settle()
        drive { end() }
        assertFocused("countdown_play_now")
        press(Key.DirectionCenter)
    }

    private fun <T> List<T>.dedupeRuns(): List<T> = filterIndexed { i, v -> i == 0 || this[i - 1] != v }

    /** The `SubtitleView`'s applied style, text size fraction and bottom padding (Media3 1.5.1 private fields). */
    private fun subtitleField(name: String): Any? {
        val view = checkNotNull(exoView?.subtitleView) { "no Exo SubtitleView bound" }
        return androidx.media3.ui.SubtitleView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view)
    }

    @Test
    fun theExoSubtitleViewDrawsTheProfileStyleAndADraftChangeRestylesItLive() {
        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        openFilm(gate)
        onPlayer { ready() }
        val default = subtitleField("style") as androidx.media3.ui.CaptionStyleCompat
        assertEquals(0xFFFFFFFF.toInt(), default.foregroundColor)
        assertEquals(androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, default.edgeType)
        assertEquals(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION, subtitleField("defaultTextSize") as Float, 1e-4f)
        // With the overlay hidden the padding is the look's position (Baja: 8 %).
        assertEquals(0.08f, subtitleField("bottomPaddingFraction") as Float, 1e-4f)
        // The overlay's bottom block shown: the subtitles sit above it.
        press(Key.DirectionUp)
        assertTrue("raised above the overlay: ${subtitleField("bottomPaddingFraction")}", (subtitleField("bottomPaddingFraction") as Float) > 0.08f)
        press(Key.DirectionRight, times = 2)
        press(Key.DirectionCenter)
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        assertFocused("style_row_size")
        // The style dialog hides the overlay: "Posición" previews at its real height.
        assertEquals(0.08f, subtitleField("bottomPaddingFraction") as Float, 1e-4f)
        press(Key.DirectionRight)
        assertEquals(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.3f, subtitleField("defaultTextSize") as Float, 1e-4f)
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertEquals(0xFFFFFF00.toInt(), (subtitleField("style") as androidx.media3.ui.CaptionStyleCompat).foregroundColor)
        press(Key.DirectionDown, times = 3)
        assertFocused("style_row_position")
        press(Key.DirectionRight, times = 2)
        assertEquals(0.20f, subtitleField("bottomPaddingFraction") as Float, 1e-4f)
    }
}
