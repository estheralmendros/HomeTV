package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.playback.F
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.ui.theme.HomeTvDimens
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** prd3 T-AC-17 (the tagged node is the dialog's content, inside its 24 dp padding): "Audio y subtítulos" with 10 subtitle tracks keeps "Formato de los subtítulos" inside the dialog and the safe area. */
@RunWith(AndroidJUnit4::class)
class TrackSelectionDialogFitTest : UiTestBase() {
    @Test
    fun tenSubtitleTracksKeepTheStyleButtonInsideTheDialog() {
        useLibrary()
        val subs = (1..10).map { F.sub("emb-$it", if (it % 2 == 0) "spa" else "eng", title = "Pista $it") }
        api.onFilm = { id -> FilmDetail(id = id, title = "Dune", media_files = listOf(F.file(1))) }
        api.onPlayback = { _, _ -> PlaybackResponse("direct", url = "/api/media-files/1/stream", audio_stream_index = 1, duration_seconds = 9360.0, subtitle_tracks = subs) }
        launch()
        press(Key.DirectionCenter)
        node("player").assertIsDisplayed()
        onPlayer { ready() }
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 2)
        press(Key.DirectionCenter)
        assertFocused("track_audio_1")
        val dialog = dpBounds("track_dialog")
        val button = dpBounds("track_style")
        assertTrue("the button (${button.toList()}) is outside the dialog (${dialog.toList()})", button[1] >= dialog[1] && button[3] <= dialog[3] + 0.5f)
        val root = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().first()
        val density = root.layoutInfo.density.density
        val room = root.size.height / density - 2 * HomeTvDimens.SafeVertical.value - DIALOG_WINDOW_INSETS
        assertTrue("the dialog is ${dialog[3] - dialog[1]} dp tall; it must stay within $room dp", dialog[3] - dialog[1] <= room + 0.5f)
        // The subtitle column scrolls; Down from its last item still reaches the button.
        press(Key.DirectionRight)
        press(Key.DirectionDown, times = 11)
        assertFocused("track_style")
    }
}
