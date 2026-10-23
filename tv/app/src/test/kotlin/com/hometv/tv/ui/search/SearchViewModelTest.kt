package com.hometv.tv.ui.search

import com.hometv.tv.R
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.SearchResponse
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class SearchViewModelTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    private val repos = TestRepos().apply {
        api.onSearch = { q, _ ->
            if (q == "nada") SearchResponse() else SearchResponse(films = listOf(FilmSummary(1, "Dune", 2021), FilmSummary(2, "Dune", 1984)), shows = listOf(ShowSummary(3, "Dune: Prophecy", 2024)))
        }
    }

    private fun vm() = SearchViewModel(repos.catalog, repos.store)

    private fun searches() = repos.api.calls.filter { it.startsWith("GET /api/search") }

    @Test
    fun `typing is debounced 300 ms and only the last query is sent`() = runTest(main.dispatcher) {
        val vm = vm()
        "dune".forEachIndexed { i, _ ->
            vm.onQueryChange("dune".take(i + 1))
            advanceTimeBy(100)
        }
        assertEquals(emptyList<String>(), searches())
        advanceTimeBy(201)
        assertEquals(listOf("GET /api/search?q=dune&limit=20"), searches())
        advanceUntilIdle()
        val results = vm.state.value
        assertEquals(listOf(1L, 2L), results.films.map { it.summary.id })
        assertEquals(listOf(3L), results.shows.map { it.summary.id })
        assertNull(results.noResults)
    }

    @Test
    fun `a newer query cancels the request in flight`() = runTest(main.dispatcher) {
        repos.api.onSearch = { q, _ -> delay(if (q == "alien") 5_000 else 10); SearchResponse(films = listOf(FilmSummary(if (q == "alien") 9 else 1, q, 2000))) }
        val vm = vm()
        vm.onQueryChange("alien")
        advanceTimeBy(400)
        vm.onQueryChange("dune")
        advanceUntilIdle()
        assertEquals(listOf("GET /api/search?q=alien&limit=20", "GET /api/search?q=dune&limit=20"), searches())
        assertEquals(listOf(1L), vm.state.value.films.map { it.summary.id })
    }

    @Test
    fun `a blank query clears the results without a request, long queries are cut to 100`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onQueryChange("dune")
        advanceUntilIdle()
        vm.onQueryChange("   ")
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), vm.state.value.films)
        assertEquals(1, searches().size)
        vm.onQueryChange("x".repeat(150))
        advanceUntilIdle()
        assertEquals("GET /api/search?q=${"x".repeat(100)}&limit=20", searches().last())
    }

    @Test
    fun `no results shows the catalog message with the query`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onQueryChange("nada")
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.search_no_results, listOf("nada")), vm.state.value.noResults)
    }

    @Test
    fun `voice input fills the query and searches`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onVoiceResult("dune")
        runCurrent()
        assertEquals("dune", vm.state.value.query)
        advanceUntilIdle()
        assertEquals(listOf("GET /api/search?q=dune&limit=20"), searches())
    }

    @Test
    fun `a result opens its detail`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onQueryChange("dune")
        advanceUntilIdle()
        vm.onCardClick(vm.state.value.shows.first())
        assertEquals(NavRequest.Detail("show", 3), vm.navigation.first())
    }
}
