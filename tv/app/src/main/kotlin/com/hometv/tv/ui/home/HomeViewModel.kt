package com.hometv.tv.ui.home

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.api.Summary
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.data.BrowseSort
import com.hometv.tv.core.data.CatalogRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.util.Format
import com.hometv.tv.core.util.Stars
import com.hometv.tv.data.HomeCache
import com.hometv.tv.data.HomePayload
import com.hometv.tv.data.HomeRefresh
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.Library
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.navigation.PlayRequest
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Home rows in display order; each is hidden when empty. "Seguir viendo" holds the
 * in-progress items and each started show's Next Up episode (no separate Next Up row).
 */
enum class HomeRowId(val prefix: String, @param:StringRes val title: Int) {
    CONTINUE("continue", R.string.home_continue_watching),
    RECENT("recent", R.string.home_recently_added),
    FILMS("films", R.string.home_films),
    SHOWS("shows", R.string.home_shows),
}

enum class CardStyle { LANDSCAPE, POSTER }

sealed interface HomeCard {
    /** Unique on the screen: `{row}:{type}:{id}` (the same title can be in two rows). */
    val key: String
}

/** A title card. [image] is API-relative (resolved against the server URL by the UI). */
data class ItemCard(
    override val key: String,
    val summary: Summary,
    val style: CardStyle,
    val title: String,
    val subtitle: UiText?,
    val image: String?,
    val progress: Float?,
    val watched: Boolean,
    /** Unwatched-episode count of a show, es-ES formatted; null when 0. */
    val badge: String?,
    /** Shown under poster cards. */
    val year: String? = null,
    /** This profile's rating: the poster card's star badge. */
    val myRating: Stars? = null,
) : HomeCard

/** Trailing "Ver todo" card of the Películas / Series rows. */
data class SeeAllCard(val library: Library) : HomeCard {
    override val key: String get() = "see_all:${library.wire}"
}

data class HomeRow(val id: HomeRowId, val cards: List<HomeCard>)

/** Where focus goes after the rows were replaced (fresh data, Back to the first card). */
data class FocusTarget(val key: String, val rowIndex: Int, val cardIndex: Int, val version: Int)

/** The hero above the rows: film/show `backdrop.large` (else `.small`), title, year, overview; episodes the show's. */
data class HeroUi(val backdrop: String?, val title: String, val year: String?, val episodeLine: UiText?, val overview: String?)

data class HomeUiState(
    val rows: List<HomeRow> = emptyList(),
    val libraryEmpty: Boolean = false,
    val loading: Boolean = true,
    val fromCache: Boolean = false,
    val error: ScreenError? = null,
    val focus: FocusTarget? = null,
    /** "¿Salir de la aplicación?" while non-null. */
    val exitDialog: ExitDialogUi? = null,
)

/**
 * The exit dialog. [signingOut] is true after "Salir" until the app exits; while it is, the ViewModel
 * ignores every press. It is ViewModel-only: the dialog does not render it (the buttons keep their look).
 */
data class ExitDialogUi(val signingOut: Boolean = false)

/**
 * Home: cache-first render, then `/api/home` + Films + Series rows in parallel,
 * replaced in place keeping the focused item by key; hero with a 300 ms settle; refresh rules.
 */
@OptIn(FlowPreview::class)
class HomeViewModel(
    private val catalog: CatalogRepository,
    private val cache: HomeCache,
    private val store: SessionStore,
    events: SharedFlow<AppEvent>,
    private val refresh: HomeRefresh,
    /** After a successful fresh load: re-fetch the profile if the cold-start one failed (`AccountState.ensureLoaded`). */
    private val onFreshLoad: suspend () -> Unit = {},
    /** "Salir": forgets the profile on this device (`SignOut`); the app exits once it returns. */
    private val signOut: suspend () -> Unit = {},
    /** Holds the rating prompts while the exit dialog is open (`RatingPrompts.setHeld`). */
    private val holdPrompts: (Boolean) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    private val _navigation = Channel<NavRequest>(Channel.BUFFERED)
    val navigation: Flow<NavRequest> = _navigation.receiveAsFlow()

    private val focused = MutableStateFlow<ItemCard?>(null)
    val hero: StateFlow<HeroUi?> = focused.filterNotNull().debounce(HERO_SETTLE_MS).map(::heroOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var payload: HomePayload? = null
    private var focusedKey: String? = null

    /**
     * The card the app put focus on at entry (cold start, coming back to the app) while the user has not
     * moved since: fresh data then focuses the new first card instead of following this one by key.
     */
    private var entryKey: String? = null
    private var leftApp = false
    private var focusVersion = 0
    private var job: Job? = null

    init {
        viewModelScope.launch {
            events.collect { if (it is AppEvent.ItemGone) onItemGone(it.type, it.id) }
        }
        load(initial = true)
    }

    fun retry() = load(initial = false)

    /** Home became visible again: back from the Player or a detail screen, or back to the app ([onAppLeft]). */
    fun onVisible() {
        val stale = refresh.consumeStale()
        if (leftApp) {
            leftApp = false
            onAppReturned()
        } else if (stale) {
            load(initial = false)
        }
    }

    /** The app stopped while Home was on screen (Home, the TV's launcher): the next [onVisible] is an entry. */
    fun onAppLeft() {
        leftApp = true
    }

    fun onCardFocused(card: ItemCard) {
        if (card.key != entryKey) entryKey = null
        focusedKey = card.key
        focused.value = card
    }

    fun onSeeAllFocused(card: SeeAllCard) {
        entryKey = null
        focusedKey = card.key
    }

    /** Back to the app: focus the first card at once, reload, then the first card of the fresh rows unless the user moved meanwhile. */
    private fun onAppReturned() {
        val first = _state.value.rows.firstOrNull()?.cards?.firstOrNull()
        if (first != null && _state.value.exitDialog == null) {
            focusedKey = first.key
            entryKey = first.key
            _state.update { it.copy(focus = FocusTarget(first.key, 0, 0, ++focusVersion)) }
        }
        load(initial = false)
    }

    /** The card to focus when Home is shown again (Back from a detail): the last focused card, if still there. */
    fun restoreTarget(): FocusTarget? {
        val key = focusedKey ?: return null
        _state.value.rows.forEachIndexed { r, row ->
            val index = row.cards.indexOfFirst { it.key == key }
            if (index >= 0) return FocusTarget(key, r, index, focusVersion)
        }
        return null
    }

    /** Focus is on the top navigation bar. */
    fun onNavFocused() {
        focusedKey = null
        entryKey = null
    }

    /**
     * Back table: a deep focus (or the top nav) returns to the first card; on the first card, with
     * no cards (loading, empty library) or on the error screen, opens the exit dialog.
     */
    fun onBack() {
        val current = _state.value
        if (current.exitDialog != null) return
        val first = current.rows.firstOrNull()?.cards?.firstOrNull()
        if (current.error == null && first != null && focusedKey != first.key) {
            focusedKey = first.key
            _state.update { it.copy(focus = FocusTarget(first.key, 0, 0, ++focusVersion)) }
            return
        }
        holdPrompts(true)
        _state.update { it.copy(exitDialog = ExitDialogUi()) }
    }

    /**
     * "Salir": single-flight; cancels the load, signs out, then asks to exit. A failing sign-out is
     * logged and the app still exits; a cleared ViewModel (cancelled scope) sends nothing.
     */
    fun onExitConfirmed() {
        val dialog = _state.value.exitDialog ?: return
        if (dialog.signingOut) return
        _state.update { it.copy(exitDialog = ExitDialogUi(signingOut = true)) }
        // A load finishing after the press would refill the account and write the Home cache.
        job?.cancel()
        viewModelScope.launch {
            try {
                signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { Log.w("HomeTvSession", "sign-out failed", e) }
            }
            _navigation.send(NavRequest.Exit)
        }
    }

    /** "Cancelar" or Back on the dialog; ignored while signing out. */
    fun onExitDismissed() {
        val dialog = _state.value.exitDialog ?: return
        if (dialog.signingOut) return
        _state.update { it.copy(exitDialog = null) }
        holdPrompts(false)
    }

    override fun onCleared() {
        // After "Salir" the activity finishes; a next Home in the same process must not inherit the hold.
        holdPrompts(false)
    }

    fun onCardClick(card: HomeCard) {
        entryKey = null
        val request = when (card) {
            is SeeAllCard -> NavRequest.Browse(card.library)
            is ItemCard -> when (val s = card.summary) {
                is EpisodeSummary -> NavRequest.Play(PlayRequest("episode", s.id, payload = s))
                is FilmSummary -> if (card.key.startsWith(HomeRowId.CONTINUE.prefix)) NavRequest.Play(PlayRequest("film", s.id)) else NavRequest.Detail("film", s.id)
                is ShowSummary -> NavRequest.Detail("show", s.id)
                else -> return
            }
        }
        _navigation.trySend(request)
    }

    /** Long-press on a Continue Watching card (in progress or Next Up) → the detail of the film or show. */
    fun onCardLongClick(card: HomeCard) {
        entryKey = null
        val item = card as? ItemCard ?: return
        val request = when (val s = item.summary) {
            is EpisodeSummary -> NavRequest.Detail("show", s.show_id)
            is FilmSummary -> NavRequest.Detail("film", s.id)
            is ShowSummary -> NavRequest.Detail("show", s.id)
            else -> return
        }
        _navigation.trySend(request)
    }

    private fun load(initial: Boolean) {
        if (_state.value.exitDialog?.signingOut == true) return
        job?.cancel()
        _state.update { it.copy(error = null, loading = it.rows.isEmpty() && !it.libraryEmpty) }
        job = viewModelScope.launch {
            val profileId = store.session.value.profileId
            if (initial && payload == null && profileId != null) {
                cache.read(profileId)?.let { apply(it, fromCache = true) }
            }
            try {
                val fresh = coroutineScope {
                    val home = async { catalog.home() }
                    val films = async { catalog.films(page = 1, pageSize = ROW_SIZE, sort = BrowseSort.ADDED_DESC) }
                    val shows = async { catalog.shows(page = 1, pageSize = ROW_SIZE, sort = BrowseSort.ADDED_DESC) }
                    HomePayload(home.await(), films.await().items, shows.await().items)
                }
                apply(fresh, fromCache = false)
                profileId?.let { cache.write(it, fresh) }
                onFreshLoad()
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = ScreenError.from(e, store.session.value.serverUrl)) }
            }
        }
    }

    private fun onItemGone(type: String, id: Long) {
        payload?.let { apply(it.without(type, id), fromCache = _state.value.fromCache) }
        load(initial = false)
    }

    private fun apply(data: HomePayload, fromCache: Boolean) {
        payload = data
        val rows = rowsOf(data)
        _state.update {
            it.copy(rows = rows, libraryEmpty = data.home.library.empty, loading = false, fromCache = fromCache, error = null, focus = focusAfter(it.rows, rows))
        }
    }

    /**
     * The first card at entry (nothing focused yet, or still the entry card); else the same item by key
     * when still present, else the same index of its row (clamped), else the first card.
     */
    private fun focusAfter(old: List<HomeRow>, new: List<HomeRow>): FocusTarget? {
        if (new.isEmpty()) return null
        val key = focusedKey
        if (old.isEmpty() || (entryKey != null && key == entryKey)) {
            return target(new, 0, 0).also { entryKey = it.key }
        }
        if (key != null) {
            new.forEachIndexed { r, row -> row.cards.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { return target(new, r, it) } }
            old.forEachIndexed { r, row ->
                val index = row.cards.indexOfFirst { it.key == key }
                if (index >= 0) {
                    val newRow = new.indexOfFirst { it.id == row.id }.takeIf { it >= 0 } ?: r.coerceAtMost(new.lastIndex)
                    return target(new, newRow, index.coerceAtMost(new[newRow].cards.lastIndex))
                }
            }
        }
        return target(new, 0, 0)
    }

    private fun target(rows: List<HomeRow>, row: Int, card: Int): FocusTarget {
        val key = rows[row].cards[card].key
        focusedKey = focusedKey?.let { key }
        return FocusTarget(key, row, card, ++focusVersion)
    }

    private fun rowsOf(data: HomePayload): List<HomeRow> = listOfNotNull(
        row(HomeRowId.CONTINUE, data.home.continue_watching, CardStyle.LANDSCAPE),
        row(HomeRowId.RECENT, data.home.recently_added, CardStyle.POSTER),
        row(HomeRowId.FILMS, data.films, CardStyle.POSTER, SeeAllCard(Library.FILMS)),
        row(HomeRowId.SHOWS, data.shows, CardStyle.POSTER, SeeAllCard(Library.SHOWS)),
    )

    private fun row(id: HomeRowId, items: List<Summary>, style: CardStyle, trailing: HomeCard? = null): HomeRow? {
        val cards = items.mapNotNull { card(id, it, style) }
        if (cards.isEmpty()) return null
        return HomeRow(id, cards + listOfNotNull(trailing))
    }

    private fun card(row: HomeRowId, s: Summary, style: CardStyle): ItemCard? {
        val key = "${row.prefix}:${s.key}"
        return when (s) {
            is FilmSummary -> ItemCard(
                key, s, style, s.title, null,
                if (style == CardStyle.LANDSCAPE) s.backdrop?.small ?: s.poster?.small else s.poster?.small,
                progressOf(s.progress), s.progress?.watched == true, null, s.year?.toString(), Stars.of(s.my_rating),
            )
            is ShowSummary -> ItemCard(
                key, s, style, s.title, null, if (style == CardStyle.LANDSCAPE) s.backdrop?.small ?: s.poster?.small else s.poster?.small,
                null, false, s.unwatched_episode_count.takeIf { it > 0 }?.let(Format::count), s.year?.toString(), Stars.of(s.my_rating),
            )
            is EpisodeSummary -> ItemCard(
                key, s, style, s.show_title, episodeLine(s),
                if (style == CardStyle.LANDSCAPE) s.still?.small ?: s.show_backdrop?.small else s.show_poster?.small,
                progressOf(s.progress, s.duration_seconds), s.progress?.watched == true, null,
            )
            else -> null
        }
    }

    private fun heroOf(card: ItemCard): HeroUi = when (val s = card.summary) {
        is FilmSummary -> HeroUi(s.backdrop?.let { it.large ?: it.small }, s.title, s.year?.toString(), null, s.overview)
        is ShowSummary -> HeroUi(s.backdrop?.let { it.large ?: it.small }, s.title, s.year?.toString(), null, s.overview)
        is EpisodeSummary -> HeroUi(s.show_backdrop?.let { it.large ?: it.small }, s.show_title, null, episodeLine(s), s.overview)
        else -> HeroUi(null, card.title, null, null, null)
    }

    companion object {
        const val HERO_SETTLE_MS = 300L
        const val ROW_SIZE = 20

        /** "T2 · E3 Office Olympics" (or "T2 · E3 Episodio 3"). */
        fun episodeLine(e: EpisodeSummary): UiText = UiText.Res(
            R.string.pair_space,
            listOf(Format.episodeCode(e.season_number, e.episode_number), Format.episodeTitle(e.episode_number, e.title)),
        )

        /** In-progress fraction (0–1) for the progress strip; null when unwatched, watched or unknown. */
        fun progressOf(progress: Progress?, fallbackDuration: Double? = null): Float? {
            if (progress == null || progress.watched) return null
            val duration = progress.duration_seconds ?: fallbackDuration ?: return null
            if (duration <= 0 || progress.position_seconds <= 0) return null
            return (progress.position_seconds / duration).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
