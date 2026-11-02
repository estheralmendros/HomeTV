package com.hometv.tv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.HomeResponse
import com.hometv.tv.core.api.Image
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.testing.UiTestPlatform
import com.hometv.tv.ui.detail.DetailText
import com.hometv.tv.ui.home.HERO_TEXT_SCRIM_ALPHA
import com.hometv.tv.ui.theme.HomeTvColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Full-bleed backdrop: the hero art is a screen-level layer, and the hero text stays readable. */
@RunWith(AndroidJUnit4::class)
class HomeHeroTest : UiTestBase() {
    private fun extent(tag: String): FloatArray {
        val node = node(tag).fetchSemanticsNode()
        val d = node.layoutInfo.density.density
        return floatArrayOf(node.positionInRoot.x / d, node.positionInRoot.y / d, (node.positionInRoot.x + node.size.width) / d, (node.positionInRoot.y + node.size.height) / d)
    }

    @Test
    fun theBackdropFillsTheTopRightBehindTheNavAndTheHero() {
        useLibrary()
        api.onHome = { HomeResponse(continue_watching = listOf(dune.copy(backdrop = Image(large = "/api/artwork/w1280/dune.jpg"), overview = "Una sinopsis."), olympics), recently_added = listOf(office, alien)) }
        launch()
        UiTestPlatform.advance(400)
        settle()
        val screen = rule.onAllNodes(isRoot()).fetchSemanticsNodes().first().let { it.size.width / it.layoutInfo.density.density }
        val (left, top, right, bottom) = extent("home_backdrop").toList()
        val heroBottom = extent("home_rows")[1]
        assertEquals("the backdrop starts at the screen top (behind the top nav)", 0f, top, 0.5f)
        assertTrue("the backdrop reaches below the hero strip ($bottom dp > $heroBottom dp)", bottom > heroBottom)
        assertEquals("the backdrop is anchored to the right edge", screen, right, 0.5f)
        assertTrue("the backdrop covers at least 60 % of the width (${right - left} of $screen dp)", right - left >= 0.6f * screen)

        // The overview sits over the art: the lighter secondary color (see theHeroTextIsReadableOverAnyArtwork).
        val overview = rule.onAllNodes(androidx.compose.ui.test.hasText("Una sinopsis."), useUnmergedTree = true).fetchSemanticsNodes().first()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        overview.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(DetailText.secondary, layouts.first().layoutInput.style.color)
    }

    @Test
    fun theHeroTextIsReadableOverAnyArtwork() {
        val worst = HomeTvColors.Background.copy(alpha = HERO_TEXT_SCRIM_ALPHA).compositeOver(Color.White)
        fun contrast(a: Color, b: Color): Double {
            val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
            return (light + 0.05) / (dark + 0.05)
        }
        assertTrue("title over a white backdrop: ${contrast(HomeTvColors.OnBackground, worst)}", contrast(HomeTvColors.OnBackground, worst) >= 4.5)
        assertTrue("overview over a white backdrop: ${contrast(DetailText.secondary, worst)}", contrast(DetailText.secondary, worst) >= 4.5)
    }

    private fun inHero(text: String) = rule.onAllNodes(
        androidx.compose.ui.test.hasText(text) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("hero")),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    private fun style(node: androidx.compose.ui.semantics.SemanticsNode): androidx.compose.ui.text.TextStyle {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style
    }

    @Test
    fun theYearIsASmallerGreySublabelBelowTheTitle() {
        useLibrary()
        launch()
        UiTestPlatform.advance(400)
        settle()
        // The focused card is Dune (2021): no "Dune · 2021" join.
        assertTrue("no 'Title · Year' join in the hero", inHero("Dune · 2021").isEmpty())
        val title = inHero("Dune").single()
        val year = inHero("2021").single()
        val d = title.layoutInfo.density.density
        assertTrue("the year sits below the title", year.positionInRoot.y >= title.positionInRoot.y + title.size.height - 1f)
        assertTrue("the year is left-aligned with the title", kotlin.math.abs(year.positionInRoot.x - title.positionInRoot.x) / d <= 1f)
        assertTrue("the year is smaller than the title", style(year).fontSize.value < style(title).fontSize.value)
        assertEquals("the year is light grey", HomeTvColors.Muted, style(year).color)
        assertTrue("the year is not bold", (style(year).fontWeight ?: androidx.compose.ui.text.font.FontWeight.Normal) <= androidx.compose.ui.text.font.FontWeight.Normal)
        // Muted is only readable on the plain background: the year must end left of the backdrop.
        val yearRight = (year.positionInRoot.x + year.size.width) / d
        val screen = rule.onAllNodes(isRoot()).fetchSemanticsNodes().first().let { it.size.width / it.layoutInfo.density.density }
        assertTrue("the year ends at $yearRight dp, over the backdrop (starts at ${screen * 0.32f} dp)", yearRight < screen * 0.32f)
    }
}
