package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.Genre
import com.hometv.tv.core.api.GenreList
import com.hometv.tv.testing.UiTestBase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** prd3 §4 step 2: the sort, genre, decade and watched buttons open list dialogs that are usable with the D-pad. */
@RunWith(AndroidJUnit4::class)
class BrowseFiltersFocusTest : UiTestBase() {
    private fun openFilms() {
        useLibrary()
        api.onGenres = { GenreList(listOf(Genre(18, "Drama", 3), Genre(35, "Comedia", 1))) }
        launch()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("grid_0")
    }

    @Test
    fun aGenrePickedFromTheListFiltersTheGridAndFocusesTheFirstCard() {
        openFilms()
        // Up from the first row reaches the header buttons (not the top nav), starting at "Ordenar".
        press(Key.DirectionUp)
        assertFocused("browse_sort")
        press(Key.DirectionRight)
        assertFocused("browse_genre")
        rule.onNodeWithText("Género: Todos", useUnmergedTree = true).assertIsDisplayed()
        press(Key.DirectionCenter)
        node("list_dialog").assertIsDisplayed()
        assertFocused("dialog_item_0")
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertTrue(api.calls.any { it.startsWith("GET /api/films?") && it.endsWith("&genre=18") })
        rule.onNodeWithText("Género: Drama", useUnmergedTree = true).assertIsDisplayed()
        assertFocused("grid_0")
    }

    @Test
    fun backOnADialogKeepsTheChoiceAndReturnsToItsButton() {
        openFilms()
        press(Key.DirectionUp)
        assertFocused("browse_sort")
        press(Key.DirectionCenter)
        assertFocused("dialog_item_0")
        back()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("list_dialog")).fetchSemanticsNodes().isEmpty())
        assertFocused("browse_sort")
        rule.onNodeWithText("Ordenar: Título A–Z", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aLongListFitsTheScreenAndScrollsToItsLastEntry() {
        openFilms()
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 2)
        assertFocused("browse_decade")
        // The year filter is labelled "Años" (button, dialog title and its "Todos" entry).
        rule.onNodeWithText("Años: Todos", useUnmergedTree = true).assertIsDisplayed()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Años", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("dialog_item_0").assertTextEquals("Todos")
        val dialog = node("list_dialog").fetchSemanticsNode()
        val d = dialog.layoutInfo.density.density
        val screen = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().first().size.height / d
        // Like the PIN dialog: room for a TV's dialog-window insets (~80 dp) inside the 27 dp safe area.
        val room = screen - 2 * com.hometv.tv.ui.theme.HomeTvDimens.SafeVertical.value - 80f
        assertTrue("the dialog is ${dialog.size.height / d} dp tall; it must stay within $room dp", dialog.size.height / d <= room)
        // "Todos", 8 decades, "Antes de 1950": the last entry is reachable and scrolled into the dialog.
        press(Key.DirectionDown, times = 9)
        assertFocused("dialog_item_9")
        val last = node("dialog_item_9").fetchSemanticsNode()
        val top = dialog.positionInRoot.y
        assertTrue("the last entry is inside the dialog", last.positionInRoot.y >= top && last.positionInRoot.y + last.size.height <= top + dialog.size.height + 1f)
    }

    @Test
    fun theWatchedFilterIsTheFourthButtonAndSendsWatched() {
        openFilms()
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 3)
        assertFocused("browse_watched")
        rule.onNodeWithText("Estado: Todas", useUnmergedTree = true).assertIsDisplayed()
        press(Key.DirectionCenter)
        rule.onNodeWithText("Estado", useUnmergedTree = true).assertIsDisplayed()
        assertFocused("dialog_item_0")
        rule.onNodeWithTag("dialog_item_2").assertTextEquals("No vistas")
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        assertTrue(api.calls.any { it.startsWith("GET /api/films?") && it.endsWith("&watched=false") })
        rule.onNodeWithText("Estado: No vistas", useUnmergedTree = true).assertIsDisplayed()
        assertFocused("grid_0")
    }

    @Test
    fun filtersSurviveADetailButResetWhenLeavingTheScreen() {
        openFilms()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        rule.onNodeWithText("Género: Drama", useUnmergedTree = true).assertIsDisplayed()
        // Into a film and Back: same screen, the filter stays.
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
        back()
        rule.onNodeWithText("Género: Drama", useUnmergedTree = true).assertIsDisplayed()
        // Leaving for Home and coming back: a new screen, the filter is reset.
        back()
        assertFocused("tab_FILMS")
        press(Key.DirectionCenter)
        rule.onNodeWithText("Género: Todos", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(api.calls.last { it.startsWith("GET /api/films?") }.let { !it.contains("genre=") })
    }
}
