package com.hometv.tv.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.core.api.ActivityEntry
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.Avatar
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.data.RatingsRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.util.RelativeTime
import com.hometv.tv.core.util.Stars
import com.hometv.tv.core.util.relativeTime
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.components.AvatarUi
import com.hometv.tv.ui.navigation.NavRequest
import java.time.Clock
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
 * One rating of the household: "**Luis** ha puntuado **Dune** con ★★" / "Has puntuado …" ([own]),
 * [time] null when `rated_at` does not parse. [key] = "profile:kind:id"; [poster] is API-relative.
 */
data class ActivityRowUi(
    val key: String,
    val own: Boolean,
    val name: String,
    val initial: String,
    val color: String?,
    val title: String,
    val stars: Stars,
    val time: RelativeTime?,
    val poster: String?,
    val kind: String,
    val id: Long,
)

enum class ActivityPhase { LOADING, CONTENT, EMPTY, ERROR }

/** The row to focus on the next composition ([version] changes for each request). */
data class ActivityFocus(val index: Int, val version: Int)

data class ActivityUiState(
    val items: List<ActivityRowUi> = emptyList(),
    val phase: ActivityPhase = ActivityPhase.LOADING,
    /** Page 1 failed (phase ERROR), or a next page failed ([pageFailed]). */
    val error: ScreenError? = null,
    val endReached: Boolean = false,
    val focus: ActivityFocus? = null,
) {
    /** A next page failed while rows are shown: the inline retry row (`activity_retry`). */
    val pageFailed: Boolean get() = error != null && items.isNotEmpty()
}

/**
 * (12) Actividad: the household's ratings, newest first, in pages of 50 (the
 * next page when focus reaches the last 5 rows, one request at a time, a failed page waits for
 * [retry]). Freshness: [onVisible] after an [onHidden] (Back from a detail, a return from the
 * background) reloads every loaded page in place, keeping the focused row by key; a rating saved
 * anywhere ([RatingsRepository.changes]) reloads at once while the screen is shown. Relative times are
 * recomputed from [clock] (its zone is the calendar's) on every load.
 */
class ActivityViewModel(
    private val ratings: RatingsRepository,
    private val store: SessionStore,
    private val clock: Clock,
    private val avatars: () -> List<Avatar> = { emptyList() },
) : ViewModel() {
    private enum class JobKind { PAGE1, MORE, RESUME }

    private val _state = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()
    private val _navigation = Channel<NavRequest>(Channel.BUFFERED)
    val navigation: Flow<NavRequest> = _navigation.receiveAsFlow()

    private var job: Job? = null
    private var jobKind: JobKind? = null
    private var lastPage = 0
    private var hidden = false
    private var visible = false
    private var lastFocused = 0
    private var lastFocusedKey: String? = null
    private var focusVersion = 0
    private var returnToRow = false

    /** A reload was asked for while page 1 or a reload was in flight: one more runs after it. */
    private var reloadPending = false

    init {
        loadFirst()
        viewModelScope.launch {
            ratings.changes.collect { if (visible) reloadLoaded() }
        }
    }

    /** [Reintentar]: page 1 again, or the failed next page. */
    fun retry() {
        if (job?.isActive == true) return
        if (lastPage == 0) loadFirst() else loadMore()
    }

    fun onFocused(index: Int) {
        val state = _state.value
        lastFocused = index
        state.items.getOrNull(index)?.let { lastFocusedKey = it.key }
        if (state.items.isEmpty() || state.endReached || state.error != null || job?.isActive == true) return
        if (index >= state.items.size - NEAR_END) loadMore()
    }

    fun onRowClick(row: ActivityRowUi) {
        lastFocusedKey = row.key
        returnToRow = true
        _navigation.trySend(NavRequest.Detail(row.kind, row.id))
    }

    /** The row to focus when the list is shown again. */
    fun restoreIndex(): Int = lastFocused.coerceIn(0, (_state.value.items.size - 1).coerceAtLeast(0))

    /** `ON_STOP`: the next [onVisible] reloads. */
    fun onHidden() {
        hidden = true
        visible = false
    }

    /** `ON_RESUME`: reloads in place only after an [onHidden]. */
    fun onVisible() {
        visible = true
        if (!hidden) return
        hidden = false
        reloadLoaded()
    }

    private fun loadFirst() {
        job?.cancel()
        reloadPending = false
        lastPage = 0
        _state.update { it.copy(phase = ActivityPhase.LOADING, error = null, items = emptyList(), endReached = false) }
        jobKind = JobKind.PAGE1
        job = viewModelScope.launch {
            try {
                val page = ratings.activity(1)
                val rows = rows(page.items)
                lastPage = 1
                _state.update {
                    it.copy(
                        items = rows,
                        phase = if (rows.isEmpty()) ActivityPhase.EMPTY else ActivityPhase.CONTENT,
                        error = null,
                        endReached = rows.size >= page.total || page.items.isEmpty(),
                    )
                }
            } catch (e: ApiException) {
                _state.update { it.copy(phase = ActivityPhase.ERROR, error = ScreenError.from(e, store.session.value.serverUrl)) }
                return@launch
            }
            if (reloadPending) {
                jobKind = JobKind.RESUME
                reloadPages()
            }
        }
    }

    private fun loadMore() {
        val next = lastPage + 1
        _state.update { it.copy(error = null) }
        jobKind = JobKind.MORE
        job = viewModelScope.launch {
            try {
                val page = ratings.activity(next)
                lastPage = next
                val loaded = _state.value.items
                val known = loaded.mapTo(HashSet()) { it.key }
                val fresh = rows(page.items).filterNot { it.key in known }
                _state.update {
                    val items = it.items + fresh
                    it.copy(items = items, endReached = fresh.isEmpty() || items.size >= page.total)
                }
            } catch (e: ApiException) {
                _state.update { it.copy(error = ScreenError.from(e, store.session.value.serverUrl)) }
            }
        }
    }

    /**
     * Every loaded page again, in place: one request up to 4 pages (the server's 200 page size), else
     * page by page. Asked for while page 1 or a reload is in flight (it may have left before a save
     * committed), it runs once more after that one; a next-page load is cancelled instead.
     */
    private fun reloadLoaded() {
        if (job?.isActive == true) {
            if (jobKind != JobKind.MORE) return run { reloadPending = true }
            job?.cancel()
        }
        if (lastPage == 0) return loadFirst()
        jobKind = JobKind.RESUME
        job = viewModelScope.launch { reloadPages() }
    }

    private suspend fun reloadPages() {
        do {
            reloadPending = false
            val pages = lastPage
            try {
                val results = if (pages <= MAX_RELOAD_PAGES_IN_ONE) {
                    listOf(ratings.activity(1, pages * PAGE_SIZE))
                } else {
                    (1..pages).map { ratings.activity(it) }
                }
                val items = rows(results.flatMap { it.items }).distinctBy { it.key }
                val total = results.last().total
                applyReload(items, total)
            } catch (_: ApiException) {
                // Silent: the rows stay as they were; the next resume retries.
                break
            }
        } while (reloadPending)
        returnToRow = false
    }

    private fun applyReload(items: List<ActivityRowUi>, total: Int) {
        if (items.isEmpty()) {
            lastFocused = 0
            lastFocusedKey = null
            _state.update { it.copy(items = items, phase = ActivityPhase.EMPTY, error = null, endReached = true, focus = null) }
            return
        }
        val index = items.indexOfFirst { it.key == lastFocusedKey }.takeIf { it >= 0 } ?: lastFocused.coerceAtMost(items.size - 1)
        lastFocused = index
        lastFocusedKey = items[index].key
        val refocus = returnToRow
        _state.update {
            it.copy(
                items = items,
                phase = ActivityPhase.CONTENT,
                error = null,
                endReached = items.size >= total,
                focus = if (refocus) ActivityFocus(index, ++focusVersion) else it.focus,
            )
        }
    }

    private fun rows(entries: List<ActivityEntry>): List<ActivityRowUi> {
        val me = store.session.value.profileId
        val now = clock.instant()
        val colors = avatars()
        return entries.mapNotNull { e ->
            val (title, poster) = when (val item = e.item) {
                is FilmSummary -> item.title to item.poster?.small
                is ShowSummary -> item.title to item.poster?.small
                else -> return@mapNotNull null
            }
            val stars = Stars.of(e.stars) ?: return@mapNotNull null
            val avatar = AvatarUi.of(e.profile.name, e.profile.avatar_id, colors)
            ActivityRowUi(
                key = "${e.profile.id}:${e.item.type}:${e.item.id}",
                own = e.profile.id == me,
                name = e.profile.name,
                initial = avatar.initial,
                color = avatar.color,
                title = title,
                stars = stars,
                time = relativeTime(e.rated_at, now, clock.zone),
                poster = poster,
                kind = e.item.type,
                id = e.item.id,
            )
        }.distinctBy { it.key }
    }

    companion object {
        const val PAGE_SIZE = RatingsRepository.PAGE_SIZE

        /** Focus within this many rows of the end loads the next page. */
        const val NEAR_END = 5

        private const val MAX_RELOAD_PAGES_IN_ONE = 4
    }
}
