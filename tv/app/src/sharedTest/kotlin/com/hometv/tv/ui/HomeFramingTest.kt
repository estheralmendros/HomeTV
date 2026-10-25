package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.ui.theme.HomeTvDimens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * prd3 §3 step 10 (row framing): the focused Home row is pinned to the top of the rows area (no part
 * of an earlier row stays visible under the hero), and the whole row fits above the 27 dp bottom
 * safe area. Robolectric screen: 960×540 dp.
 */
@RunWith(AndroidJUnit4::class)
class HomeFramingTest : UiTestBase() {
    private companion object {
        val ROWS = listOf("continue", "recent", "films", "shows")
    }

    /** Unclipped vertical extent of a tagged node, in dp. */
    private fun extent(tag: String): ClosedFloatingPointRange<Float> {
        val node = node(tag).fetchSemanticsNode()
        val density = node.layoutInfo.density.density
        val top = node.positionInRoot.y / density
        return top..(top + node.size.height / density)
    }

    private val screenBottom get() = rule.onRoot().fetchSemanticsNode().size.height / rule.density.density

    private fun assertFramed(focused: String, above: List<String>) {
        val viewport = extent("home_rows")
        val row = extent("home_row_$focused")
        assertEquals("row '$focused' is pinned to the top of the rows area", viewport.start, row.start, 1f)
        above.forEach { earlier ->
            val previous = extent("home_row_$earlier")
            assertTrue("row '$earlier' (above '$focused') still shows ${previous.endInclusive - viewport.start} dp inside the rows area", previous.endInclusive <= viewport.start + 0.5f)
        }
        val limit = screenBottom - HomeTvDimens.SafeVertical.value
        assertTrue("row '$focused' ends at ${row.endInclusive} dp, below the safe area ($limit dp)", row.endInclusive <= limit + 0.5f)
        ROWS.forEach { id ->
            val visibleBottom = visibleBottom("home_row_$id")
            assertTrue("row '$id' is visible down to $visibleBottom dp, inside the bottom safe area (> $limit dp)", visibleBottom <= limit + 0.5f)
        }
    }

    /** Bottom of the visible (clipped) part of a tagged node, in dp; 0 when nothing of it is visible. */
    private fun visibleBottom(tag: String): Float {
        val node = node(tag).fetchSemanticsNode()
        val bounds = node.boundsInRoot
        return if (bounds.height <= 0f) 0f else bounds.bottom / node.layoutInfo.density.density
    }

    @Test
    fun eachFocusedRowIsPinnedToTheTopAndFitsAboveTheSafeArea() {
        useLibrary()
        launch()
        // Rows: Seguir viendo (landscape), Añadidos recientemente, Películas, Series (posters).
        assertFramed("continue", above = emptyList())
        press(Key.DirectionDown)
        assertFramed("recent", above = listOf("continue"))
        press(Key.DirectionDown)
        assertFramed("films", above = listOf("continue", "recent"))
        press(Key.DirectionDown)
        assertFramed("shows", above = listOf("continue", "recent", "films"))
    }
}
