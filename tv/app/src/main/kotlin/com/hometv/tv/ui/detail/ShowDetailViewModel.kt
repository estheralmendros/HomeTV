package com.hometv.tv.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.SeasonDetail
import com.hometv.tv.core.api.ShowDetail
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.data.AppEventSink
import com.hometv.tv.core.data.CatalogRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.data.WatchRepository
import com.hometv.tv.core.data.WatchTarget
import com.hometv.tv.core.data.WatchlistKind
import com.hometv.tv.core.data.WatchlistRepository
import com.hometv.tv.core.util.Format
import com.hometv.tv.data.DetailRefresh
import com.hometv.tv.data.HomeRefresh
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.home.HomeViewModel
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.navigation.PlayRequest
import com.hometv.tv.ui.text.UiFormat
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** A season of the left column: its title is a structured `SeasonTitle` ("Temporada N", "Especiales", or the TMDB name). */
data class SeasonUi(val number: Int, val title: UiText)

/** One episode row: "3. Office Olympics" (or "Episodio 3"), "4 oct 2005 · 22 min", overview, watched / progress. */
data class EpisodeRowUi(
    val id: Long,
    val title: UiText,
    val meta: UiText,
    val overview: String?,
    val still: String?,
    val watched: Boolean,
    val progress: Float?,
)

/** The episode pane: season name, "N episodios", the season watched button, rows; [entryIndex] = where Right lands. */
data class SeasonPaneUi(
    val number: Int,
    val title: UiText,
    val count: UiText,
    val watchedButton: UiText,
    val episodes: List<EpisodeRowUi>,
    val entryIndex: Int,
)

/** Episode actions dialog (long-press): [Ver desde el principio] [Marcar como visto | no visto]. */
data class EpisodeActionsUi(val episodeId: Long, val watchedText: UiText)

sealed interface ShowDetailUiState {
    data object Loading : ShowDetailUiState

    data class Content(
        val show: ShowDetail,
        /** "2005 · Finalizada · Comedia · ★ 8,6 · 150 sin ver". */
        val meta: UiText,
        val buttons: List<DetailButton>,
        val seasons: List<SeasonUi>,
        val selectedSeason: Int,
        /** Null while the selected season loads. */
        val season: SeasonPaneUi?,
        val episodeActions: EpisodeActionsUi? = null,
        /** Row to keep focused after a watched change (null: no request). */
        val focusEpisodeIndex: Int? = null,
        val focusVersion: Int = 0,
        /** A season answered 404: "Este título ya no está disponible." [Volver] (Back refetches the show). */
        val seasonGone: Boolean = false,
        /** "No se ha podido actualizar Mi lista." under the buttons after a failed write, until the next action. */
        val watchlistError: UiText? = null,
    ) : ShowDetailUiState

    data class Error(val error: ScreenError) : ShowDetailUiState
}

/**
 * Show detail (prd3 §7): next-episode button, show/season/episode watched toggles, seasons fetched
 * 250 ms after focus settles and cached, episode actions dialog, the optimistic "Mi lista" toggle
 * (watchlist prd3 FR-T6), 404 handling.
 */
class ShowDetailViewModel(
    private val showId: Long,
    private val catalog: CatalogRepository,
    private val watch: WatchRepository,
    watchlist: WatchlistRepository,
    private val store: SessionStore,
    private val events: AppEventSink,
    private val refresh: HomeRefresh,
    private val detailRefresh: DetailRefresh,
) : ViewModel() {
    private val _state = MutableStateFlow<ShowDetailUiState>(ShowDetailUiState.Loading)
    val state: StateFlow<ShowDetailUiState> = _state.asStateFlow()
    private val _navigation = Channel<NavRequest>(Channel.BUFFERED)
    val navigation: Flow<NavRequest> = _navigation.receiveAsFlow()

    private var show: ShowDetail? = null
    private val seasonCache = mutableMapOf<Int, SeasonDetail>()
    private var selected: Int? = null
    private var actions: EpisodeActionsUi? = null
    private var focusEpisode: Int? = null
    private var focusVersion = 0
    private var seasonGone = false

    private val listToggle = DetailWatchlistToggle(
        WatchlistKind.SHOW,
        showId,
        watchlist,
        store,
        viewModelScope,
        showsContent = { _state.value is ShowDetailUiState.Content },
        onRolledBack = { value -> show = show?.copy(in_watchlist = value) },
        onScreenError = ::fail,
        publish = ::publish,
    )
    private var seasonJob: Job? = null
    private var job: Job? = null
    private var seenGeneration = detailRefresh.generation()

    init {
        load()
    }

    fun retry() = load()

    /**
     * The screen resumed (e.g. the Player popped back). When a Player was launched since the last
     * fetch, the show and the selected season are refetched in place (no Loading, selection and
     * focus kept): `next_episode`, progress and watched marks change while playing (and autoplay may
     * have moved on to other seasons, so every cached season is dropped).
     */
    fun onVisible() {
        if (detailRefresh.generation() == seenGeneration || job?.isActive == true) return
        seenGeneration = detailRefresh.generation()
        job = viewModelScope.launch {
            try {
                seasonCache.clear()
                apply(catalog.show(showId))
                selected?.let { fetchSeason(it) }
                publish()
            } catch (e: ApiException) {
                fail(e)
            }
        }
    }

    fun onButton(id: ButtonId) {
        val s = show ?: return
        clearWatchlistError()
        when (id) {
            ButtonId.PLAY, ButtonId.RESUME -> s.next_episode?.let { play(it.id, fromStart = false) }
            ButtonId.WATCHED -> changeWatched(WatchTarget.SHOW, showId, s.unwatched_episode_count > 0, clearCache = true)
            ButtonId.WATCHLIST -> toggleWatchlist(s)
            else -> Unit
        }
    }

    /** Moving focus over a season selects it after 250 ms (debounced fetch, cached). */
    fun onSeasonFocused(number: Int) {
        clearWatchlistError()
        seasonJob?.cancel()
        seasonJob = viewModelScope.launch {
            delay(SEASON_SETTLE_MS)
            selectSeason(number)
        }
    }

    fun onSeasonWatched() {
        clearWatchlistError()
        val number = selected ?: return
        val season = seasonCache[number] ?: return
        val summary = show?.seasons?.firstOrNull { it.season_number == number }
        val watched = (summary?.unwatched_episode_count ?: season.episodes.count { it.progress?.watched != true }) > 0
        changeWatched(WatchTarget.SEASON, season.id, watched, clearCache = false)
    }

    fun onEpisodeClick(row: EpisodeRowUi) {
        clearWatchlistError()
        play(row.id, fromStart = false)
    }

    fun onEpisodeLongClick(row: EpisodeRowUi) {
        listToggle.clearError()
        actions = EpisodeActionsUi(row.id, UiText.Res(if (row.watched) R.string.mark_unwatched else R.string.mark_watched))
        publish()
    }

    fun onEpisodeActionsDismiss() {
        actions = null
        publish()
    }

    fun onEpisodePlayFromStart() {
        val a = actions ?: return
        actions = null
        listToggle.clearError()
        publish()
        play(a.episodeId, fromStart = true)
    }

    fun onEpisodeToggleWatched() {
        val a = actions ?: return
        actions = null
        listToggle.clearError()
        val episode = seasonCache[selected]?.episodes?.firstOrNull { it.id == a.episodeId }
        focusEpisode = seasonCache[selected]?.episodes?.indexOfFirst { it.id == a.episodeId }?.takeIf { it >= 0 }
        changeWatched(WatchTarget.EPISODE, a.episodeId, episode?.progress?.watched != true, clearCache = false)
    }

    /** [Volver] on the season 404 screen: back to the show, refetched. */
    fun onSeasonGoneBack() {
        seasonGone = false
        seasonCache.clear()
        selected = null
        load()
    }

    /**
     * Optimistic (watchlist prd3 FR-T6): the label flips at once, focus stays on the button (no
     * refetch). The write runs in `appScope`, coalesced per title, so it survives Back; this
     * ViewModel only awaits it to confirm or roll back.
     */
    private fun toggleWatchlist(s: ShowDetail) {
        val target = !s.in_watchlist
        show = s.copy(in_watchlist = target)
        publish()
        listToggle.toggle(target)
    }

    /** The next action (a button, a season focus change, an episode action) hides the failed-write line. */
    private fun clearWatchlistError() {
        if (listToggle.clearError()) publish()
    }

    /** A fetched show; a list write still in flight wins over its `in_watchlist` until it settles (FR-T6 step 9). */
    private fun apply(fetched: ShowDetail) {
        show = fetched.copy(in_watchlist = listToggle.fetched(fetched.in_watchlist, show?.in_watchlist))
    }

    private fun play(episodeId: Long, fromStart: Boolean) {
        _navigation.trySend(NavRequest.Play(PlayRequest("episode", episodeId, fromStart = fromStart)))
    }

    private fun changeWatched(target: WatchTarget, id: Long, watched: Boolean, clearCache: Boolean) {
        viewModelScope.launch {
            try {
                watch.setWatched(target, id, watched)
                refresh.markStale()
                if (clearCache) seasonCache.clear() else selected?.let { seasonCache.remove(it) }
                focusVersion++
                apply(catalog.show(showId))
                selected?.let { fetchSeason(it) }
                publish()
            } catch (e: ApiException) {
                fail(e)
            }
        }
    }

    private fun load() {
        job?.cancel()
        _state.value = ShowDetailUiState.Loading
        seenGeneration = detailRefresh.generation()
        job = viewModelScope.launch {
            try {
                val detail = catalog.show(showId)
                apply(detail)
                val initial = selected
                    ?: detail.next_episode?.season_number?.takeIf { n -> detail.seasons.any { it.season_number == n } }
                    ?: detail.seasons.firstOrNull()?.season_number
                selected = initial
                publish()
                initial?.let { fetchSeason(it) }
                publish()
            } catch (e: ApiException) {
                fail(e)
            }
        }
    }

    private suspend fun selectSeason(number: Int) {
        if (selected == number && seasonCache.containsKey(number)) return
        selected = number
        focusEpisode = null
        publish()
        fetchSeason(number)
        publish()
    }

    private suspend fun fetchSeason(number: Int) {
        if (seasonCache.containsKey(number)) return
        try {
            seasonCache[number] = catalog.season(showId, number)
        } catch (e: ApiException) {
            val error = ScreenError.from(e, store.session.value.serverUrl)
            if (error == ScreenError.ItemGone) seasonGone = true else _state.value = ShowDetailUiState.Error(error)
        }
    }

    private fun fail(e: ApiException) {
        val error = ScreenError.from(e, store.session.value.serverUrl)
        if (error == ScreenError.ItemGone) {
            events.emit(AppEvent.ItemGone("show", showId))
            refresh.markStale()
        }
        _state.value = ShowDetailUiState.Error(error)
    }

    private fun publish() {
        val s = show ?: return
        if (_state.value is ShowDetailUiState.Error) return
        val number = selected
        _state.value = ShowDetailUiState.Content(
            show = s,
            meta = meta(s),
            buttons = buttons(s),
            seasons = s.seasons.map { SeasonUi(it.season_number, UiText.Value(Format.seasonTitle(it.season_number, it.name))) },
            selectedSeason = number ?: 0,
            season = number?.let { n -> seasonCache[n]?.let { pane(s, it) } },
            episodeActions = actions,
            focusEpisodeIndex = focusEpisode,
            focusVersion = focusVersion,
            seasonGone = seasonGone,
            watchlistError = listToggle.error,
        )
    }

    private fun buttons(s: ShowDetail): List<DetailButton> {
        val next = s.next_episode
        val watched = DetailButton(ButtonId.WATCHED, UiText.Res(if (s.unwatched_episode_count == 0) R.string.mark_show_unwatched else R.string.mark_show_watched))
        val list = watchlistButton(s.in_watchlist)
        if (next == null) return listOf(watched, list)
        val code = Format.episodeCode(next.season_number, next.episode_number)
        val progress = next.progress
        val resume = progress != null && !progress.watched && progress.position_seconds >= MIN_RESUME_SECONDS
        val play = if (resume) DetailButton(ButtonId.RESUME, UiText.Res(R.string.show_resume_episode, listOf(code)))
        else DetailButton(ButtonId.PLAY, UiText.Res(R.string.show_play_episode, listOf(code)))
        return listOf(play, watched, list)
    }

    private fun pane(s: ShowDetail, season: SeasonDetail): SeasonPaneUi {
        val unwatched = s.seasons.firstOrNull { it.season_number == season.season_number }?.unwatched_episode_count
            ?: season.episodes.count { it.progress?.watched != true }
        val entry = season.episodes.indexOfFirst { it.id == s.next_episode?.id }.coerceAtLeast(0)
        return SeasonPaneUi(
            number = season.season_number,
            title = UiText.Value(Format.seasonTitle(season.season_number, season.name)),
            count = UiText.Plural(R.plurals.episode_count, season.episodes.size, listOf(Format.count(season.episodes.size))),
            watchedButton = UiText.Res(if (unwatched == 0) R.string.mark_season_unwatched else R.string.mark_season_watched),
            episodes = season.episodes.map(::row),
            entryIndex = entry,
        )
    }

    private fun row(e: EpisodeSummary): EpisodeRowUi = EpisodeRowUi(
        id = e.id,
        title = e.title?.takeIf { it.isNotBlank() }?.let { UiText.Res(R.string.episode_row_title, listOf(e.episode_number.toString(), it)) }
            ?: UiText.Value(Format.episodeTitle(e.episode_number, null)),
        meta = UiText.Joined(
            listOfNotNull(
                e.air_date?.let { d -> runCatching { Format.date(d) }.getOrNull()?.let { UiText.Data(it) } },
                e.runtime_minutes?.takeIf { it > 0 }?.let { UiText.Value(Format.runtime(it)) },
            ),
        ),
        overview = e.overview,
        still = e.still?.small,
        watched = e.progress?.watched == true,
        progress = HomeViewModel.progressOf(e.progress, e.duration_seconds),
    )

    companion object {
        const val SEASON_SETTLE_MS = 250L
        private const val MIN_RESUME_SECONDS = 30.0

        fun meta(s: ShowDetail): UiText = UiText.Joined(
            listOfNotNull(
                s.year?.let { UiText.Data(it.toString()) },
                UiFormat.showStatusRes(s.status)?.let { UiText.Res(it) },
                s.genres.takeIf { it.isNotEmpty() }?.let { g -> UiText.Joined(g.map { UiText.Data(it.name) }, R.string.separator_comma) },
                s.vote_average?.takeIf { it > 0 }?.let { UiText.Res(R.string.rating, listOf(Format.rating(it))) },
                s.unwatched_episode_count.takeIf { it > 0 }?.let { UiText.Plural(R.plurals.unwatched_count, it, listOf(Format.count(it))) },
            ),
        )
    }
}
