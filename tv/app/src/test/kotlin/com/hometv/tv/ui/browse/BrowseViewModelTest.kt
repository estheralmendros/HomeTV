package com.hometv.tv.ui.browse

import com.hometv.tv.R
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.data.BrowseSort
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.navigation.Library
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class BrowseViewModelTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    private val repos = TestRepos()
    private val memory = BrowseSortMemory()
    private var filmsTotal = 120

    init {
        repos.api.onFilms = { page, size, _, _ ->
            val from = (page - 1) * size
            val ids = (from until minOf(from + size, filmsTotal)).map { it + 1L }
            Page(ids.map { FilmSummary(it, "Film $it", 2000 + (it % 20).toInt()) }, page, size, filmsTotal)
        }
    }

    private fun vm(library: Library = Library.FILMS) = BrowseViewModel(library, repos.catalog, repos.store, memory, currentYear = 2026)

    private fun filmCalls() = repos.api.calls.filter { it.startsWith("GET /api/films?") }

    @Test
    fun `the first page is title A-Z with 50 items, the header shows the total`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("GET /api/films?page=1&page_size=50&sort=title&order=asc"), filmCalls())
        val state = vm.state.value
        assertEquals(50, state.items.size)
        assertEquals(120, state.total)
        assertEquals(UiText.Res(R.string.pair_dot, listOf(UiText.Res(R.string.nav_films), "120")), state.header)
        assertEquals(UiText.Res(R.string.browse_sort, listOf(UiText.Res(R.string.sort_title_asc))), state.sortLabel)
    }

    @Test
    fun `the sort button opens a list of the five orders, and a pick reloads page 1 focused on the first card`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.openSortDialog()
        val dialog = vm.state.value.dialog!!
        assertEquals(BrowseDialogKind.SORT, dialog.kind)
        assertEquals(
            listOf(R.string.sort_title_asc, R.string.sort_title_desc, R.string.sort_year_desc, R.string.sort_year_asc, R.string.sort_added_desc).map { UiText.Res(it) },
            dialog.items,
        )
        assertEquals(0, dialog.selected)
        vm.onFocused(20)
        vm.onDialogSelect(4)
        advanceUntilIdle()
        assertNull(vm.state.value.dialog)
        assertEquals(BrowseSort.ADDED_DESC, vm.state.value.sort)
        assertEquals("GET /api/films?page=1&page_size=50&sort=added&order=desc", filmCalls().last())
        assertEquals(UiText.Res(R.string.browse_sort, listOf(UiText.Res(R.string.sort_added_desc))), vm.state.value.sortLabel)
        assertEquals(0, vm.state.value.focus!!.index)
    }

    @Test
    fun `picking the current entry only closes the dialog, Back closes it unchanged`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val before = filmCalls().size
        vm.openSortDialog()
        vm.onDialogSelect(0)
        advanceUntilIdle()
        assertNull(vm.state.value.dialog)
        vm.openDecadeDialog()
        vm.onDialogDismiss()
        assertNull(vm.state.value.dialog)
        assertEquals(before, filmCalls().size)
    }

    @Test
    fun `the genre list comes from the server once, and a genre filters the grid`() = runTest(main.dispatcher) {
        repos.api.onGenres = { com.hometv.tv.core.api.GenreList(listOf(com.hometv.tv.core.api.Genre(18, "Drama", 3), com.hometv.tv.core.api.Genre(35, "Comedia", 1))) }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.browse_genre, listOf(UiText.Res(R.string.filter_all_genres))), vm.state.value.genreLabel)
        vm.openGenreDialog()
        advanceUntilIdle()
        assertEquals(listOf(UiText.Res(R.string.filter_all_genres), UiText.Data("Drama"), UiText.Data("Comedia")), vm.state.value.dialog!!.items)
        vm.onDialogSelect(1)
        advanceUntilIdle()
        assertEquals("GET /api/films?page=1&page_size=50&sort=title&order=asc&genre=18", filmCalls().last())
        assertEquals(UiText.Res(R.string.browse_genre, listOf(UiText.Data("Drama"))), vm.state.value.genreLabel)
        vm.openGenreDialog()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.dialog!!.selected)
        assertEquals(1, repos.api.calls.count { it == "GET /api/genres?type=films" })
    }

    @Test
    fun `a failed genre load offers only Todos`() = runTest(main.dispatcher) {
        repos.api.onGenres = { throw java.net.ConnectException("refused") }
        val vm = vm()
        advanceUntilIdle()
        vm.openGenreDialog()
        advanceUntilIdle()
        assertEquals(listOf(UiText.Res(R.string.filter_all_genres)), vm.state.value.dialog!!.items)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `decades run from the current one to the 1950s, then before 1950`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.openDecadeDialog()
        val items = vm.state.value.dialog!!.items
        assertEquals(UiText.Res(R.string.filter_all_decades), items.first())
        assertEquals(UiText.Res(R.string.decade_range, listOf("2020", "2029")), items[1])
        assertEquals(UiText.Res(R.string.decade_range, listOf("1950", "1959")), items[items.size - 2])
        assertEquals(UiText.Res(R.string.decade_before, listOf("1950")), items.last())
        assertEquals(10, items.size)
        vm.onDialogSelect(4)
        advanceUntilIdle()
        assertEquals("GET /api/films?page=1&page_size=50&sort=title&order=asc&year_from=1990&year_to=1999", filmCalls().last())
        assertEquals(UiText.Res(R.string.browse_decade, listOf(UiText.Res(R.string.decade_range, listOf("1990", "1999")))), vm.state.value.decadeLabel)
        vm.openDecadeDialog()
        vm.onDialogSelect(9)
        advanceUntilIdle()
        assertEquals("GET /api/films?page=1&page_size=50&sort=title&order=asc&year_to=1949", filmCalls().last())
    }

    @Test
    fun `the watched filter offers Todas, Vistas and No vistas and sends watched`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.browse_watched, listOf(UiText.Res(R.string.filter_all_watched))), vm.state.value.watchedLabel)
        vm.openWatchedDialog()
        val dialog = vm.state.value.dialog!!
        assertEquals(UiText.Res(R.string.dialog_watched), dialog.title)
        assertEquals(listOf(UiText.Res(R.string.filter_all_watched), UiText.Res(R.string.filter_watched), UiText.Res(R.string.filter_unwatched)), dialog.items)
        assertEquals(0, dialog.selected)
        vm.onDialogSelect(2)
        advanceUntilIdle()
        assertEquals("GET /api/films?page=1&page_size=50&sort=title&order=asc&watched=false", filmCalls().last())
        assertEquals(UiText.Res(R.string.browse_watched, listOf(UiText.Res(R.string.filter_unwatched))), vm.state.value.watchedLabel)
        vm.openWatchedDialog()
        assertEquals(2, vm.state.value.dialog!!.selected)
        vm.onDialogSelect(1)
        advanceUntilIdle()
        assertEquals("GET /api/films?page=1&page_size=50&sort=title&order=asc&watched=true", filmCalls().last())
        vm.openWatchedDialog()
        vm.onDialogSelect(0)
        advanceUntilIdle()
        assertEquals("GET /api/films?page=1&page_size=50&sort=title&order=asc", filmCalls().last())
    }

    @Test
    fun `no match with filters shows the message and requests no card focus`() = runTest(main.dispatcher) {
        repos.api.onGenres = { com.hometv.tv.core.api.GenreList(listOf(com.hometv.tv.core.api.Genre(18, "Drama"))) }
        val vm = vm()
        advanceUntilIdle()
        repos.api.onFilms = { p, s, _, _ -> Page(emptyList(), p, s, 0) }
        val focus = vm.state.value.focus
        vm.openGenreDialog()
        advanceUntilIdle()
        vm.onDialogSelect(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.noMatches)
        assertEquals(focus, vm.state.value.focus)
    }

    @Test
    fun `the next page loads when focus reaches the last two loaded rows, once, until all items are loaded`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onFocused(41)
        advanceUntilIdle()
        assertEquals(1, filmCalls().size, "row 7 of 9 (index 36-41) is not in the last two rows")
        repos.api.onFilms.let { real -> repos.api.onFilms = { p, s, so, o -> delay(500); real(p, s, so, o) } }
        vm.onFocused(42)
        runCurrent()
        vm.onFocused(44)
        vm.onFocused(49)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), filmCalls().map { Regex("page=(\\d+)").find(it)!!.groupValues[1].toInt() })
        assertEquals(100, vm.state.value.items.size)
        vm.onFocused(89)
        advanceUntilIdle()
        assertEquals(2, filmCalls().size)
        vm.onFocused(90)
        advanceUntilIdle()
        assertEquals(120, vm.state.value.items.size)
        vm.onFocused(119)
        advanceUntilIdle()
        assertEquals(3, filmCalls().size, "items.size == total: no more pages")
    }

    @Test
    fun `a failed next page shows the inline error and waits for Retry instead of refetching on every focus move`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        var failPage2 = true
        repos.api.onFilms.let { real ->
            repos.api.onFilms = { p, s, so, o -> if (p == 2 && failPage2) throw com.hometv.tv.core.data.FakeHomeTvApi.httpError(500, "internal_error"); real(p, s, so, o) }
        }
        vm.onFocused(44)
        advanceUntilIdle()
        assertEquals(2, filmCalls().size)
        assertTrue(vm.state.value.pageFailed, "the grid stays, with the inline error")
        assertEquals(50, vm.state.value.items.size)
        vm.onFocused(45)
        vm.onFocused(49)
        vm.onFocused(44)
        advanceUntilIdle()
        assertEquals(2, filmCalls().size, "no automatic retry on focus moves")

        failPage2 = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(3, filmCalls().size)
        assertEquals(100, vm.state.value.items.size)
        assertEquals(false, vm.state.value.pageFailed)
        assertNull(vm.state.value.error)
        assertEquals(50, vm.state.value.focus!!.index, "the Retry row is gone: focus moves to the first new card")
        assertEquals(50, vm.restoreIndex())
    }

    @Test
    fun `a page that overlaps the loaded items (the library shifted) appends only new keys`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        // A scan added a title before offset 50: page 2 starts with the last item of page 1.
        repos.api.onFilms.let { real -> repos.api.onFilms = { p, s, so, o -> if (p == 2) { val pg = real(p, s, so, o); pg.copy(items = listOf(FilmSummary(50, "Film 50", 2010)) + pg.items.dropLast(1), total = 121) } else real(p, s, so, o) } }
        vm.onFocused(45)
        advanceUntilIdle()
        val keys = vm.state.value.items.map { it.key }
        assertEquals(keys.distinct(), keys, "the grid is keyed by item.key: no duplicates")
        assertEquals(99, keys.size)
    }

    @Test
    fun `a page with no new items stops paging instead of refetching on every focus move`() = runTest(main.dispatcher) {
        filmsTotal = 60
        val vm = vm()
        advanceUntilIdle()
        // The library shrank between pages: page 2 only repeats an item already loaded.
        repos.api.onFilms.let { real -> repos.api.onFilms = { p, s, so, o -> if (p == 2) Page(listOf(FilmSummary(50, "Film 50", 2010)), p, s, 60) else real(p, s, so, o) } }
        vm.onFocused(45)
        advanceUntilIdle()
        vm.onFocused(49)
        advanceUntilIdle()
        assertEquals(2, filmCalls().size)
        assertEquals(50, vm.state.value.items.size)
    }

    @Test
    fun `a filter change keeps the previous total until page 1 arrives`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        repos.api.onFilms.let { real -> repos.api.onFilms = { p, s, so, o -> delay(500); real(p, s, so, o).copy(total = 30) } }
        vm.openWatchedDialog()
        vm.onDialogSelect(2)
        runCurrent()
        assertTrue(vm.state.value.loading)
        assertEquals(120, vm.state.value.total, "no \"Películas · 0\" flash while page 1 loads")
        advanceUntilIdle()
        assertEquals(30, vm.state.value.total)
    }

    @Test
    fun `OK on Genero while the genres load opens one dialog, and a late load does not replace another dialog`() = runTest(main.dispatcher) {
        repos.api.onGenres = { delay(500); com.hometv.tv.core.api.GenreList(listOf(com.hometv.tv.core.api.Genre(18, "Drama", 3))) }
        val vm = vm()
        advanceUntilIdle()
        vm.openGenreDialog()
        runCurrent()
        vm.openGenreDialog()
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, repos.api.calls.count { it == "GET /api/genres?type=films" })
        assertEquals(BrowseDialogKind.GENRE, vm.state.value.dialog!!.kind)
        vm.onDialogDismiss()

        // A second screen (genres not loaded yet): "Ordenar" opened while "Género" loads stays open.
        val other = vm(Library.SHOWS)
        advanceUntilIdle()
        other.openGenreDialog()
        runCurrent()
        other.openSortDialog()
        advanceUntilIdle()
        assertEquals(BrowseDialogKind.SORT, other.state.value.dialog!!.kind)
    }

    @Test
    fun `a sort change while a page is loading drops the stale page`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        repos.api.onFilms.let { real -> repos.api.onFilms = { p, s, so, o -> if (p == 2) delay(1_000); real(p, s, so, o) } }
        vm.onFocused(45)
        runCurrent()
        vm.openSortDialog()
        vm.onDialogSelect(1)
        advanceUntilIdle()
        assertEquals(50, vm.state.value.items.size)
        assertEquals(BrowseSort.TITLE_DESC, vm.state.value.sort)
    }

    @Test
    fun `the sort is remembered per library for the session, the filters only while the screen is open`() = runTest(main.dispatcher) {
        val first = vm()
        advanceUntilIdle()
        first.openSortDialog()
        first.onDialogSelect(1)
        first.openDecadeDialog()
        first.onDialogSelect(4)
        first.openWatchedDialog()
        first.onDialogSelect(1)
        advanceUntilIdle()
        // A new screen (left for another tab, then back): the sort is remembered, the filters reset.
        val films = vm(Library.FILMS)
        advanceUntilIdle()
        assertEquals(BrowseSort.TITLE_DESC, films.state.value.sort)
        assertNull(films.state.value.decade)
        assertNull(films.state.value.genre)
        assertNull(films.state.value.watched)
        assertEquals("GET /api/films?page=1&page_size=50&sort=title&order=desc", filmCalls().last())
        assertEquals(BrowseSort.TITLE_ASC, vm(Library.SHOWS).state.value.sort)
    }

    @Test
    fun `focus returns to the last focused card when coming back from a detail`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onFocused(12)
        assertEquals(12, vm.restoreIndex())
    }

    @Test
    fun `cards show the watched check, progress or unwatched badge and open the detail`() = runTest(main.dispatcher) {
        repos.api.onFilms = { p, s, _, _ ->
            Page(listOf(
                FilmSummary(1, "Alien", 1979, progress = Progress(position_seconds = 100.0, duration_seconds = 1000.0)),
                FilmSummary(2, "Dune", 2021, progress = Progress(watched = true)),
            ), p, s, 2)
        }
        repos.api.onShows = { p, s, _, _ -> Page(listOf(ShowSummary(7, "Dark", 2017, unwatched_episode_count = 0), ShowSummary(8, "Lost", 2004, unwatched_episode_count = 3)), p, s, 2) }
        val films = vm()
        advanceUntilIdle()
        val (alien, dune) = films.state.value.items
        assertEquals(0.1f, alien.progress)
        assertTrue(dune.watched)
        assertNull(dune.progress)
        assertEquals("1979", alien.year)
        films.onCardClick(dune)
        assertEquals(NavRequest.Detail("film", 2), films.navigation.first())

        val shows = vm(Library.SHOWS)
        advanceUntilIdle()
        assertEquals(listOf(null, "3"), shows.state.value.items.map { it.badge })
        shows.onCardClick(shows.state.value.items[1])
        assertEquals(NavRequest.Detail("show", 8), shows.navigation.first())
    }
}
