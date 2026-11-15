package com.hometv.tv.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.ui.theme.HomeTvDimens
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native graphics, real text metrics: the exit dialog and its [Salir] [Cancelar] row fit inside the
 * dialog's content and the TV safe area (the `RatingDialogFitTest` approach: dialog nodes sit in the
 * dialog window, so they are checked against `confirm_dialog`'s own bounds).
 */
@RunWith(AndroidJUnit4::class)
class HomeExitDialogFitTest : UiTestBase() {
    @Test
    fun theDialogAndBothButtonsFitOnOneRow() {
        useLibrary()
        launch()
        back()
        val dialog = dpBounds("confirm_dialog")
        val tags = listOf("confirm_dialog_confirm", "confirm_dialog_cancel")
        val tops = tags.map { dpBounds(it)[1] }
        tags.forEach { tag ->
            val b = dpBounds(tag)
            assertTrue("'$tag' ends at ${b[2]} dp, past the dialog's content edge", b[2] <= dialog[2] - DIALOG_PADDING + 0.5f)
            assertTrue("'$tag' starts at ${b[0]} dp, left of the dialog's content", b[0] >= dialog[0] + DIALOG_PADDING - 0.5f)
            assertTrue("'$tag' ends at ${b[3]} dp, below the dialog's content", b[3] <= dialog[3] - DIALOG_PADDING + 0.5f)
            assertTrue("'$tag' is one line (${b[3] - b[1]} dp tall)", b[3] - b[1] <= 48.5f)
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
    fun theTitleAndTheButtonRowAreCentred() {
        useLibrary()
        launch()
        back()
        val dialog = dpBounds("confirm_dialog")
        val centre = (dialog[0] + dialog[2]) / 2
        val title = rule.onNodeWithText("¿Salir de la aplicación?").fetchSemanticsNode()
        val d = title.layoutInfo.density.density
        val titleCentre = (title.positionInRoot.x + title.size.width / 2f) / d
        val rowCentre = (dpBounds("confirm_dialog_confirm")[0] + dpBounds("confirm_dialog_cancel")[2]) / 2
        assertTrue("the title is centred at $titleCentre dp; the dialog at $centre dp", kotlin.math.abs(titleCentre - centre) < 0.5f)
        assertTrue("the button row is centred at $rowCentre dp; the dialog at $centre dp", kotlin.math.abs(rowCentre - centre) < 0.5f)
    }
}
