package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.EpisodeDetail
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.playback.F
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.testing.UiTestPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * prd3 Testing Decisions "Compose UI tests" for the Player (with a fake `PlayerController`): keys
 * while the overlay is hidden, the overlay focus order, the track dialog returning focus to its
 * opener, the Back table rows of the Player, the countdown and the error screens.
 */
@RunWith(AndroidJUnit4::class)
class PlayerFocusTest : UiTestBase() {
    private fun text(value: String) = rule.onNodeWithText(value, useUnmergedTree = true)

    private val twoAudio = F.file(1, audio = listOf(F.audio(1, codec = "aac", default = true), F.audio(2, codec = "ac3", channels = 6, language = "spa")))

    /** Home → OK on the "Seguir viendo" film card (Dune) → Player, READY and playing. */
    private fun openFilm(response: PlaybackResponse = PlaybackResponse("direct", url = "/api/media-files/1/stream", audio_stream_index = 1, duration_seconds = 9360.0)) {
        useLibrary()
        api.onFilm = { id -> com.hometv.tv.core.api.FilmDetail(id = id, title = "Dune", media_files = listOf(twoAudio)) }
        api.onPlayback = { _, _ -> response }
        launch()
        assertFocused("card_continue:film:12")
        press(Key.DirectionCenter)
        node("player").assertIsDisplayed()
        onPlayer { ready() }
    }

    @Test
    fun upShowsTheOverlayWithFocusOnPlayPauseAndRightWalksTheButtonRow() {
        openFilm()
        assertTrue(api.calls.contains("POST /api/media-files/1/playback"))
        press(Key.DirectionUp)
        assertFocused("player_play_pause")
        text("Dune").assertIsDisplayed()
        press(Key.DirectionRight)
        assertFocused("player_forward")
        press(Key.DirectionRight)
        assertFocused("player_tracks")
        press(Key.DirectionLeft, times = 2)
        assertFocused("player_play_pause")
        press(Key.DirectionLeft)
        assertFocused("player_rewind")
        press(Key.DirectionUp)
        assertFocused("player_progress")
    }

    @Test
    fun theTrackDialogOpensOnTheCurrentAudioAndBackReturnsFocusToItsButton() {
        openFilm()
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 2)
        assertFocused("player_tracks")
        text("Audio y subtítulos").assertIsDisplayed()
        press(Key.DirectionCenter)
        assertFocused("track_audio_1")
        text("Subtítulos").assertIsDisplayed()
        text("Desactivados").assertIsDisplayed()
        text("Español · AC3 5.1").assertIsDisplayed()
        back()
        assertFocused("player_tracks")
        // Selecting closes the dialog and applies at once (direct, within caps: no request).
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        assertFocused("track_audio_2")
        val posts = api.calls.count { it.startsWith("POST /api/media-files") }
        press(Key.DirectionCenter)
        assertEquals(2, player.audio)
        assertEquals(posts, api.calls.count { it.startsWith("POST /api/media-files") })
        assertFocused("player_tracks")
    }

    @Test
    fun okWhilePlayingPausesAndShowsTheOverlayAndBackHidesItThenExitsToTheCard() {
        openFilm()
        press(Key.DirectionCenter)
        assertFalse(player.playWhenReady)
        assertFocused("player_play_pause")
        rule.onNodeWithContentDescription("Reproducir").assertIsDisplayed() // the play/pause button's description while paused
        back()
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag("player_play_pause")).assertCountEquals(0)
        back()
        assertTrue(api.calls.contains("PUT /api/progress"))
        assertTrue(player.released)
        assertFocused("card_continue:film:12")
    }

    @Test
    fun leftAndRightWhileHiddenSeekAndShowOnlyTheProgressBar() {
        openFilm()
        rule.runOnUiThread { player.positionMs = 600_000 }
        press(Key.DirectionRight)
        node("player_progress").assertIsDisplayed()
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag("player_play_pause")).assertCountEquals(0)
        UiTestPlatform.advance(700)
        settle()
        assertTrue(player.log.contains("seekTo 610000"))
    }

    @Test
    fun theNextEpisodeCountdownFocusesPlayNowAndBackExits() {
        useLibrary()
        api.onEpisode = { id ->
            if (id == 203L) EpisodeDetail(203, 7, "The Office", season_number = 2, episode_number = 3, media_files = listOf(F.file(2, duration = 1320.0)), next_episode_id = 204)
            else EpisodeDetail(204, 7, "The Office", season_number = 2, episode_number = 4, title = "Halloween", media_files = listOf(F.file(3, duration = 1320.0)))
        }
        api.onPlayback = { id, _ -> PlaybackResponse("hls", url = "/api/hls/S$id/index.m3u8", session_id = "S$id", audio_stream_index = 1, audio_action = "copy", duration_seconds = 1320.0) }
        launch()
        press(Key.DirectionRight)
        assertFocused("card_continue:episode:203")
        press(Key.DirectionCenter)
        onPlayer { ready() }
        onPlayer {
            positionMs = 1_300_000
        }
        UiTestPlatform.advance(1_100)
        settle()
        onPlayer { end() }
        assertFocused("countdown_play_now")
        text("A continuación").assertIsDisplayed()
        text("Halloween").assertIsDisplayed()
        text("Reproducir ahora").assertIsDisplayed()
        press(Key.DirectionRight)
        assertFocused("countdown_cancel")
        back()
        assertTrue(api.calls.contains("DELETE /api/hls/S2"))
        assertFocused("card_continue:episode:203")
    }

    @Test
    fun anUnsupportedFileShowsTheSpanishReasonWithFocusOnTheFirstButton() {
        openFilm(PlaybackResponse("unsupported", reason = "duration_unknown", detail = "English detail"))
        text("Este vídeo no se puede reproducir en este televisor").assertIsDisplayed()
        text("No se conoce la duración del archivo; solo se puede reproducir directamente.").assertIsDisplayed()
        rule.onAllNodes(androidx.compose.ui.test.hasText("English detail")).assertCountEquals(0)
        rule.onNodeWithText("Volver").assertIsFocused()
        back()
        assertFocused("card_continue:film:12")
    }

    @Test
    fun tryOtherVersionOpensTheVersionsListWithNoEntryChecked() {
        useLibrary()
        api.onFilm = { id -> com.hometv.tv.core.api.FilmDetail(id = id, title = "Dune", media_files = listOf(F.file(1), F.file(2, label = "720p H264 · AAC 2.0"))) }
        api.onPlayback = { id, _ ->
            if (id == 1L) PlaybackResponse("unsupported", reason = "video_codec_unsupported")
            else PlaybackResponse("direct", url = "/api/media-files/$id/stream", audio_stream_index = 1, duration_seconds = 9360.0)
        }
        launch()
        press(Key.DirectionCenter)
        assertTrue(api.calls.contains("POST /api/media-files/1/playback"))
        rule.onNodeWithText("Probar otra versión").assertIsFocused()
        press(Key.DirectionCenter)
        node("list_dialog").assertIsDisplayed()
        node("dialog_item_0").assertIsNotSelected()
        assertFocused("dialog_item_0")
    }

    @Test
    fun aRemovedFilmShowsTheItemGoneScreen() {
        useLibrary()
        api.onFilm = { throw com.hometv.tv.core.data.FakeHomeTvApi.httpError(404, "not_found") }
        launch()
        press(Key.DirectionCenter)
        text("Este título ya no está disponible.").assertIsDisplayed()
        rule.onNodeWithText("Volver").assertIsFocused()
        press(Key.DirectionCenter)
        settle()
        node("card_continue:episode:203").assertIsDisplayed()
    }
}
