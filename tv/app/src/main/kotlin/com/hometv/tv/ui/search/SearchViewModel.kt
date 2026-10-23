package com.hometv.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.data.CatalogRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.ui.common.PosterItem
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val films: List<PosterItem> = emptyList(),
    val shows: List<PosterItem> = emptyList(),
    /** "No hay resultados para «dune»". */
    val noResults: UiText? = null,
    val error: ScreenError? = null,
)

/** Search (prd3 §5): `GET /api/search?q=&limit=20` debounced 300 ms, latest query wins. */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val catalog: CatalogRepository,
    private val store: SessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private val _navigation = Channel<NavRequest>(Channel.BUFFERED)
    val navigation: Flow<NavRequest> = _navigation.receiveAsFlow()
    private val queries = MutableStateFlow("")

    /** The last focused element (`field` or a result key), restored when the screen is shown again. */
    var lastFocusedKey: String? = null

    init {
        viewModelScope.launch {
            queries.debounce(DEBOUNCE_MS).collectLatest { search(it) }
        }
    }

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text) }
        queries.value = text
    }

    /** The first voice-recognition result fills the field. */
    fun onVoiceResult(text: String) = onQueryChange(text)

    fun retry() {
        viewModelScope.launch { search(queries.value) }
    }

    fun onCardClick(item: PosterItem) {
        _navigation.trySend(NavRequest.Detail(item.summary.type, item.summary.id))
    }

    private suspend fun search(raw: String) {
        val q = raw.trim().take(CatalogRepository.MAX_QUERY_LENGTH)
        if (q.isEmpty()) {
            _state.update { it.copy(films = emptyList(), shows = emptyList(), noResults = null, error = null) }
            return
        }
        try {
            val result = catalog.search(q)
            val films = result.films.mapNotNull(PosterItem::of)
            val shows = result.shows.mapNotNull(PosterItem::of)
            _state.update {
                it.copy(films = films, shows = shows, error = null, noResults = if (films.isEmpty() && shows.isEmpty()) UiText.Res(R.string.search_no_results, listOf(q)) else null)
            }
        } catch (e: ApiException) {
            _state.update { it.copy(error = ScreenError.from(e, store.session.value.serverUrl)) }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 300L
    }
}
