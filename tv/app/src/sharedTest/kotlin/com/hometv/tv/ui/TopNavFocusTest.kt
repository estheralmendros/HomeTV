package com.hometv.tv.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.Profile
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.ui.components.NavTab
import com.hometv.tv.ui.theme.HomeTvColors
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tab focus: while the top nav has focus the pill follows the focused tab, and
 * every tab label stays readable (≥ 4.5:1 against what is behind it).
 */
@RunWith(AndroidJUnit4::class)
class TopNavFocusTest : UiTestBase() {
    /** The text tabs (Search is a lens icon without a label, see `SearchTabTest`). */
    private val labels = mapOf(NavTab.HOME to "Inicio", NavTab.FILMS to "Películas", NavTab.SHOWS to "Series", NavTab.WATCHLIST to "Mi lista", NavTab.ACTIVITY to "Actividad", NavTab.SETTINGS to "Ajustes")

    /** "Ajustes" is an admin-only tab: sign in as an admin so every label is on the bar. */
    private fun signInAsAdmin() {
        api.onMe = { Profile(5, "Esther", "avatar-05", is_admin = true) }
    }

    /** The color the tab's label was laid out with. */
    private fun labelColor(tab: NavTab): Color {
        val node = rule.onNode(hasText(labels.getValue(tab)) and hasAnyAncestor(hasTestTag("tab_${tab.name}")), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style.color
    }

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun assertReadable(tab: NavTab, on: Color, where: String) {
        val color = labelColor(tab)
        val ratio = contrast(color, on)
        assertTrue("'${labels[tab]}' ($where) is drawn in $color: contrast $ratio:1 (needs ≥ 4.5)", ratio >= 4.5)
    }

    @Test
    fun movingAlongTheBarMovesThePillAndKeepsEveryLabelReadable() {
        useLibrary()
        signInAsAdmin()
        launch()
        press(Key.DirectionUp)
        assertFocused("tab_HOME")
        press(Key.DirectionRight)
        assertFocused("tab_FILMS")

        node("tab_FILMS").assertIsSelected()
        node("tab_HOME").assertIsNotSelected()
        // The pill (TabRowDefaults.PillIndicator, active) is colorScheme.onSurface.
        assertReadable(NavTab.FILMS, on = HomeTvColors.OnBackground, where = "focused, on the pill")
        labels.keys.filter { it != NavTab.FILMS }.forEach { assertReadable(it, on = HomeTvColors.Background, where = "unfocused, bar focused") }
    }

    @Test
    fun leavingTheBarPutsThePillBackOnTheCurrentScreen() {
        useLibrary()
        signInAsAdmin()
        launch()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        node("tab_HOME").assertIsSelected()
        node("tab_FILMS").assertIsNotSelected()
    }

    @Test
    fun aCurrentTabThatIsNotOnTheBarFallsBackToInicio() {
        // Settings while the profile was just demoted: SETTINGS is the current screen but no longer a tab.
        rule.setContent {
            com.hometv.tv.ui.theme.HomeTvTheme {
                androidx.compose.foundation.layout.Column {
                    com.hometv.tv.ui.components.TopNav(
                        NavTab.SETTINGS, {}, null, {}, com.hometv.tv.ui.components.rememberFocusRequesters(), showSettings = false,
                    )
                    val content = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.size(100.dp).focusRequester(content).focusable().testTag("content"),
                    )
                    androidx.compose.runtime.LaunchedEffect(Unit) { content.requestFocus() }
                }
            }
        }
        settle()
        node("tab_HOME").assertIsSelected()
        node("tab_SEARCH").assertIsNotSelected()
        assertFocused("content")
        press(Key.DirectionUp)
        assertFocused("tab_HOME")
    }

    /** Vertical center of a node, unclipped, in dp. */
    private fun centerY(node: androidx.compose.ui.semantics.SemanticsNode): Float {
        val density = node.layoutInfo.density.density
        return (node.positionInRoot.y + node.size.height / 2f) / density
    }

    @Test
    fun everyTabsContentIsCenteredInThePillRow() {
        useLibrary()
        signInAsAdmin()
        launch()
        press(Key.DirectionUp)
        // The pill spans the TabRow's height, so each label (and the lens icon) must sit at its center.
        val row = centerY(node("nav_tabs").fetchSemanticsNode())
        val contents = labels.map { (tab, text) ->
            text to rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("tab_${tab.name}")), useUnmergedTree = true).fetchSemanticsNode()
        } + ("lens" to rule.onNode(androidx.compose.ui.test.hasContentDescription("Buscar") and hasAnyAncestor(hasTestTag("tab_SEARCH")), useUnmergedTree = true).fetchSemanticsNode())
        contents.forEach { (name, content) ->
            val offset = centerY(content) - row
            assertTrue("'$name' is ${"%.1f".format(offset)} dp off the center of the pill row", kotlin.math.abs(offset) <= 1f)
        }
    }

    /**
     * Fully on screen and inside the 48 dp horizontal safe area. Vertically the bar keeps its existing
     * place (12 dp padding, tabs from 14 dp: it predates this check), so only "on screen" is asserted there.
     */
    private fun assertTabFits(tab: NavTab) {
        val tag = "tab_${tab.name}"
        assertFullyVisible(tag)
        val node = node(tag).fetchSemanticsNode()
        val density = node.layoutInfo.density.density
        val screen = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().first().size.width / density
        val left = node.positionInRoot.x / density
        val right = left + node.size.width / density
        val margin = com.hometv.tv.ui.theme.HomeTvDimens.SafeHorizontal.value
        assertTrue("'$tag' spans $left..$right dp, outside the $margin dp side margins", left >= margin - 0.5f && right <= screen - margin + 0.5f)
    }

    @Test
    fun theAdminBarShowsSevenTabsInsideTheSafeAreaWithActividadAfterMiLista() {
        // Tab order: [🔍] [Inicio] [Películas] [Series] [Mi lista] [Actividad] [Ajustes].
        useLibrary()
        signInAsAdmin()
        launch()
        NavTab.entries.forEach(::assertTabFits)
        val lefts = NavTab.entries.map { node("tab_${it.name}").fetchSemanticsNode().positionInRoot.x }
        assertTrue("the tabs are laid out in enum order: $lefts", lefts.zipWithNext().all { (a, b) -> a < b })
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 2)
        assertFocused("tab_SHOWS")
        press(Key.DirectionRight)
        assertFocused("tab_WATCHLIST")
        assertReadable(NavTab.WATCHLIST, on = HomeTvColors.OnBackground, where = "focused, on the pill")
        press(Key.DirectionRight)
        assertFocused("tab_ACTIVITY")
        assertReadable(NavTab.ACTIVITY, on = HomeTvColors.OnBackground, where = "focused, on the pill")
        press(Key.DirectionRight)
        assertFocused("tab_SETTINGS")
    }

    @Test
    fun viewersSeeActividadAsTheLastTab() {
        useLibrary()
        launch()
        assertTrue(rule.onAllNodes(hasTestTag("tab_SETTINGS"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTabFits(NavTab.ACTIVITY)
    }
}
