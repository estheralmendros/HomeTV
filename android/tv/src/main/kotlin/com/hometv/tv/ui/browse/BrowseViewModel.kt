package com.hometv.tv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.Genre
import com.hometv.tv.core.api.Summary
import com.hometv.tv.core.data.BrowseFilter
import com.hometv.tv.core.data.BrowseSort
import com.hometv.tv.core.data.CatalogRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.util.Format
import com.hometv.tv.ui.common.PosterItem
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.Library
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.text.UiText
import java.time.Year
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

/**
 * The Browse sort per library, remembered for the app session (not persisted). The
 * filters are not here: they live in the screen's ViewModel and reset when the screen is left.
 */
class BrowseSortMemory {
    private val sorts = ConcurrentHashMap<Library, BrowseSort>()

    fun get(library: Library): BrowseSort = sorts[library] ?: BrowseSort.TITLE_ASC

    fun set(library: Library, sort: BrowseSort) {
        sorts[library] = sort
    }

    /** Back to Título A-Z for every library ("Salir" forgets the viewer's choices). */
    fun reset() {
        sorts.clear()
    }
}

/** A decade filter: [from]..[to] inclusive; [from] null = "Antes de 1950" (only `year_to`). */
data class Decade(val from: Int?, val to: Int)

enum class BrowseDialogKind { SORT, GENRE, DECADE, WATCHED }

/** The open list dialog: [items] with a ✓ and initial focus on [selected]. */
data class BrowseDialog(val kind: BrowseDialogKind, val title: UiText, val items: List<UiText>, val selected: Int)

/** A focus request on a grid index; [version] changes for each request. */
data class GridFocus(val index: Int, val version: Int)

data class BrowseUiState(
    val library: Library,
    val sort: BrowseSort,
    // The active filters; null = "Todos" (genre and years) / "Todas" (watched: true = "Vistas", false = "No vistas").
    val genre: Genre? = null,
    val decade: Decade? = null,
    val watched: Boolean? = null,
    val dialog: BrowseDialog? = null,
    val items: List<PosterItem> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val error: ScreenError? = null,
    val focus: GridFocus? = null,
) {
    /** A next page failed while the grid has items: inline "No se han podido cargar más títulos." [Reintentar]. */
    val pageFailed: Boolean get() = error != null && items.isNotEmpty()

    /** "Películas · 1.234". */
    val header: UiText
        get() = UiText.Res(R.string.pair_dot, listOf(UiText.Res(if (library == Library.FILMS) R.string.nav_films else R.string.nav_shows), Format.count(total)))

    /** "Ordenar: Título A–Z". */
    val sortLabel: UiText get() = UiText.Res(R.string.browse_sort, listOf(UiText.Res(sortRes(sort))))

    /** "Género: Todos" / "Género: Drama". */
    val genreLabel: UiText get() = UiText.Res(R.string.browse_genre, listOf(genre?.let { UiText.Data(it.name) } ?: UiText.Res(R.string.filter_all_genres)))

    /** "Años: Todos" / "Años: 1990–1999". */
    val decadeLabel: UiText get() = UiText.Res(R.string.browse_decade, listOf(decade?.let(::decadeText) ?: UiText.Res(R.string.filter_all_decades)))

    /** "Estado: Todas" / "Estado: Vistas" / "Estado: No vistas". */
    val watchedLabel: UiText get() = UiText.Res(R.string.browse_watched, listOf(UiText.Res(watchedRes(watched))))

    /** Filters active and nothing matches: "Ningún título coincide con estos filtros." */
    val noMatches: Boolean get() = (genre != null || decade != null || watched != null) && !loading && error == null && items.isEmpty()

    companion object {
        fun sortRes(sort: BrowseSort): Int = when (sort) {
            BrowseSort.TITLE_ASC -> R.string.sort_title_asc
            BrowseSort.TITLE_DESC -> R.string.sort_title_desc
            BrowseSort.YEAR_DESC -> R.string.sort_year_desc
            BrowseSort.YEAR_ASC -> R.string.sort_year_asc
            BrowseSort.ADDED_DESC -> R.string.sort_added_desc
        }

        fun watchedRes(watched: Boolean?): Int = when (watched) {
            null -> R.string.filter_all_watched
            true -> R.string.filter_watched
            false -> R.string.filter_unwatched
        }

        /** "1990–1999", or "Antes de 1950" (years as data: no thousands separator). */
        fun decadeText(decade: Decade): UiText =
            if (decade.from == null) UiText.Res(R.string.decade_before, listOf((decade.to + 1).toString()))
            else UiText.Res(R.string.decade_range, listOf(decade.from.toString(), decade.to.toString()))
    }
}

/**
 * Browse grid: pages of 50, 6 columns, the next page when focus reaches the last two
 * loaded rows (one request in flight, until `items.size == total`; a failed page shows an inline
 * error and waits for [retry]), and the sort / genre / decade / watched list dialogs. The grid state and
 * the loaded pages live here, so Back from a detail restores the same card.
 */
class BrowseViewModel(
    private val library: Library,
    private val catalog: CatalogRepository,
    private val store: SessionStore,
    private val memory: BrowseSortMemory,
    currentYear: Int = Year.now().value,
) : ViewModel() {
    // Filters start at "Todos" on every new screen; a detail opened from it keeps this ViewModel.
    private val _state = MutableStateFlow(BrowseUiState(library, memory.get(library)))
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()
    private val _navigation = Channel<NavRequest>(Channel.BUFFERED)
    val navigation: Flow<NavRequest> = _navigation.receiveAsFlow()
    private var job: Job? = null
    private var genreJob: Job? = null

    /** A page brought no new item (the library shrank between pages): no more pages until a reload. */
    private var endReached = false
    private var lastPage = 0
    private var lastFocused = 0
    private var focusVersion = 0
    private var genres: List<Genre>? = null

    /** "Todos", the decades from the current one down to the 1950s, then "Antes de 1950". */
    private val decades: List<Decade?> = listOf<Decade?>(null) +
        (currentYear / 10 * 10 downTo FIRST_DECADE step 10).map { Decade(it, it + 9) } +
        Decade(null, FIRST_DECADE - 1)

    init {
        reload(focusFirst = false)
    }

    /** "Todas", "Vistas", "No vistas". */
    private val watchedOptions: List<Boolean?> = listOf(null, true, false)

    fun retry() = if (lastPage == 0) reload(focusFirst = false) else loadMore(focusNewPage = true)

    fun openSortDialog() {
        val sorts = BrowseSort.entries
        _state.update {
            it.copy(dialog = BrowseDialog(BrowseDialogKind.SORT, UiText.Res(R.string.dialog_sort), sorts.map { s -> UiText.Res(BrowseUiState.sortRes(s)) }, sorts.indexOf(it.sort)))
        }
    }

    /**
     * Loads the library's genres once (a failure or an empty list offers only "Todos"), then opens the list.
     * One load at a time; a dialog opened meanwhile (e.g. "Ordenar") is not replaced when the load ends.
     */
    fun openGenreDialog() {
        if (genreJob?.isActive == true) return
        val opened = _state.value.dialog
        genreJob = viewModelScope.launch {
            val list = genres ?: try {
                catalog.genres(if (library == Library.FILMS) "films" else "shows")
            } catch (_: ApiException) {
                null
            }.also { if (it != null) genres = it }
            val options = listOf<Genre?>(null) + list.orEmpty()
            _state.update { s ->
                if (s.dialog != opened) return@update s
                val items = options.map { g -> g?.let { UiText.Data(it.name) } ?: UiText.Res(R.string.filter_all_genres) }
                s.copy(dialog = BrowseDialog(BrowseDialogKind.GENRE, UiText.Res(R.string.dialog_genre), items, options.indexOfFirst { it?.id == s.genre?.id }.coerceAtLeast(0)))
            }
        }
    }

    fun openDecadeDialog() {
        _state.update { s ->
            val items = decades.map { d -> d?.let(BrowseUiState::decadeText) ?: UiText.Res(R.string.filter_all_decades) }
            s.copy(dialog = BrowseDialog(BrowseDialogKind.DECADE, UiText.Res(R.string.dialog_decade), items, decades.indexOf(s.decade).coerceAtLeast(0)))
        }
    }

    fun openWatchedDialog() {
        _state.update { s ->
            val items = watchedOptions.map { UiText.Res(BrowseUiState.watchedRes(it)) }
            s.copy(dialog = BrowseDialog(BrowseDialogKind.WATCHED, UiText.Res(R.string.dialog_watched), items, watchedOptions.indexOf(s.watched)))
        }
    }

    /** A pick reloads page 1 and focuses the first card; the current entry only closes the dialog. */
    fun onDialogSelect(index: Int) {
        val state = _state.value
        val dialog = state.dialog ?: return
        _state.update { it.copy(dialog = null) }
        if (index == dialog.selected) return
        val next = when (dialog.kind) {
            BrowseDialogKind.SORT -> state.copy(sort = BrowseSort.entries[index])
            BrowseDialogKind.GENRE -> state.copy(genre = if (index == 0) null else genres?.getOrNull(index - 1))
            BrowseDialogKind.DECADE -> state.copy(decade = decades[index])
            BrowseDialogKind.WATCHED -> state.copy(watched = watchedOptions[index])
        }
        memory.set(library, next.sort)
        _state.update { it.copy(sort = next.sort, genre = next.genre, decade = next.decade, watched = next.watched) }
        reload(focusFirst = true)
    }

    /** Back on the dialog: nothing changes. */
    fun onDialogDismiss() {
        _state.update { it.copy(dialog = null) }
    }

    fun onFocused(index: Int) {
        lastFocused = index
        val state = _state.value
        val size = state.items.size
        // A failed page waits for [Reintentar] (retry) instead of refetching on every focus move.
        if (size == 0 || size >= state.total || endReached || job?.isActive == true || state.error != null) return
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
        endReached = false
        // The previous total stays in the header until page 1 arrives (no "Películas · 0" flash).
        _state.update { it.copy(items = emptyList(), loading = true, error = null) }
        job = viewModelScope.launch {
            fetch(1)?.let { (items, total) ->
                lastPage = 1
                // No match: focus stays on the button that changed the filter.
                val focusCard = focusFirst && items.isNotEmpty()
                _state.update {
                    it.copy(items = items, total = total, loading = false, focus = if (focusCard) GridFocus(0, ++focusVersion) else it.focus)
                }
            }
        }
    }

    /** [focusNewPage]: [Reintentar] had focus and disappears with the error, so the first new card takes it. */
    private fun loadMore(focusNewPage: Boolean = false) {
        val page = lastPage + 1
        _state.update { it.copy(error = null) }
        job = viewModelScope.launch {
            fetch(page)?.let { (items, total) ->
                lastPage = page
                // The library can shift between pages (a scan): drop keys already loaded, since the grid is
                // keyed by item.key. A page with nothing new ends paging (no refetch on every focus move).
                val loaded = _state.value.items
                val known = loaded.mapTo(HashSet()) { it.key }
                val fresh = items.filterNot { it.key in known }
                val first = loaded.size
                if (fresh.isEmpty()) endReached = true
                val focus = if (focusNewPage && fresh.isNotEmpty()) GridFocus(first, ++focusVersion).also { lastFocused = first } else null
                _state.update {
                    it.copy(items = it.items + fresh, total = total, loading = false, focus = focus ?: it.focus)
                }
            }
        }
    }

    private suspend fun fetch(page: Int): Pair<List<PosterItem>, Int>? = try {
        val state = _state.value
        val filter = BrowseFilter(genreId = state.genre?.id, yearFrom = state.decade?.from, yearTo = state.decade?.to, watched = state.watched)
        val result: Pair<List<Summary>, Int> = when (library) {
            Library.FILMS -> catalog.films(page, PAGE_SIZE, state.sort, filter).let { it.items to it.total }
            Library.SHOWS -> catalog.shows(page, PAGE_SIZE, state.sort, filter).let { it.items to it.total }
        }
        result.first.mapNotNull(PosterItem::of) to result.second
    } catch (e: ApiException) {
        _state.update { it.copy(loading = false, error = ScreenError.from(e, store.session.value.serverUrl)) }
        null
    }

    companion object {
        const val PAGE_SIZE = 50
        const val COLUMNS = 6

        /** The oldest decade listed; earlier years are "Antes de 1950". */
        const val FIRST_DECADE = 1950
    }
}
