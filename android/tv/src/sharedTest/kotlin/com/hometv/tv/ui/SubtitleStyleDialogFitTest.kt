package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.playback.F
import com.hometv.tv.testing.UiTestBase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * prd3 T-AC-17 (960×540 dp, native graphics): the style dialog sits in the upper part of the screen, inside the
 * safe area, its bottom at ≤ 324 dp (60 %) at "Mediano" and at "Grande" (budget 295 / 299 dp), every row and the
 * sample inside it.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleStyleDialogFitTest : UiTestBase() {
    private fun openStyleDialog() {
        useLibrary()
        api.onFilm = { id -> FilmDetail(id = id, title = "Dune", media_files = listOf(F.file(1))) }
        api.onPlayback = { _, _ -> PlaybackResponse("direct", url = "/api/media-files/1/stream", audio_stream_index = 1, duration_seconds = 9360.0) }
        launch()
        press(Key.DirectionCenter)
        node("player").assertIsDisplayed()
        onPlayer { ready() }
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 2)
        press(Key.DirectionCenter)
        press(Key.DirectionDown, times = 2)
        assertFocused("track_style")
        press(Key.DirectionCenter)
        assertFocused("style_row_size")
    }

    private fun assertFitsTheUpperPart() {
        assertInsideSafeArea("subtitle_style_dialog")
        val dialog = dpBounds("subtitle_style_dialog")
        assertTrue("the dialog ends at ${dialog[3]} dp, below 60 % of the screen (324 dp)", dialog[3] <= 324f)
        (listOf("style_row_size", "style_row_color", "style_row_edge", "style_row_edge_color", "style_row_position", "style_sample")).forEach { tag ->
            val b = dpBounds(tag)
            // The focused row is drawn at 1.02 (over the gaps and the 24 dp side padding): bounds include that scale.
            val slack = (b[2] - b[0]) * 0.01f + 0.5f
            assertTrue("'$tag' (${b.toList()}) is outside the dialog (${dialog.toList()})", b[0] >= dialog[0] - slack && b[2] <= dialog[2] + slack && b[1] >= dialog[1] - slack && b[3] <= dialog[3] + slack)
        }
    }

    @Test
    fun atMedianoTheDialogFitsTheUpperPart() {
        openStyleDialog()
        assertFitsTheUpperPart()
    }

    @Test
    fun atGrandeTheDialogStillFitsTheUpperPart() {
        openStyleDialog()
        press(Key.DirectionRight)
        assertFitsTheUpperPart()
    }
}
