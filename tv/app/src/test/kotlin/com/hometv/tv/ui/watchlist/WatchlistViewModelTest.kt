package com.hometv.tv.ui.watchlist

import com.hometv.tv.R
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.api.WatchlistEntry
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.data.WatchlistKind
import com.hometv.tv.core.data.WatchlistSort
import com.hometv.tv.core.data.WatchlistType
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** watchlist prd3 FR-T9: paging as Browse, sort/type dialogs, empty states, and the reload in place on resume. */
class WatchlistViewModelTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    private val repos = TestRepos(appScope = CoroutineScope(SupervisorJob() + main.dispatcher))
    private val memory = WatchlistMemory()

    /** The server's list, newest first: films 1..n (odd ids are shows when [mixed]). */
    private var entries: List<WatchlistEntry> = list(12)

    /** When set, every list request waits for it (read at request time). */
    private var gate: CompletableDeferred<Unit>? = null
    private var failNext = false

    private fun list(n: Int, mixed: Boolean = true, from: Long = 1): List<WatchlistEntry> = (from until from + n).map { id ->
        if (mixed && id % 2 == 1L) WatchlistEntry("show", ShowSummary(id, "Show $id", 2000), "2026-10-27T10:00:00Z")
        else WatchlistEntry("film", FilmSummary(id, "Film $id", 2000), "2026-10-27T10:00:00Z")
    }

    init {
        repos.api.onWatchlist = { type, _, page, size ->
            gate?.await()
            if (failNext) {
                failNext = false
                throw java.net.ConnectException("refused")
            }
            val filtered = entries.filter { type == "all" || (type == "films" && it.kind == "film") || (type == "shows" && it.kind == "show") }
            Page(filtered.drop((page - 1) * size).take(size), page, size, filtered.size)
        }
    }

    /** As the screen does: its first `ON_RESUME` comes right after the ViewModel was created (no reload: never hidden). */
    private fun vm() = WatchlistViewModel(repos.watchlist, repos.store, memory).also { it.onVisible() }

    /** Back from a detail screen (or a return from the background): the entry was stopped, then resumed. */
    private fun WatchlistViewModel.resume() {
        onHidden()
        onVisible()
    }

    /** A new screen with [type] picked in the "Tipo" dialog (the type is not remembered across screens). */
    private fun TestScope.vm(type: WatchlistType) = vm().also {
        advanceUntilIdle()
        it.openTypeDialog()
        it.onDialogSelect(WatchlistType.entries.indexOf(type))
        advanceUntilIdle()
    }

    private fun calls() = repos.api.calls.filter { it.startsWith("GET /api/watchlist") }

    private fun WatchlistViewModel.keys() = state.value.items.map { it.key }

    @Test
    fun `the first page is newest added, all types, 50 per page, and the header shows the total`() = runTest(main.dispatcher) {
        entries = list(1234)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("GET /api/watchlist?type=all&sort=added&page=1&page_size=50"), calls())
        val state = vm.state.value
        assertEquals(50, state.items.size)
        assertEquals(listOf("show:1", "film:2"), vm.keys().take(2), "films and shows mixed")
        assertEquals(UiText.Res(R.string.pair_dot, listOf(UiText.Res(R.string.watchlist_title), "1.234")), state.header)
        assertEquals(UiText.Res(R.string.browse_sort, listOf(UiText.Res(R.string.sort_added_desc))), state.sortLabel)
        assertEquals(UiText.Res(R.string.watchlist_type, listOf(UiText.Res(R.string.watchlist_type_all))), state.typeLabel)
        assertNull(state.focus, "the screen focuses restoreIndex() on its own")
    }

    @Test
    fun `the next page loads when focus reaches the last two loaded rows`() = runTest(main.dispatcher) {
        entries = list(120)
        val vm = vm()
        advanceUntilIdle()
        vm.onFocused(41)
        advanceUntilIdle()
        assertEquals(1, calls().size)
        vm.onFocused(42)
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=2&page_size=50", calls().last())
        assertEquals(100, vm.state.value.items.size)
        vm.onCardClick(vm.state.value.items[5])
        assertEquals(NavRequest.Detail("film", 6), vm.navigation.first())
    }

    @Test
    fun `the sort dialog lists the three orders and a new pick reloads, focuses the first card and is remembered`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.openSortDialog()
        val dialog = vm.state.value.dialog!!
        assertEquals(WatchlistDialogKind.SORT, dialog.kind)
        assertEquals(UiText.Res(R.string.dialog_sort), dialog.title)
        assertEquals(listOf(R.string.sort_added_desc, R.string.sort_title_asc, R.string.sort_year_desc).map { UiText.Res(it) }, dialog.items)
        assertEquals(0, dialog.selected)
        vm.onDialogSelect(0)
        advanceUntilIdle()
        assertNull(vm.state.value.dialog)
        assertEquals(1, calls().size, "the current entry only closes the dialog")

        vm.onFocused(5)
        vm.openSortDialog()
        vm.onDialogSelect(1)
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=title&page=1&page_size=50", calls().last())
        assertEquals(WatchlistSort.TITLE, memory.sort)
        assertEquals(UiText.Res(R.string.browse_sort, listOf(UiText.Res(R.string.sort_title_asc))), vm.state.value.sortLabel)
        assertEquals(0, vm.state.value.focus!!.index)
        vm.openSortDialog()
        assertEquals(1, vm.state.value.dialog!!.selected)
        vm.onDialogDismiss()
        assertNull(vm.state.value.dialog)

        // Remembered for the app session: the next screen starts with it.
        val next = vm()
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=title&page=1&page_size=50", calls().last())
        assertEquals(WatchlistSort.TITLE, next.state.value.sort)
    }

    @Test
    fun `the type dialog offers Todo, Peliculas and Series and a pick sends the type`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.openTypeDialog()
        val dialog = vm.state.value.dialog!!
        assertEquals(WatchlistDialogKind.TYPE, dialog.kind)
        assertEquals(UiText.Res(R.string.dialog_type), dialog.title)
        assertEquals(listOf(R.string.watchlist_type_all, R.string.watchlist_type_films, R.string.watchlist_type_shows).map { UiText.Res(it) }, dialog.items)
        assertEquals(0, dialog.selected)
        vm.onDialogSelect(2)
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=shows&sort=added&page=1&page_size=50", calls().last())
        assertEquals(WatchlistType.SHOWS, vm.state.value.type)
        assertTrue(vm.keys().all { it.startsWith("show:") })
        assertEquals(UiText.Res(R.string.watchlist_type, listOf(UiText.Res(R.string.watchlist_type_shows))), vm.state.value.typeLabel)
    }

    @Test
    fun `a new screen starts at Todo while the sort is remembered for the app session, like Browse`() = runTest(main.dispatcher) {
        val first = vm()
        advanceUntilIdle()
        first.openTypeDialog()
        first.onDialogSelect(2)
        first.openSortDialog()
        first.onDialogSelect(1)
        advanceUntilIdle()
        assertEquals(WatchlistType.SHOWS, first.state.value.type)

        // The screen is left (Back to Home, another tab) and the "Mi lista" tab opened again.
        val again = vm()
        advanceUntilIdle()
        assertEquals(WatchlistType.ALL, again.state.value.type)
        assertEquals(WatchlistSort.TITLE, again.state.value.sort)
        assertEquals("GET /api/watchlist?type=all&sort=title&page=1&page_size=50", calls().last())
    }

    @Test
    fun `an empty list is empty, a type with no results is noMatches with its own text`() = runTest(main.dispatcher) {
        entries = emptyList()
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value.empty)
        assertFalse(vm.state.value.noMatches)
        assertEquals(1, vm.state.value.headerFocusVersion, "the first load with no card asks for the header")

        entries = list(3, mixed = false)
        val shows = vm(WatchlistType.SHOWS)
        assertFalse(shows.state.value.empty)
        assertTrue(shows.state.value.noMatches)
        assertEquals(UiText.Res(R.string.watchlist_no_shows), shows.state.value.noMatchesText)
        entries = list(2, mixed = true).filter { it.kind == "show" }
        val films = vm(WatchlistType.FILMS)
        assertEquals(UiText.Res(R.string.watchlist_no_films), films.state.value.noMatchesText)
    }

    @Test
    fun `a dialog pick with no results keeps focus on its opener, without a header request`() = runTest(main.dispatcher) {
        entries = list(3, mixed = false)
        val vm = vm()
        advanceUntilIdle()
        val before = vm.state.value
        vm.openTypeDialog()
        vm.onDialogSelect(2)
        advanceUntilIdle()
        assertTrue(vm.state.value.noMatches)
        assertEquals(before.headerFocusVersion, vm.state.value.headerFocusVersion)
        assertEquals(before.focus, vm.state.value.focus)
    }

    @Test
    fun `Ver todo shows everything and focuses the first card, or the header when the whole list is empty`() = runTest(main.dispatcher) {
        entries = list(3, mixed = false)
        val vm = vm(WatchlistType.SHOWS)
        assertTrue(vm.state.value.noMatches)
        val version = vm.state.value.headerFocusVersion
        vm.showAll()
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=1&page_size=50", calls().last())
        assertEquals(WatchlistType.ALL, vm.state.value.type)
        assertEquals(3, vm.state.value.items.size)
        assertEquals(0, vm.state.value.focus!!.index)
        assertEquals(version, vm.state.value.headerFocusVersion)

        val other = vm(WatchlistType.SHOWS)
        entries = emptyList()
        val before = other.state.value.headerFocusVersion
        other.showAll()
        advanceUntilIdle()
        assertTrue(other.state.value.empty)
        assertNull(other.state.value.focus)
        assertEquals(before + 1, other.state.value.headerFocusVersion)
    }

    @Test
    fun `a failed first page is a screen error, and a retry with no items asks for the header`() = runTest(main.dispatcher) {
        failNext = true
        entries = emptyList()
        val vm = vm()
        advanceUntilIdle()
        assertEquals(ScreenError.Connection("hometv.tail1234.ts.net", false), vm.state.value.error)
        assertFalse(vm.state.value.pageFailed)
        assertFalse(vm.state.value.empty)
        val version = vm.state.value.headerFocusVersion
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.empty)
        assertEquals(version + 1, vm.state.value.headerFocusVersion)
    }

    @Test
    fun `onVisible is ignored before the first page arrived, and during a running page-1 load or reload`() = runTest(main.dispatcher) {
        gate = CompletableDeferred()
        val vm = vm()
        runCurrent()
        vm.resume()
        runCurrent()
        assertEquals(1, calls().size)
        gate!!.complete(Unit)
        gate = null
        advanceUntilIdle()

        gate = CompletableDeferred()
        vm.openSortDialog()
        vm.onDialogSelect(2)
        runCurrent()
        vm.resume()
        runCurrent()
        assertEquals(2, calls().size, "a running PAGE1 load already reads fresh data")
        gate!!.complete(Unit)
        advanceUntilIdle()

        gate = CompletableDeferred()
        vm.resume()
        runCurrent()
        vm.resume()
        runCurrent()
        assertEquals(3, calls().size, "a second resume adds nothing")
        gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, calls().size)
    }

    @Test
    fun `a resume reload keeps focus on the clicked card by key, without a loading state`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onFocused(3)
        vm.onCardClick(vm.state.value.items[3])
        entries = entries.filterNot { it.item.key == "show:1" }
        gate = CompletableDeferred()
        vm.resume()
        runCurrent()
        assertEquals(12, vm.state.value.items.size, "the items stay on screen meanwhile")
        assertFalse(vm.state.value.loading)
        gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=1&page_size=50", calls().last())
        assertEquals(11, vm.state.value.items.size)
        assertEquals(2, vm.state.value.focus!!.index, "film:4 moved from index 3 to 2")
        assertEquals(2, vm.restoreIndex())
    }

    @Test
    fun `a resume reload moves focus to the clamped index when the card is gone`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onFocused(11)
        vm.onCardClick(vm.state.value.items[11])
        entries = entries.take(11)
        vm.resume()
        advanceUntilIdle()
        assertEquals(10, vm.state.value.focus!!.index, "the new last card")

        vm.onFocused(4)
        vm.onCardClick(vm.state.value.items[4])
        entries = entries.filterNot { it.item.key == "show:5" }
        vm.resume()
        advanceUntilIdle()
        assertEquals(4, vm.state.value.focus!!.index, "the card now at that index")
        assertEquals("film:6", vm.keys()[4])
    }

    @Test
    fun `a failed resume reload keeps the items and the paging state, with no error`() = runTest(main.dispatcher) {
        entries = list(120)
        val vm = vm()
        advanceUntilIdle()
        vm.onFocused(42)
        advanceUntilIdle()
        assertEquals(100, vm.state.value.items.size)
        failNext = true
        vm.resume()
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=1&page_size=100", calls().last())
        assertEquals(100, vm.state.value.items.size)
        assertNull(vm.state.value.error)
        vm.onFocused(95)
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=3&page_size=50", calls().last(), "lastPage is still 2")
    }

    @Test
    fun `with a write in flight the first load waits for it before reading the list`() = runTest(main.dispatcher) {
        val write = CompletableDeferred<Unit>()
        repos.api.onWatchlistWrite = { _, _, _ -> write.await() }
        // Removed on a detail screen, then Back to Mi lista (a new ViewModel) before the DELETE answered.
        repos.watchlist.write(WatchlistKind.FILM, 2, false)
        runCurrent()
        val vm = vm()
        runCurrent()
        assertEquals(emptyList<String>(), calls(), "no GET until the write answered")
        assertTrue(vm.state.value.loading)
        entries = entries.filterNot { it.item.key == "film:2" }
        write.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, calls().size)
        assertEquals(11, vm.state.value.items.size)
        assertFalse("film:2" in vm.keys())
    }

    @Test
    fun `with a write in flight the resume reload waits for it before reading the list`() = runTest(main.dispatcher) {
        val write = CompletableDeferred<Unit>()
        repos.api.onWatchlistWrite = { _, _, _ -> write.await() }
        val vm = vm()
        advanceUntilIdle()
        repos.watchlist.write(WatchlistKind.FILM, 2, false)
        runCurrent()
        vm.onCardClick(vm.state.value.items[1])
        vm.resume()
        runCurrent()
        assertEquals(1, calls().size, "no GET until the write answered")
        entries = entries.filterNot { it.item.key == "film:2" }
        write.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, calls().size)
        assertEquals(11, vm.state.value.items.size)
    }

    @Test
    fun `a resume after three pages sends one request of 150, after five pages five of 50`() = runTest(main.dispatcher) {
        entries = list(400)
        val vm = vm()
        advanceUntilIdle()
        repeat(2) {
            vm.onFocused(vm.state.value.items.size - 1)
            advanceUntilIdle()
        }
        assertEquals(150, vm.state.value.items.size)
        vm.resume()
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=1&page_size=150", calls().last())
        assertEquals(150, vm.state.value.items.size)
        vm.onFocused(149)
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=4&page_size=50", calls().last(), "paging continues with no gap")
        vm.onFocused(199)
        advanceUntilIdle()
        assertEquals(250, vm.state.value.items.size)

        val before = calls().size
        vm.resume()
        advanceUntilIdle()
        assertEquals((1..5).map { "GET /api/watchlist?type=all&sort=added&page=$it&page_size=50" }, calls().drop(before))
        assertEquals((1L..250L).map { if (it % 2 == 1L) "show:$it" else "film:$it" }, vm.keys(), "no missing or duplicated card")
        vm.onFocused(249)
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=6&page_size=50", calls().last())
    }

    @Test
    fun `a resume cancels a running next-page load and covers it, paging then asks for that page again`() = runTest(main.dispatcher) {
        entries = list(120)
        val vm = vm()
        advanceUntilIdle()
        gate = CompletableDeferred()
        val late = gate!!
        vm.onFocused(42)
        runCurrent()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=2&page_size=50", calls().last())
        gate = null
        vm.resume()
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=1&page_size=50", calls().last(), "lastPage is still 1")
        late.complete(Unit)
        advanceUntilIdle()
        assertEquals(50, vm.state.value.items.size, "the cancelled page is never applied")
        vm.onFocused(42)
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=2&page_size=50", calls().last())
        assertEquals(100, vm.state.value.items.size)
    }

    @Test
    fun `no page loads while the reload runs, and a dialog pick cancels the reload`() = runTest(main.dispatcher) {
        entries = list(120)
        val vm = vm()
        advanceUntilIdle()
        gate = CompletableDeferred()
        val reload = gate!!
        vm.resume()
        runCurrent()
        val count = calls().size
        vm.onFocused(45)
        runCurrent()
        assertEquals(count, calls().size, "onFocused on the last rows sends nothing during the reload")
        gate = null
        vm.openTypeDialog()
        vm.onDialogSelect(1)
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=films&sort=added&page=1&page_size=50", calls().last())
        entries = list(1)
        reload.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.keys().all { it.startsWith("film:") }, "the reload's answer is never applied")
        assertEquals(50, vm.state.value.items.size)
    }

    @Test
    fun `after a page with nothing new ended paging, a resume reload lets paging continue`() = runTest(main.dispatcher) {
        entries = list(120)
        val firstPage = entries.take(50)
        var shifted = true
        repos.api.onWatchlist = { _, _, page, size ->
            // The list shifted between pages: page 2 brings only titles already shown.
            if (shifted && page == 2) Page(firstPage, 2, size, 120) else Page(entries.drop((page - 1) * size).take(size), page, size, 120)
        }
        val vm = vm()
        advanceUntilIdle()
        vm.onFocused(42)
        advanceUntilIdle()
        assertEquals(50, vm.state.value.items.size)
        vm.onFocused(49)
        advanceUntilIdle()
        assertEquals(2, calls().size, "endReached")
        shifted = false
        vm.resume()
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=1&page_size=100", calls().last())
        assertEquals(100, vm.state.value.items.size)
        vm.onFocused(95)
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=3&page_size=50", calls().last())
    }

    @Test
    fun `a resume reload that empties the list asks for the header when focus was on a card, for empty and noMatches`() = runTest(main.dispatcher) {
        entries = list(1)
        val vm = vm()
        advanceUntilIdle()
        vm.onCardClick(vm.state.value.items[0])
        entries = emptyList()
        val version = vm.state.value.headerFocusVersion
        vm.resume()
        advanceUntilIdle()
        assertTrue(vm.state.value.empty)
        assertNull(vm.state.value.focus)
        assertEquals(version + 1, vm.state.value.headerFocusVersion)

        entries = list(2)
        val shows = vm(WatchlistType.SHOWS)
        shows.onBodyFocusChanged(true)
        entries = list(2).filter { it.kind == "film" }
        val before = shows.state.value.headerFocusVersion
        shows.resume()
        advanceUntilIdle()
        assertTrue(shows.state.value.noMatches)
        assertNull(shows.state.value.focus)
        assertEquals(before + 1, shows.state.value.headerFocusVersion)
    }

    @Test
    fun `a card focused without a click is kept by key on resume while the grid has focus`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onBodyFocusChanged(true)
        vm.onFocused(3)
        entries = entries.drop(1)
        vm.resume()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.focus!!.index)
        assertEquals("film:4", vm.keys()[2])
    }

    @Test
    fun `with focus on the header or the top nav a resume replaces the items but never moves focus`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onBodyFocusChanged(true)
        vm.onFocused(2)
        vm.onBodyFocusChanged(false)
        val before = vm.state.value
        entries = entries.drop(1)
        vm.resume()
        advanceUntilIdle()
        assertEquals(11, vm.state.value.items.size)
        assertEquals(before.focus, vm.state.value.focus)
        assertEquals(before.headerFocusVersion, vm.state.value.headerFocusVersion)
        assertEquals(1, vm.restoreIndex(), "the next entry into the grid uses the same title")

        entries = emptyList()
        vm.resume()
        advanceUntilIdle()
        assertTrue(vm.state.value.empty)
        assertEquals(before.headerFocusVersion, vm.state.value.headerFocusVersion, "the header survives the reload")
    }

    @Test
    fun `after a card click the resume publishes one focus and then forgets the click`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onCardClick(vm.state.value.items[2])
        vm.onBodyFocusChanged(false)
        vm.resume()
        advanceUntilIdle()
        val focus = vm.state.value.focus!!
        assertEquals(2, focus.index)
        vm.resume()
        advanceUntilIdle()
        assertEquals(focus, vm.state.value.focus, "returnToCard was cleared by the first reload")
    }

    @Test
    fun `a resume without a stop since the last load reloads nothing (the screen's first resume comes with the init load)`() = runTest(main.dispatcher) {
        val vm = WatchlistViewModel(repos.watchlist, repos.store, memory)
        advanceUntilIdle()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(1, calls().size, "the init load is already fresh")
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(1, calls().size, "still never hidden")
        vm.resume()
        advanceUntilIdle()
        assertEquals(2, calls().size)
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(2, calls().size, "the stop was consumed by the previous reload")
    }

    @Test
    fun `a card opened before the screen's first resume still reloads on Back`() = runTest(main.dispatcher) {
        // The entry never reached RESUMED (e.g. OK during the enter transition): the first ON_RESUME is Back's.
        val vm = WatchlistViewModel(repos.watchlist, repos.store, memory)
        advanceUntilIdle()
        vm.onFocused(1)
        vm.onCardClick(vm.state.value.items[1])
        entries = entries.filterNot { it.item.key == "film:2" }
        vm.onHidden()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(2, calls().size)
        assertEquals(11, vm.state.value.items.size)
        assertFalse("film:2" in vm.keys())
        assertEquals(1, vm.state.value.focus!!.index, "focus on the card now at the clicked index")
    }

    @Test
    fun `Reintentar during a resume reload is ignored, the reload replaces the failed page`() = runTest(main.dispatcher) {
        entries = list(120)
        val vm = vm()
        advanceUntilIdle()
        failNext = true
        vm.onFocused(42)
        advanceUntilIdle()
        assertTrue(vm.state.value.pageFailed)
        gate = CompletableDeferred()
        vm.resume()
        runCurrent()
        val count = calls().size
        vm.retry()
        runCurrent()
        assertEquals(count, calls().size)
        gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.pageFailed)
        assertEquals(50, vm.state.value.items.size)
        vm.onFocused(42)
        advanceUntilIdle()
        assertEquals("GET /api/watchlist?type=all&sort=added&page=2&page_size=50", calls().last())
    }
}
