package com.hometv.tv.ui.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.PersonAppearance
import com.hometv.tv.core.api.PersonDetail
import com.hometv.tv.core.data.CatalogRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.util.Format
import com.hometv.tv.core.util.LifeSpan
import com.hometv.tv.data.DetailRefresh
import com.hometv.tv.ui.common.PosterItem
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.text.UiText
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface PersonUiState {
    data object Loading : PersonUiState

    data class Content(
        val name: String,
        /** `profile_image.large` (h632). */
        val photo: String?,
        /** First letter of the name (es-ES uppercase) for the photo placeholder: data, not UI text. */
        val initial: String,
        /** Null → the line is hidden. */
        val lifeSpan: UiText?,
        /** TMDB text, untranslated. */
        val placeOfBirth: String?,
        /** Null → no biography block. */
        val biography: String?,
        val cards: List<AppearanceCard>,
        /** `!credits_complete`: the note under the name. */
        val incomplete: Boolean,
        val bioDialogOpen: Boolean = false,
        /** Where focus goes on (re-)entry; never changed by D-pad moves. */
        val focus: PersonFocus,
        val focusVersion: Int,
    ) : PersonUiState

    data class Error(val error: ScreenError) : PersonUiState
}

/** A card of "En tu catálogo": the Browse/Search poster item plus "como <personaje>". */
data class AppearanceCard(val item: PosterItem, val subtitle: UiText?)

sealed interface PersonFocus {
    data object Bio : PersonFocus

    /** The card with this [PosterItem.key]; the screen derives its index from the cards. */
    data class Card(val key: String) : PersonFocus

    data object None : PersonFocus
}

/**
 * (10) Person (cast-appearances prd3 §3): `GET /api/people/{id}`, 404 → [ScreenError.PersonGone].
 * After a Player launch ([DetailRefresh]) the screen refetches in place when it is visible again,
 * keeping focus where the viewer has it while that target still exists.
 */
class PersonViewModel(
    private val personId: Long,
    private val catalog: CatalogRepository,
    private val store: SessionStore,
    private val detailRefresh: DetailRefresh,
    private val today: () -> LocalDate,
) : ViewModel() {
    private val _state = MutableStateFlow<PersonUiState>(PersonUiState.Loading)
    val state: StateFlow<PersonUiState> = _state.asStateFlow()
    private val _navigation = Channel<NavRequest>(Channel.BUFFERED)
    val navigation: Flow<NavRequest> = _navigation.receiveAsFlow()

    private var job: Job? = null
    private var seenGeneration = detailRefresh.generation()

    init {
        load()
    }

    fun retry() = load()

    /** ON_RESUME: refetch in place (no Loading) once per Player launch; a failure keeps the content. */
    fun onVisible() {
        if (detailRefresh.generation() == seenGeneration || job?.isActive == true) return
        seenGeneration = detailRefresh.generation()
        job = viewModelScope.launch {
            val person = try {
                catalog.person(personId)
            } catch (_: ApiException) {
                return@launch
            }
            val current = _state.value as? PersonUiState.Content
            _state.value = if (current == null) content(person) else refreshed(current, content(person))
        }
    }

    /** OK on a card: remember it for the focus restore on Back, then open its detail. */
    fun onCardSelected(card: AppearanceCard) {
        val c = _state.value as? PersonUiState.Content ?: return
        _state.value = c.copy(focus = PersonFocus.Card(card.item.key))
        _navigation.trySend(NavRequest.Detail(card.item.summary.type, card.item.summary.id))
    }

    fun openBio() {
        val c = _state.value as? PersonUiState.Content ?: return
        if (c.biography != null) _state.value = c.copy(bioDialogOpen = true)
    }

    fun closeBio() {
        val c = _state.value as? PersonUiState.Content ?: return
        _state.value = c.copy(bioDialogOpen = false, focus = PersonFocus.Bio, focusVersion = c.focusVersion + 1)
    }

    private fun load() {
        job?.cancel()
        _state.value = PersonUiState.Loading
        seenGeneration = detailRefresh.generation()
        job = viewModelScope.launch {
            _state.value = try {
                content(catalog.person(personId))
            } catch (e: ApiException) {
                val error = ScreenError.from(e, store.session.value.serverUrl)
                PersonUiState.Error(if (error == ScreenError.ItemGone) ScreenError.PersonGone else error)
            }
        }
    }

    /**
     * The refetched [next] with the current focus memory when its target still exists (the same
     * value, so the screen's focus effect does not re-run); else the initial-focus rule.
     */
    private fun refreshed(current: PersonUiState.Content, next: PersonUiState.Content): PersonUiState.Content {
        val kept = when (val f = current.focus) {
            PersonFocus.Bio -> next.biography != null
            is PersonFocus.Card -> next.cards.any { it.item.key == f.key }
            PersonFocus.None -> true
        }
        return next.copy(
            focus = if (kept) current.focus else next.focus,
            focusVersion = current.focusVersion,
            bioDialogOpen = current.bioDialogOpen && next.biography != null,
        )
    }

    private fun content(p: PersonDetail): PersonUiState.Content {
        val cards = p.appearances.mapNotNull { a -> PosterItem.of(a.item)?.let { AppearanceCard(it, subtitle(a)) } }
        val biography = p.biography?.takeIf { it.isNotBlank() }
        return PersonUiState.Content(
            name = p.name,
            photo = p.profile_image?.large,
            initial = p.name.trim().firstOrNull()?.toString()?.uppercase(Format.LOCALE) ?: "?",
            lifeSpan = lifeSpanText(Format.lifeSpan(p.birthday, p.deathday, today())),
            placeOfBirth = p.place_of_birth?.takeIf { it.isNotBlank() },
            biography = biography,
            cards = cards,
            incomplete = !p.credits_complete,
            focus = initialFocus(cards, biography),
            focusVersion = 0,
        )
    }

    companion object {
        /** "como Paul Atreides" for films and shows alike; nothing without a character (no episode count). */
        fun subtitle(a: PersonAppearance): UiText? =
            a.character?.takeIf { it.isNotBlank() }?.let { UiText.Res(R.string.person_as_character, listOf(it)) }

        /** The life span line (cast-appearances prd.md "Life span line"); null → hidden. */
        fun lifeSpanText(span: LifeSpan?): UiText? = when (span) {
            is LifeSpan.Living -> span.age?.let { UiText.Res(R.string.person_life_living, listOf(span.birth, ageText(it))) } ?: UiText.Data(span.birth)
            is LifeSpan.Deceased -> span.age?.let { UiText.Res(R.string.person_life_deceased, listOf(span.birth, span.death, ageText(it))) }
                ?: UiText.Res(R.string.person_life_deceased_no_age, listOf(span.birth, span.death))
            is LifeSpan.DeathOnly -> UiText.Res(R.string.person_life_death_only, listOf(span.death))
            null -> null
        }

        private fun ageText(age: Int) = UiText.Plural(R.plurals.person_age, age, listOf(Format.count(age)))

        /** The first card, else the biography, else nothing (Back still works). */
        private fun initialFocus(cards: List<AppearanceCard>, biography: String?): PersonFocus = when {
            cards.isNotEmpty() -> PersonFocus.Card(cards.first().item.key)
            biography != null -> PersonFocus.Bio
            else -> PersonFocus.None
        }
    }
}
