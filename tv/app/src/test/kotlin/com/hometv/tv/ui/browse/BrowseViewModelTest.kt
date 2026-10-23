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

    private fun vm(library: Library = Library.FILMS) = BrowseViewModel(library, repos.catalog, repos.store, memory)

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
    fun `the sort button cycles through the five options and reloads page 1 focused on the first card`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val expected = listOf(
            BrowseSort.TITLE_DESC to "sort=title&order=desc",
            BrowseSort.YEAR_DESC to "sort=year&order=desc",
            BrowseSort.YEAR_ASC to "sort=year&order=asc",
            BrowseSort.ADDED_DESC to "sort=added&order=desc",
            BrowseSort.TITLE_ASC to "sort=title&order=asc",
        )
        val labels = listOf(R.string.sort_title_desc, R.string.sort_year_desc, R.string.sort_year_asc, R.string.sort_added_desc, R.string.sort_title_asc)
        expected.forEachIndexed { i, (sort, query) ->
            vm.onFocused(20)
            vm.cycleSort()
            advanceUntilIdle()
            assertEquals(sort, vm.state.value.sort)
            assertEquals("GET /api/films?page=1&page_size=50&$query", filmCalls().last())
            assertEquals(UiText.Res(R.string.browse_sort, listOf(UiText.Res(labels[i]))), vm.state.value.sortLabel)
            assertEquals(0, vm.state.value.focus!!.index)
            assertEquals(50, vm.state.value.items.size)
        }
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
    fun `a sort change while a page is loading drops the stale page`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        repos.api.onFilms.let { real -> repos.api.onFilms = { p, s, so, o -> if (p == 2) delay(1_000); real(p, s, so, o) } }
        vm.onFocused(45)
        runCurrent()
        vm.cycleSort()
        advanceUntilIdle()
        assertEquals(50, vm.state.value.items.size)
        assertEquals(BrowseSort.TITLE_DESC, vm.state.value.sort)
    }

    @Test
    fun `the chosen sort is remembered per library for the app session`() = runTest(main.dispatcher) {
        vm().cycleSort()
        advanceUntilIdle()
        assertEquals(BrowseSort.TITLE_DESC, vm(Library.FILMS).state.value.sort)
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
