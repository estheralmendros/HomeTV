package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.RatingPrompt
import com.hometv.tv.core.api.WatchedResponse
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.playback.F
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.ui.theme.HomeTvDimens
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native graphics, real text metrics): the dialog's buttons are one full-width
 * row under the poster in the widest cases, every button fully visible inside the dialog and the
 * safe area, sharing one top.
 */
@RunWith(AndroidJUnit4::class)
class RatingDialogFitTest : UiTestBase() {
    private val longTitle = "Un título larguísimo que no cabe en una sola línea del diálogo"

    private fun openFilm(myRating: Double?) {
        useLibrary()
        api.onFilm = { id -> FilmDetail(id = id, title = longTitle, match_status = "matched", media_files = listOf(F.file(1)), my_rating = myRating) }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
    }

    /**
     * Dialog nodes are positioned in the dialog window, so they are checked against the dialog's own
     * bounds; the dialog itself must fit the safe area with the room a TV's dialog-window insets take
     * (Robolectric does not model them, see `UiTestBase.DIALOG_WINDOW_INSETS`).
     */
    private fun assertOneRowInsideTheDialog(vararg tags: String) {
        val dialog = dpBounds("rating_dialog")
        val contentLeft = dialog[0] + DIALOG_PADDING
        val contentRight = dialog[2] - DIALOG_PADDING
        val tops = tags.map { dpBounds(it)[1] }
        tags.forEach { tag ->
            node(tag).assertExists()
            val b = dpBounds(tag)
            assertTrue("'$tag' ends at ${b[2]} dp, past the dialog's content edge ($contentRight dp)", b[2] <= contentRight + 0.5f)
            assertTrue("'$tag' starts at ${b[0]} dp, left of the dialog's content (${contentLeft} dp)", b[0] >= contentLeft - 0.5f)
            assertTrue("'$tag' ends at ${b[3]} dp, below the dialog (${dialog[3]} dp)", b[3] <= dialog[3] - DIALOG_PADDING + 0.5f)
            assertTrue("'$tag' is one line, not wrapped (${b[3] - b[1]} dp tall)", b[3] - b[1] <= 48.5f)
        }
        assertTrue("the buttons share one top: $tops", tops.all { kotlin.math.abs(it - tops.first()) < 0.5f })
        val root = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().first()
        val density = root.layoutInfo.density.density
        val screenHeight = root.size.height / density
        val screenWidth = root.size.width / density
        val room = screenHeight - 2 * HomeTvDimens.SafeVertical.value - DIALOG_WINDOW_INSETS
        val height = dialog[3] - dialog[1]
        val width = dialog[2] - dialog[0]
        assertTrue("the dialog is $height dp tall; it must stay within $room dp", height <= room)
        assertTrue("the dialog is $width dp wide; the safe width is ${screenWidth - 2 * HomeTvDimens.SafeHorizontal.value} dp", width <= screenWidth - 2 * HomeTvDimens.SafeHorizontal.value)
    }

    @Test
    fun theWidestManualCaseFitsOnOneRow() {
        api.onRatingWrite = { _, _, _ -> throw httpError(500, "internal_error") }
        openFilm(myRating = 4.0)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("rating_retry")
        node("rating_error").assertExists()
        assertOneRowInsideTheDialog("rating_retry", "rating_cancel", "rating_remove")
    }

    @Test
    fun thePromptFailedCaseFitsOnOneRow() {
        api.onWatched = { WatchedResponse(1, RatingPrompt("film", 13, longTitle)) }
        api.onRatingWrite = { _, _, _ -> throw httpError(500, "internal_error") }
        openFilm(myRating = null)
        press(Key.DirectionRight)
        assertFocused("button_WATCHED")
        api.onFilm = { id -> FilmDetail(id = id, title = longTitle, match_status = "matched", media_files = listOf(F.file(1)), progress = Progress(watched = true)) }
        press(Key.DirectionCenter)
        assertFocused("rating_stars")
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("rating_retry")
        assertOneRowInsideTheDialog("rating_retry", "rating_not_now")
    }
}
