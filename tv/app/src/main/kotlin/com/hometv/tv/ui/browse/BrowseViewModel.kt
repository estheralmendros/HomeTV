package com.hometv.tv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.Summary
import com.hometv.tv.core.data.BrowseSort
import com.hometv.tv.core.data.CatalogRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.util.Format
import com.hometv.tv.ui.common.PosterItem
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.Library
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.text.UiText
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Browse sort per library, remembered for the app session (not persisted; prd3 §4.2). */
class BrowseSortMemory {
    private val sorts = ConcurrentHashMap<Library, BrowseSort>()

    fun get(library: Library): BrowseSort = sorts[library] ?: BrowseSort.TITLE_ASC

    fun set(library: Library, sort: BrowseSort) {
        sorts[library] = sort
    }
}

/** A focus request on a grid index; [version] changes for each request. */
data class GridFocus(val index: Int, val version: Int)

data class BrowseUiState(
    val library: Library,
    val sort: BrowseSort,
    val items: List<PosterItem> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val error: ScreenError? = null,
    val focus: GridFocus? = null,
) {
    /** "Películas · 1.234". */
    val header: UiText
        get() = UiText.Res(R.string.pair_dot, listOf(UiText.Res(if (library == Library.FILMS) R.string.nav_films else R.string.nav_shows), Format.count(total)))

    /** "Ordenar: Título A–Z". */
    val sortLabel: UiText get() = UiText.Res(R.string.browse_sort, listOf(UiText.Res(sortRes(sort))))

    companion object {
        fun sortRes(sort: BrowseSort): Int = when (sort) {
            BrowseSort.TITLE_ASC -> R.string.sort_title_asc
            BrowseSort.TITLE_DESC -> R.string.sort_title_desc
            BrowseSort.YEAR_DESC -> R.string.sort_year_desc
            BrowseSort.YEAR_ASC -> R.string.sort_year_asc
            BrowseSort.ADDED_DESC -> R.string.sort_added_desc
        }
    }
}

/**
 * Browse grid (prd3 §4): pages of 50, 6 columns, the next page when focus reaches the last two
 * loaded rows (one request in flight, until `items.size == total`), sort cycle. The grid state and
 * the loaded pages live here, so Back from a detail restores the same card.
 */
class BrowseViewModel(
    private val library: Library,
    private val catalog: CatalogRepository,
    private val store: SessionStore,
    private val memory: BrowseSortMemory,
) : ViewModel() {
    private val _state = MutableStateFlow(BrowseUiState(library, memory.get(library)))
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()
    private val _navigation = Channel<NavRequest>(Channel.BUFFERED)
    val navigation: Flow<NavRequest> = _navigation.receiveAsFlow()
    private var job: Job? = null
    private var lastPage = 0
    private var lastFocused = 0
    private var focusVersion = 0

    init {
        reload(focusFirst = false)
    }

    fun retry() = if (lastPage == 0) reload(focusFirst = false) else loadMore()

    /** Changes the sort to the next option, reloads page 1 and focuses the first card. */
    fun cycleSort() {
        val next = _state.value.sort.next()
        memory.set(library, next)
        _state.update { it.copy(sort = next) }
        reload(focusFirst = true)
    }

    fun onFocused(index: Int) {
        lastFocused = index
        val state = _state.value
        val size = state.items.size
        if (size == 0 || size >= state.total || job?.isActive == true) return
        if (index / COLUMNS >= (size - 1) / COLUMNS - 1) loadMore()
    }

    /** The card to focus when the grid is shown again (Back from a detail). */
    fun restoreIndex(): Int = lastFocused.coerceAtMost((_state.value.items.size - 1).coerceAtLeast(0))

    fun onCardClick(item: PosterItem) {
        _navigation.trySend(NavRequest.Detail(item.summary.type, item.summary.id))
    }

    private fun reload(focusFirst: Boolean) {
        job?.cancel()
        lastPage = 0
        lastFocused = 0
        _state.update { it.copy(items = emptyList(), total = 0, loading = true, error = null) }
        job = viewModelScope.launch {
            fetch(1)?.let { (items, total) ->
                lastPage = 1
                _state.update {
                    it.copy(items = items, total = total, loading = false, focus = if (focusFirst) GridFocus(0, ++focusVersion) else it.focus)
                }
            }
        }
    }

    private fun loadMore() {
        val page = lastPage + 1
        job = viewModelScope.launch {
            fetch(page)?.let { (items, total) ->
                lastPage = page
                _state.update { it.copy(items = it.items + items, total = total, loading = false) }
            }
        }
    }

    private suspend fun fetch(page: Int): Pair<List<PosterItem>, Int>? = try {
        val sort = _state.value.sort
        val result: Pair<List<Summary>, Int> = when (library) {
            Library.FILMS -> catalog.films(page, PAGE_SIZE, sort).let { it.items to it.total }
            Library.SHOWS -> catalog.shows(page, PAGE_SIZE, sort).let { it.items to it.total }
        }
        result.first.mapNotNull(PosterItem::of) to result.second
    } catch (e: ApiException) {
        _state.update { it.copy(loading = false, error = ScreenError.from(e, store.session.value.serverUrl)) }
        null
    }

    companion object {
        const val PAGE_SIZE = 50
        const val COLUMNS = 6
    }
}
