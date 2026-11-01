package com.hometv.tv.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.data.WatchlistRepository
import com.hometv.tv.core.data.WatchlistSort
import com.hometv.tv.core.data.WatchlistType
import com.hometv.tv.core.util.Format
import com.hometv.tv.ui.browse.BrowseViewModel
import com.hometv.tv.ui.browse.GridFocus
import com.hometv.tv.ui.common.PosterItem
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.text.UiText
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
 * Mi lista sort for the app session (not persisted), like `BrowseSortMemory`. The type and "Estado"
 * filters are not here: like the Browse filters they live in the screen's ViewModel and reset when the
 * screen is left.
 */
class WatchlistMemory {
    @Volatile var sort: WatchlistSort = WatchlistSort.ADDED
}

enum class WatchlistDialogKind { SORT, TYPE, WATCHED }

/** The open list dialog ("Ordenar" / "Tipo" / "Estado"): [items] with a ✓ and initial focus on [selected] (Browse's `BrowseDialog` twin). */
data class WatchlistDialog(val kind: WatchlistDialogKind, val title: UiText, val items: List<UiText>, val selected: Int)

data class WatchlistUiState(
    val sort: WatchlistSort,
    val type: WatchlistType,
    /** The "Estado" filter: null = "Todos", true = "Vistos", false = "No vistos" (the library rule). */
    val watched: Boolean? = null,
    val dialog: WatchlistDialog? = null,
    val items: List<PosterItem> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val error: ScreenError? = null,
    val focus: GridFocus? = null,
    /** Bumped when focus must go to the header because the grid has no card (FR-T9.6, FR-T10.7). */
    val headerFocusVersion: Int = 0,
) {
    /** A next page failed while the grid has items: inline "No se han podido cargar más títulos." [Reintentar]. */
    val pageFailed: Boolean get() = error != null && items.isNotEmpty()

    /** "Mi lista · 12". */
    val header: UiText get() = UiText.Res(R.string.pair_dot, listOf(UiText.Res(R.string.watchlist_title), Format.count(total)))

    /** "Ordenar: Añadidos recientemente". */
    val sortLabel: UiText get() = UiText.Res(R.string.browse_sort, listOf(UiText.Res(sortRes(sort))))

    /** "Tipo: Todo". */
    val typeLabel: UiText get() = UiText.Res(R.string.watchlist_type, listOf(UiText.Res(typeRes(type))))

    /** "Estado: Todos". */
    val watchedLabel: UiText get() = UiText.Res(R.string.watchlist_watched, listOf(UiText.Res(watchedRes(watched))))

    private val filtered: Boolean get() = type != WatchlistType.ALL || watched != null

    /** Nothing in the list at all (type ALL, Estado Todos). */
    val empty: Boolean get() = !filtered && !loading && error == null && items.isEmpty()

    /** A type and/or Estado filter with no results. */
    val noMatches: Boolean get() = filtered && !loading && error == null && items.isEmpty()

    /** "No hay películas en tu lista." / "No hay series en tu lista." (type only), else the generic filters text. */
    val noMatchesText: UiText get() = UiText.Res(
        when {
            watched != null -> R.string.watchlist_no_matches_filters
            type == WatchlistType.FILMS -> R.string.watchlist_no_films
            else -> R.string.watchlist_no_shows
        },
    )

    companion object {
        /** The Browse sort strings: they name the fixed server directions. */
        fun sortRes(sort: WatchlistSort): Int = when (sort) {
            WatchlistSort.ADDED -> R.string.sort_added_desc
            WatchlistSort.TITLE -> R.string.sort_title_asc
            WatchlistSort.YEAR -> R.string.sort_year_desc
        }

        fun typeRes(type: WatchlistType): Int = when (type) {
            WatchlistType.ALL -> R.string.watchlist_type_all
            WatchlistType.FILMS -> R.string.watchlist_type_films
            WatchlistType.SHOWS -> R.string.watchlist_type_shows
        }

        /** Masculine (Mi lista mixes films and shows); Browse's library filter says Todas / Vistas / No vistas. */
        fun watchedRes(watched: Boolean?): Int = when (watched) {
            null -> R.string.watchlist_watched_all
            true -> R.string.watchlist_watched_yes
            false -> R.string.watchlist_watched_no
        }

        /** The "Estado" dialog entries, in order. */
        val watchedOptions: List<Boolean?> = listOf(null, true, false)
    }
}

/**
 * (11) Mi lista (watchlist prd3 FR-T9): the Browse grid rules (pages of 50, 6 columns, the next page
 * when focus reaches the last two loaded rows, one request in flight, inline failed page + [retry]),
 * the "Ordenar" / "Tipo" / "Estado" list dialogs, and a reload in place when the screen resumes after being
 * stopped ([onHidden], [onVisible]) that picks up changes made on a detail screen, on the web or on
 * another TV.
 *
 * One paging [job] runs at a time; [jobKind] tells a page-1 load, a next page and a resume reload apart.
 */
class WatchlistViewModel(
    private val watchlist: WatchlistRepository,
    private val store: SessionStore,
    private val memory: WatchlistMemory,
) : ViewModel() {
    private enum class JobKind { PAGE1, MORE, RESUME }

    private val _state = MutableStateFlow(WatchlistUiState(memory.sort, WatchlistType.ALL))
    val state: StateFlow<WatchlistUiState> = _state.asStateFlow()
    private val _navigation = Channel<NavRequest>(Channel.BUFFERED)
    val navigation: Flow<NavRequest> = _navigation.receiveAsFlow()
    private var job: Job? = null
    private var jobKind: JobKind? = null

    /** A page brought no new item (the list shifted between pages): no more pages until a reload. */
    private var endReached = false
    private var lastPage = 0
    private var loadedOnce = false
    /** The screen was stopped (ON_STOP) since the last resume: the next [onVisible] reloads. */
    private var hidden = false
    private var lastFocused = 0
    private var lastFocusedKey: String? = null
    private var focusVersion = 0

    /** Focus is in the body (a card, or "Ver todo"): a reload may move or dispose it, so it re-requests focus. */
    private var bodyHasFocus = false

    /** A card was opened: the resume after Back focuses it again (leaving may report the body unfocused first). */
    private var returnToCard = false

    init {
        reload(focusFirst = false)
    }

    /** [Reintentar]; ignored during a resume reload, which replaces the failed page's row anyway. */
    fun retry() {
        if (job?.isActive == true && jobKind == JobKind.RESUME) return
        if (lastPage == 0) reload(focusFirst = false) else loadMore(focusNewPage = true)
    }

    fun openSortDialog() {
        val sorts = WatchlistSort.entries
        _state.update {
            it.copy(dialog = WatchlistDialog(WatchlistDialogKind.SORT, UiText.Res(R.string.dialog_sort), sorts.map { s -> UiText.Res(WatchlistUiState.sortRes(s)) }, sorts.indexOf(it.sort)))
        }
    }

    fun openTypeDialog() {
        val types = WatchlistType.entries
        _state.update {
            it.copy(dialog = WatchlistDialog(WatchlistDialogKind.TYPE, UiText.Res(R.string.dialog_type), types.map { t -> UiText.Res(WatchlistUiState.typeRes(t)) }, types.indexOf(it.type)))
        }
    }

    fun openWatchedDialog() {
        val options = WatchlistUiState.watchedOptions
        _state.update {
            it.copy(
                dialog = WatchlistDialog(
                    WatchlistDialogKind.WATCHED,
                    UiText.Res(R.string.dialog_watched),
                    options.map { w -> UiText.Res(WatchlistUiState.watchedRes(w)) },
                    options.indexOf(it.watched),
                ),
            )
        }
    }

    /**
     * A new pick reloads page 1 and focuses the first card (none: focus stays on the opener); the current
     * entry only closes. A sort is remembered for the app session, a type or Estado only for this screen.
     */
    fun onDialogSelect(index: Int) {
        val dialog = _state.value.dialog ?: return
        _state.update { it.copy(dialog = null) }
        if (index == dialog.selected) return
        when (dialog.kind) {
            WatchlistDialogKind.SORT -> WatchlistSort.entries[index].let { sort ->
                memory.sort = sort
                _state.update { it.copy(sort = sort) }
            }
            WatchlistDialogKind.TYPE -> _state.update { it.copy(type = WatchlistType.entries[index]) }
            WatchlistDialogKind.WATCHED -> _state.update { it.copy(watched = WatchlistUiState.watchedOptions[index]) }
        }
        reload(focusFirst = true, fromDialog = true)
    }

    /** Back on the dialog: nothing changes. */
    fun onDialogDismiss() {
        _state.update { it.copy(dialog = null) }
    }

    /** "Ver todo" on the filtered empty state: clears the type and Estado, keeps the sort. */
    fun showAll() {
        _state.update { it.copy(type = WatchlistType.ALL, watched = null) }
        reload(focusFirst = true)
    }

    fun onFocused(index: Int) {
        val state = _state.value
        lastFocused = index
        state.items.getOrNull(index)?.let { lastFocusedKey = it.key }
        val size = state.items.size
        // A failed page waits for [Reintentar] (retry); a running load or reload is never doubled.
        if (size == 0 || size >= state.total || endReached || job?.isActive == true || state.error != null) return
        if (index / COLUMNS >= (size - 1) / COLUMNS - 1) loadMore()
    }

    /** The body container (grid, or the empty / no-matches column) gained or lost focus. */
    fun onBodyFocusChanged(hasFocus: Boolean) {
        bodyHasFocus = hasFocus
    }

    /** The card to focus when the grid is shown again (Back from a detail). */
    fun restoreIndex(): Int = lastFocused.coerceAtMost((_state.value.items.size - 1).coerceAtLeast(0))

    fun onCardClick(item: PosterItem) {
        lastFocusedKey = item.key
        returnToCard = true
        _navigation.trySend(NavRequest.Detail(item.summary.type, item.summary.id))
    }

    /** The screen was stopped (a detail screen opened, the app went to the background). */
    fun onHidden() {
        hidden = true
    }

    /**
     * The screen resumed: reloads what is loaded in place, after any pending "Mi lista" write landed,
     * only when it was stopped since the last resume ([onHidden]: Back from a detail, a return from the
     * background). The screen's first resume comes with the init load, which already reads fresh data;
     * a card opened before that first resume still reloads on Back, whose resume follows a stop.
     * Ignored before the first page and while a page-1 load or another reload runs; a running next
     * page is cancelled (the next focus on the last rows asks for it again).
     */
    fun onVisible() {
        if (!hidden) return
        hidden = false
        if (!loadedOnce) return
        if (job?.isActive == true) {
            if (jobKind != JobKind.MORE) return
            job?.cancel()
        }
        val pages = maxOf(lastPage, 1)
        jobKind = JobKind.RESUME
        job = viewModelScope.launch {
            try {
                // A toggle made on the detail just before Back is still in flight in appScope (FR-T9.8.1).
                watchlist.awaitIdle()
                val (items, total) = fetchLoaded(pages)
                lastPage = pages
                endReached = false
                applyReload(items, total)
            } catch (_: ApiException) {
                // Silent: the items and the paging state stay as they were; the next resume retries.
            }
            returnToCard = false
        }
    }

    /**
     * The first [pages] × 50 positions of the current list: one request up to the server's 200 page
     * size, else page by page, so the next page (pages + 1, size 50) continues with no gap or overlap.
     */
    private suspend fun fetchLoaded(pages: Int): Pair<List<PosterItem>, Int> {
        val state = _state.value
        val results = if (pages <= MAX_RELOAD_PAGES_IN_ONE) {
            listOf(watchlist.list(state.type, state.sort, 1, pages * PAGE_SIZE, state.watched))
        } else {
            (1..pages).map { watchlist.list(state.type, state.sort, it, PAGE_SIZE, state.watched) }
        }
        val items = results.flatMap { page -> page.items.mapNotNull { PosterItem.of(it.item) } }.distinctBy { it.key }
        return items to results.last().total
    }

    private fun applyReload(items: List<PosterItem>, total: Int) {
        val refocus = returnToCard || bodyHasFocus
        if (items.isEmpty()) {
            lastFocused = 0
            lastFocusedKey = null
            // Focus never stays on a disposed card: the header takes it (only when it was in the body).
            _state.update {
                it.copy(items = items, total = total, loading = false, error = null, focus = null, headerFocusVersion = it.headerFocusVersion + if (refocus) 1 else 0)
            }
            return
        }
        val index = items.indexOfFirst { it.key == lastFocusedKey }.takeIf { it >= 0 } ?: lastFocused.coerceAtMost(items.size - 1)
        lastFocused = index
        lastFocusedKey = items[index].key
        _state.update {
            it.copy(items = items, total = total, loading = false, error = null, focus = if (refocus) GridFocus(index, ++focusVersion) else it.focus)
        }
    }

    /**
     * Page 1 again (first load, [retry], a dialog pick, [showAll]), read once no list write is in
     * flight. With no card, focus goes to the header, except after a dialog pick: its opener still has it.
     */
    private fun reload(focusFirst: Boolean, fromDialog: Boolean = false) {
        job?.cancel()
        lastPage = 0
        lastFocused = 0
        lastFocusedKey = null
        endReached = false
        returnToCard = false
        // The previous total stays in the header until page 1 arrives (no "Mi lista · 0" flash).
        _state.update { it.copy(items = emptyList(), loading = true, error = null) }
        jobKind = JobKind.PAGE1
        job = viewModelScope.launch {
            // A write still in flight (e.g. a removal on a detail screen just left) lands before the read.
            watchlist.awaitIdle()
            fetch(1)?.let { (items, total) ->
                lastPage = 1
                loadedOnce = true
                val focusCard = focusFirst && items.isNotEmpty()
                if (focusCard) lastFocusedKey = items.first().key
                val header = items.isEmpty() && !fromDialog
                _state.update {
                    it.copy(
                        items = items,
                        total = total,
                        loading = false,
                        focus = if (focusCard) GridFocus(0, ++focusVersion) else it.focus,
                        headerFocusVersion = it.headerFocusVersion + if (header) 1 else 0,
                    )
                }
            }
        }
    }

    /** [focusNewPage]: [Reintentar] had focus and disappears with the error, so the first new card takes it. */
    private fun loadMore(focusNewPage: Boolean = false) {
        val page = lastPage + 1
        _state.update { it.copy(error = null) }
        jobKind = JobKind.MORE
        job = viewModelScope.launch {
            fetch(page)?.let { (items, total) ->
                lastPage = page
                // The list can shift between pages: drop keys already loaded (the grid is keyed by item.key).
                // A page with nothing new ends paging (no refetch on every focus move).
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
        watchlist.list(state.type, state.sort, page, watched = state.watched).let { result -> result.items.mapNotNull { PosterItem.of(it.item) } to result.total }
    } catch (e: ApiException) {
        _state.update { it.copy(loading = false, error = ScreenError.from(e, store.session.value.serverUrl)) }
        null
    }

    companion object {
        const val PAGE_SIZE = WatchlistRepository.PAGE_SIZE
        const val COLUMNS = BrowseViewModel.COLUMNS

        /** A resume reload of up to 4 pages (200 titles, the server's `page_size` maximum) is one request. */
        private const val MAX_RELOAD_PAGES_IN_ONE = 4
    }
}
