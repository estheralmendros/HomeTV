package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.Genre
import com.hometv.tv.core.api.GenreList
import com.hometv.tv.testing.UiTestBase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The four Browse header buttons stay on one line, inside the safe area and clear of
 * the title, even with the longest labels. JVM only: it needs Robolectric's native graphics, which
 * measure text (legacy mode gives text no width); the whole JVM suite runs native
 * (`robolectric.graphicsMode` in `app/build.gradle.kts`), the annotation keeps it so on its own.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowseHeaderFitTest : UiTestBase() {
    /** Bounds of the first node with [tag], in dp: left, top, right, bottom. */
    private fun bounds(tag: String): List<Float> {
        val n = node(tag).fetchSemanticsNode()
        val d = n.layoutInfo.density.density
        return listOf(n.positionInRoot.x / d, n.positionInRoot.y / d, (n.positionInRoot.x + n.size.width) / d, (n.positionInRoot.y + n.size.height) / d)
    }

    @Test
    fun theFourButtonsStayOnOneLineWithTheLongestLabels() {
        useLibrary()
        api.onGenres = { GenreList(listOf(Genre(878, "Ciencia ficción", 3))) }
        launch()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("grid_0")
        val oneLine = bounds("browse_sort").let { it[3] - it[1] }
        // "Añadidos recientemente", "Ciencia ficción", "Antes de 1950", "No vistas".
        press(Key.DirectionUp)
        press(Key.DirectionCenter)
        press(Key.DirectionDown, times = 4)
        press(Key.DirectionCenter)
        // Each pick reloads and focuses the first card; Up returns to "Ordenar".
        listOf(Triple("browse_genre", 1, 1), Triple("browse_decade", 2, 9), Triple("browse_watched", 3, 2)).forEach { (tag, rights, entry) ->
            press(Key.DirectionUp)
            press(Key.DirectionRight, times = rights)
            assertFocused(tag)
            press(Key.DirectionCenter)
            press(Key.DirectionDown, times = entry)
            press(Key.DirectionCenter)
        }
        rule.onNodeWithText("Estado: No vistas", useUnmergedTree = true).assertIsDisplayed()
        val root = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().first()
        val width = root.size.width / root.layoutInfo.density.density
        val margin = com.hometv.tv.ui.theme.HomeTvDimens.SafeHorizontal.value
        val title = rule.onAllNodes(androidx.compose.ui.test.hasText("Películas · ", substring = true), useUnmergedTree = true).fetchSemanticsNodes().first().let {
            val d = it.layoutInfo.density.density
            listOf(it.positionInRoot.x / d, it.positionInRoot.y / d, (it.positionInRoot.x + it.size.width) / d, (it.positionInRoot.y + it.size.height) / d)
        }
        press(Key.DirectionUp)
        listOf("browse_sort", "browse_genre", "browse_decade", "browse_watched").forEachIndexed { i, tag ->
            if (i > 0) press(Key.DirectionRight)
            assertFocused(tag)
            val b = bounds(tag)
            assertTrue("'$tag' wraps: ${b[3] - b[1]} dp tall, one line is $oneLine dp", kotlin.math.abs((b[3] - b[1]) - oneLine) <= 1f)
            assertTrue("focused '$tag' spans x ${b[0]}..${b[2]} dp, outside the $margin dp safe area of a $width dp screen", b[0] >= margin - 1f && b[2] <= width - margin + 1f)
            val overlaps = b[0] < title[2] && title[0] < b[2] && b[1] < title[3] && title[1] < b[3]
            assertTrue("'$tag' $b overlaps the title $title", !overlaps)
        }
    }

}
