package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.api.WatchlistEntry
import com.hometv.tv.testing.UiTestBase
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Back table and D-pad paths of the "Mi lista" tab and screen with the D-pad. */
@RunWith(AndroidJUnit4::class)
class WatchlistFocusTest : UiTestBase() {
    /** The server's list, newest first; writes from the detail screens change it. */
    private val server: MutableList<WatchlistEntry> = Collections.synchronizedList(mutableListOf())

    /** The titles the profile has watched (server side), by summary key ("film:12"). */
    private val seen: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private fun film(id: Long) = WatchlistEntry("film", FilmSummary(id, "Film $id", 2000), "2026-10-27T10:00:00Z")

    private fun show(id: Long) = WatchlistEntry("show", ShowSummary(id, "Show $id", 2005, unwatched_episode_count = 3), "2026-10-27T10:00:00Z")

    private fun useWatchlist(vararg entries: WatchlistEntry) {
        useLibrary()
        server += entries
        api.onWatchlist = { type, _, page, size, watched ->
            val filtered = server.toList()
                .filter { type == "all" || (type == "films" && it.kind == "film") || (type == "shows" && it.kind == "show") }
                .filter { watched == null || (it.item.key in seen) == watched }
            Page(filtered.drop((page - 1) * size).take(size), page, size, filtered.size)
        }
        api.onWatchlistWrite = { segment, id, inList ->
            val kind = if (segment == "films") "film" else "show"
            server.removeAll { it.kind == kind && it.item.id == id }
            if (inList) server.add(0, if (kind == "film") film(id) else show(id))
        }
        val films = api.onFilm
        api.onFilm = { id -> films(id).copy(in_watchlist = server.any { it.kind == "film" && it.item.id == id }) }
        val shows = api.onShow
        api.onShow = { id -> shows(id).copy(in_watchlist = server.any { it.kind == "show" && it.item.id == id }) }
    }

    private fun listCalls() = api.calls.count { it.startsWith("GET /api/watchlist") }

    /** Home → Up → Right to "Mi lista" → OK. */
    private fun openWatchlist() {
        launch()
        press(Key.DirectionUp)
        assertFocused("tab_HOME")
        press(Key.DirectionRight, times = 2)
        assertFocused("tab_SHOWS")
        press(Key.DirectionRight)
        assertFocused("tab_WATCHLIST")
        press(Key.DirectionCenter)
        node("watchlist_header").assertIsDisplayed()
    }

    private fun gone(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    private fun assertCardTitle(tag: String, title: String) {
        rule.onNode(hasText(title) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertExists()
    }

    /** Stop and resume the activity (a return from the background): `ON_STOP`, then `ON_RESUME` again. */
    private fun stopAndResume() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        settle()
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
    }

    @Test
    fun theTabOpensTheListOnTheFirstCardAndBackReturnsToTheTab() {
        useWatchlist(film(12), show(7), film(13))
        openWatchlist()
        assertFocused("grid_0")
        rule.onNodeWithText("Mi lista · 3", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1, api.count("GET /api/watchlist?type=all&sort=added&page=1&page_size=50"))
        assertCardTitle("grid_1", "Show 7")
        back()
        assertFocused("tab_WATCHLIST")
    }

    @Test
    fun backFromADetailReturnsToTheCardThatOpenedIt() {
        useWatchlist(film(12), film(13), film(14))
        openWatchlist()
        press(Key.DirectionRight)
        assertFocused("grid_1")
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
        back()
        assertFocused("grid_1")
        assertEquals("the list reloads on resume", 2, listCalls())
    }

    @Test
    fun aTitleRemovedOnTheDetailLeavesFocusOnTheCardNowAtThatIndex() {
        useWatchlist(film(12), film(13), film(14))
        openWatchlist()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
        press(Key.DirectionRight, times = 2)
        assertFocused("button_WATCHLIST")
        press(Key.DirectionCenter)
        assertEquals(1, api.count("DELETE /api/watchlist/films/13"))
        back()
        assertFocused("grid_1")
        assertCardTitle("grid_1", "Film 14")
        assertTrue(gone("grid_2"))
    }

    @Test
    fun upFromTheFirstRowReachesOrdenarAndUpFromTheHeaderTheTopNav() {
        useWatchlist(film(12), film(13))
        openWatchlist()
        press(Key.DirectionUp)
        assertFocused("watchlist_sort")
        rule.onNodeWithText("Ordenar: Añadidos recientemente", useUnmergedTree = true).assertIsDisplayed()
        press(Key.DirectionRight)
        assertFocused("watchlist_type")
        rule.onNodeWithText("Tipo: Todo", useUnmergedTree = true).assertIsDisplayed()
        press(Key.DirectionUp)
        assertFocused("tab_WATCHLIST")
        // Down from the bar is geometric (as from any tab): it lands on a header button.
        press(Key.DirectionDown)
        val focused = focusedTags()
        assertTrue(
            "Down from the tab focuses a header button, not $focused",
            focused.any { it.startsWith("watchlist_sort") || it.startsWith("watchlist_type") || it.startsWith("watchlist_watched") },
        )
        press(Key.DirectionDown)
        assertTrue("Down from the header reaches the first row, not ${focusedTags()}", focusedTags().any { it.startsWith("grid_0") || it.startsWith("grid_1") })
    }

    @Test
    fun aTypeWithoutResultsOffersVerTodoBelowTheHeader() {
        useWatchlist(film(12), film(13))
        openWatchlist()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        assertFocused("watchlist_type")
        press(Key.DirectionCenter)
        assertFocused("dialog_item_0")
        rule.onNodeWithText("Todo", useUnmergedTree = true).assertIsDisplayed()
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        assertTrue(api.calls.contains("GET /api/watchlist?type=shows&sort=added&page=1&page_size=50"))
        rule.onNode(hasTestTag("watchlist_no_matches") and hasText("No hay series en tu lista."), useUnmergedTree = true).assertIsDisplayed()
        assertFocused("watchlist_type")
        rule.onNodeWithText("Tipo: Series", useUnmergedTree = true).assertIsDisplayed()
        press(Key.DirectionDown)
        assertFocused("watchlist_show_all")
        press(Key.DirectionCenter)
        assertFocused("grid_0")
        rule.onNodeWithText("Tipo: Todo", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun estadoFollowsTipoInTheHeaderAndAPickFiltersTheListThenUpFromTheFirstRowReachesOrdenar() {
        useWatchlist(film(12), show(7), film(13))
        seen += "film:12"
        openWatchlist()
        press(Key.DirectionUp)
        assertFocused("watchlist_sort")
        press(Key.DirectionRight, times = 2)
        assertFocused("watchlist_watched")
        rule.onNodeWithText("Estado: Todos", useUnmergedTree = true).assertIsDisplayed()
        press(Key.DirectionLeft)
        assertFocused("watchlist_type")
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("dialog_item_0")
        rule.onNodeWithText("Estado", useUnmergedTree = true).assertIsDisplayed()
        listOf("Todos", "Vistos", "No vistos").forEach { rule.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed() }
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        assertEquals(1, api.count("GET /api/watchlist?type=all&sort=added&page=1&page_size=50&watched=false"))
        assertFocused("grid_0")
        assertCardTitle("grid_0", "Show 7")
        assertCardTitle("grid_1", "Film 13")
        assertTrue(gone("grid_2"))
        rule.onNodeWithText("Mi lista · 2", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Estado: No vistos", useUnmergedTree = true).assertIsDisplayed()
        press(Key.DirectionUp)
        assertFocused("watchlist_sort")
    }

    @Test
    fun theThreeHeaderButtonsFitTheScreenWithTheirLongestValues() {
        useWatchlist(film(12))
        seen += "film:12"
        openWatchlist()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter) // Tipo: Películas
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 2)
        assertFocused("watchlist_watched")
        press(Key.DirectionCenter)
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter) // Estado: No vistos (no results)
        rule.onNodeWithText("Tipo: Películas", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Estado: No vistos", useUnmergedTree = true).assertIsDisplayed()
        listOf("watchlist_sort", "watchlist_type", "watchlist_watched", "watchlist_no_matches", "watchlist_show_all").forEach(::assertInsideSafeArea)
    }

    @Test
    fun anEstadoWithoutResultsShowsTheFiltersTextAndVerTodoClearsTipoAndEstado() {
        useWatchlist(film(12), show(7))
        openWatchlist()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter) // Tipo: Películas
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 2)
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter) // Estado: Vistos
        assertTrue(api.calls.contains("GET /api/watchlist?type=films&sort=added&page=1&page_size=50&watched=true"))
        rule.onNode(
            hasTestTag("watchlist_no_matches") and hasText("Ningún título de tu lista coincide con los filtros."),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        assertFocused("watchlist_watched")
        press(Key.DirectionDown)
        assertFocused("watchlist_show_all")
        press(Key.DirectionCenter)
        assertFocused("grid_0")
        assertEquals("GET /api/watchlist?type=all&sort=added&page=1&page_size=50", api.calls.last { it.startsWith("GET /api/watchlist") })
        rule.onNodeWithText("Tipo: Todo", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Estado: Todos", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aDialogClosedWithBackChangesNothingAndReturnsToItsButton() {
        useWatchlist(film(12))
        openWatchlist()
        press(Key.DirectionUp)
        press(Key.DirectionCenter)
        assertFocused("dialog_item_0")
        back()
        assertTrue(gone("list_dialog"))
        assertFocused("watchlist_sort")
        assertEquals(1, listCalls())
    }

    @Test
    fun anEmptyListShowsItsTextWithFocusOnOrdenar() {
        useWatchlist()
        openWatchlist()
        rule.onNode(hasTestTag("watchlist_empty") and hasText("Tu lista está vacía. Añade películas y series desde su ficha con «Mi lista»."), useUnmergedTree = true)
            .assertIsDisplayed()
        assertFocused("watchlist_sort")
    }

    @Test
    fun okThenBackAtOnceStillWritesAndTheListIsReadOnlyAfterTheWrite() {
        useWatchlist(film(12), film(13))
        val gate = CompletableDeferred<Unit>()
        val write = api.onWatchlistWrite
        api.onWatchlistWrite = { segment, id, inList ->
            gate.await()
            write(segment, id, inList)
        }
        openWatchlist()
        press(Key.DirectionCenter)
        press(Key.DirectionRight, times = 2)
        assertFocused("button_WATCHLIST")
        press(Key.DirectionCenter)
        back()
        assertEquals(1, api.count("DELETE /api/watchlist/films/12"))
        assertEquals("no list GET while the write is in flight", 1, listCalls())
        assertCardTitle("grid_0", "Film 12")
        rule.runOnUiThread { gate.complete(Unit) }
        settle()
        assertEquals(2, listCalls())
        assertCardTitle("grid_0", "Film 13")
        assertTrue(gone("grid_1"))
        assertFocused("grid_0")
    }

    @Test
    fun removingTheOnlyCardLeavesTheEmptyTextWithFocusOnOrdenar() {
        useWatchlist(film(12))
        openWatchlist()
        press(Key.DirectionCenter)
        press(Key.DirectionRight, times = 2)
        press(Key.DirectionCenter)
        back()
        node("watchlist_empty").assertIsDisplayed()
        assertFocused("watchlist_sort")
    }

    @Test
    fun removingTheOnlyMatchingCardLeavesTheFilteredTextWithFocusOnVerTodo() {
        useWatchlist(film(12), show(7))
        openWatchlist()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        assertFocused("grid_0")
        assertCardTitle("grid_0", "Show 7")
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
        press(Key.DirectionRight, times = 3)
        assertFocused("button_WATCHLIST")
        press(Key.DirectionCenter)
        assertEquals(1, api.count("DELETE /api/watchlist/shows/7"))
        back()
        node("watchlist_no_matches").assertIsDisplayed()
        assertFocused("watchlist_show_all")
    }

    @Test
    fun verTodoOnAListThatTurnedOutEmptyFocusesOrdenar() {
        useWatchlist(film(12))
        openWatchlist()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        node("watchlist_no_matches").assertIsDisplayed()
        press(Key.DirectionDown)
        assertFocused("watchlist_show_all")
        // The only film was removed elsewhere (web, another TV).
        server.clear()
        press(Key.DirectionCenter)
        node("watchlist_empty").assertIsDisplayed()
        assertFocused("watchlist_sort")
    }

    @Test
    fun aResumeWithFocusOnTheHeaderReloadsWithoutMovingFocus() {
        useWatchlist(film(12), film(13))
        openWatchlist()
        press(Key.DirectionUp)
        assertFocused("watchlist_sort")
        server.removeAt(0)
        stopAndResume()
        assertEquals(2, listCalls())
        assertCardTitle("grid_0", "Film 13")
        assertFocused("watchlist_sort")
    }

    @Test
    fun aResumeKeepsFocusOnTheSameTitleWhenItWasFocusedButNotOpened() {
        useWatchlist(film(12), film(13), film(14), film(15))
        openWatchlist()
        press(Key.DirectionRight, times = 2)
        assertFocused("grid_2")
        assertCardTitle("grid_2", "Film 14")
        server.removeAt(0)
        stopAndResume()
        assertEquals(2, listCalls())
        assertFocused("grid_1")
        assertCardTitle("grid_1", "Film 14")
    }

    @Test
    fun aResumeThatFillsAnEmptyListLeavesFocusOnTheHeader() {
        useWatchlist()
        openWatchlist()
        assertFocused("watchlist_sort")
        // A title added on the web while "Ordenar" had focus.
        server += film(12)
        stopAndResume()
        node("grid_0").assertIsDisplayed()
        assertFocused("watchlist_sort")
    }
}
