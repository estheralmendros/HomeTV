package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.SubtitleStyle
import com.hometv.tv.core.playback.F
import com.hometv.tv.testing.UiTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * prd3 T-AC-14..T-AC-16 with the D-pad: "Formato de los subtítulos" in "Audio y subtítulos", the style dialog's
 * rows (Up/Down, the skipped "Color del contorno", Left/Right values with clash skips and stops), Back saves once
 * and returns focus to "Audio y subtítulos".
 */
@RunWith(AndroidJUnit4::class)
class SubtitleStyleFocusTest : UiTestBase() {
    private val twoAudio = F.file(1, audio = listOf(F.audio(1, default = true), F.audio(2, language = "spa")))

    private fun openPlayer() {
        useLibrary()
        api.onFilm = { id -> FilmDetail(id = id, title = "Dune", media_files = listOf(twoAudio)) }
        api.onPlayback = { _, _ ->
            PlaybackResponse("direct", url = "/api/media-files/1/stream", audio_stream_index = 1, duration_seconds = 9360.0, subtitle_tracks = listOf(F.sub("emb-3", "spa")))
        }
        launch()
        assertFocused("card_continue:film:12")
        press(Key.DirectionCenter)
        node("player").assertIsDisplayed()
        onPlayer { ready() }
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 2)
        assertFocused("player_tracks")
        press(Key.DirectionCenter)
        assertFocused("track_audio_1")
    }

    private fun openStyleDialog() {
        openPlayer()
        press(Key.DirectionDown, times = 2)
        assertFocused("track_style")
        press(Key.DirectionCenter)
        node("subtitle_style_dialog").assertExists()
        assertFocused("style_row_size")
    }

    private fun description(tag: String, text: String) = node(tag).assertContentDescriptionEquals(text)

    @Test
    fun theButtonIsReachedFromBothColumnsAndUpReturnsToTheColumnItCameFrom() {
        openPlayer()
        rule.onNodeWithText("Formato de los subtítulos", useUnmergedTree = true).assertIsDisplayed()
        press(Key.DirectionDown, times = 2)
        assertFocused("track_style")
        press(Key.DirectionUp)
        assertFocused("track_audio_2")
        press(Key.DirectionRight)
        assertTrue(focusedTags().toString(), focusedTags().any { it.startsWith("track_sub_") })
        press(Key.DirectionDown, times = 3)
        assertFocused("track_style")
        press(Key.DirectionUp)
        assertFocused("track_sub_emb-3")
    }

    @Test
    fun okOpensTheDialogOnTamanoAndUpDownWalkTheRowsSkippingTheEdgeColourWithoutAnEdge() {
        openStyleDialog()
        press(Key.DirectionDown)
        assertFocused("style_row_color")
        press(Key.DirectionDown)
        assertFocused("style_row_edge")
        press(Key.DirectionDown)
        assertFocused("style_row_edge_color")
        press(Key.DirectionUp)
        assertFocused("style_row_edge")
        // Contorno → Sombra → Sin contorno: "Color del contorno" is skipped both ways.
        press(Key.DirectionRight, times = 2)
        description("style_row_edge", "Contorno: Sin contorno")
        press(Key.DirectionDown)
        assertFocused("style_row_position")
        press(Key.DirectionUp)
        assertFocused("style_row_edge")
    }

    @Test
    fun leftRightChangeValuesStopAtTheEndsAndSkipTheClashTheSampleFollows() {
        openStyleDialog()
        description("style_row_size", "Tamaño: Mediano")
        val medium = dpBounds("style_sample").let { it[3] - it[1] }
        press(Key.DirectionRight)
        description("style_row_size", "Tamaño: Grande")
        press(Key.DirectionRight)
        description("style_row_size", "Tamaño: Grande")
        assertTrue("the sample band grows with Grande", dpBounds("style_sample").let { it[3] - it[1] } > medium)
        // White text: the edge colour goes Negro → Gris oscuro and stops (Blanco skipped).
        press(Key.DirectionDown, times = 3)
        assertFocused("style_row_edge_color")
        press(Key.DirectionRight, times = 2)
        description("style_row_edge_color", "Color del contorno: Gris oscuro")
        press(Key.DirectionLeft, times = 3)
        description("style_row_edge_color", "Color del contorno: Negro")
        // OK does nothing on a row.
        press(Key.DirectionCenter)
        assertFocused("style_row_edge_color")
    }

    @Test
    fun backClosesSavesOnceAndReturnsFocusToAudioYSubtitulos() {
        openStyleDialog()
        press(Key.DirectionRight)
        back()
        assertTrue(absent("subtitle_style_dialog"))
        assertFocused("player_tracks")
        assertEquals(listOf(SubtitleStyle(size = "large")), api.subtitleStyleBodies)
        // Unchanged: closing saves nothing more.
        press(Key.DirectionCenter)
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        assertFocused("style_row_size")
        back()
        assertEquals(1, api.subtitleStyleBodies.size)
        assertFocused("player_tracks")
    }
}
