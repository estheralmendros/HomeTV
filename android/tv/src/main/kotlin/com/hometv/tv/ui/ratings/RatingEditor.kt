package com.hometv.tv.ui.ratings

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.data.RatingKind
import com.hometv.tv.core.data.RatingsRepository
import com.hometv.tv.core.util.Stars
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.detail.replacesScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What `RatingDialog` shows; [poster] is API-relative. */
data class RatingDialogUi(
    val mode: Mode,
    val title: String,
    val poster: String?,
    val stars: Stars?,
    val canRemove: Boolean,
    val phase: Phase,
) {
    /** [PROMPT]: "¿Qué te ha parecido?" [Guardar] [Ahora no]; [MANUAL]: [Guardar] [Cancelar] (+ [Quitar puntuación]). */
    enum class Mode { PROMPT, MANUAL }

    enum class Phase { EDITING, SAVING, SAVED, FAILED }
}

/**
 * The rating dialog's state holder, shared by the root `RatingPromptHost` and the
 * detail screens. The write runs in `appScope` ([RatingsRepository]); [scope] (the owner's: a Main
 * scope of the host, the detail ViewModel's) awaits it and runs the 1.5 s confirmation.
 *
 * [save], [remove] and [step] are no-ops unless the phase is EDITING or FAILED and no write for the
 * title is in flight; [save] sets SAVING synchronously. [secondary] (Ahora no / Cancelar) and
 * [dismiss] (Back) do nothing while SAVING. A failure `e` with [closesOn] closes the dialog and calls
 * [onGone]; any other one is FAILED + [onFailed].
 *
 * Callbacks: [onSaving]/[onRemoving] when a write starts (the detail's optimistic value), [onSaved]/
 * [onRemoved] on success, [onFailed] (`removal` true for a remove), [onGone], and [onClosed] each time
 * the dialog closes (never after [detach]).
 */
class RatingEditor(
    private val kind: RatingKind,
    private val id: Long,
    private val mode: RatingDialogUi.Mode,
    title: String,
    poster: String?,
    initial: Stars?,
    private val ratings: RatingsRepository,
    private val scope: CoroutineScope,
    private val onSaving: (Stars) -> Unit = {},
    private val onSaved: (Stars) -> Unit = {},
    private val onRemoving: () -> Unit = {},
    private val onRemoved: () -> Unit = {},
    private val onFailed: (e: ApiException, removal: Boolean) -> Unit = { _, _ -> },
    private val onGone: (Throwable) -> Unit = {},
    private val onClosed: () -> Unit = {},
    private val closesOn: (Throwable) -> Boolean = ::detailCloses,
) {
    private val _ui = MutableStateFlow<RatingDialogUi?>(
        RatingDialogUi(mode, title, poster, initial, canRemove = mode == RatingDialogUi.Mode.MANUAL && initial != null, phase = RatingDialogUi.Phase.EDITING),
    )

    /** Null once closed. */
    val ui: StateFlow<RatingDialogUi?> = _ui.asStateFlow()

    private var savedTimer: Job? = null
    private var detached = false

    private val editable: Boolean
        get() {
            val phase = _ui.value?.phase ?: return false
            return (phase == RatingDialogUi.Phase.EDITING || phase == RatingDialogUi.Phase.FAILED) && !ratings.isWriting(kind, id)
        }

    /** Left (-1) / Right (+1): from no value either key gives ½; then ½ steps clamped to ½..5. */
    fun step(delta: Int) {
        if (!editable) return
        val current = _ui.value ?: return
        _ui.value = current.copy(stars = current.stars?.step(delta) ?: Stars.first())
    }

    /** OK on the stars, [Guardar], [Reintentar]. */
    fun save() {
        if (!editable) return
        val current = _ui.value ?: return
        val stars = current.stars ?: return
        _ui.value = current.copy(phase = RatingDialogUi.Phase.SAVING)
        onSaving(stars)
        scope.launch {
            val failure = attempt { ratings.set(kind, id, stars) }
            if (detached) return@launch
            if (failure == null) {
                _ui.value = _ui.value?.copy(phase = RatingDialogUi.Phase.SAVED)
                onSaved(stars)
                savedTimer = scope.launch {
                    delay(SAVED_MILLIS)
                    close()
                }
            } else if (closesOn(failure)) {
                close()
                onGone(failure)
            } else {
                _ui.value = _ui.value?.copy(phase = RatingDialogUi.Phase.FAILED)
                onFailed(failure, false)
            }
        }
    }

    /** [Quitar puntuación] (manual, rated): the dialog closes at once; the owner shows the outcome. */
    fun remove() {
        if (!editable || _ui.value?.canRemove != true) return
        close()
        onRemoving()
        scope.launch {
            val failure = attempt { ratings.remove(kind, id) }
            if (detached) return@launch
            when {
                failure == null -> onRemoved()
                closesOn(failure) -> onGone(failure)
                else -> onFailed(failure, true)
            }
        }
    }

    /** [Ahora no] (prompt: sends the dismissal) / [Cancelar] (manual: nothing stored). Ignored while SAVING. */
    fun secondary() {
        val phase = _ui.value?.phase ?: return
        if (phase == RatingDialogUi.Phase.SAVING) return
        if (phase != RatingDialogUi.Phase.SAVED && mode == RatingDialogUi.Mode.PROMPT) ratings.dismiss(kind, id)
        close()
    }

    /** Back: nothing while SAVING; after a save closes at once with no request; else as [secondary]. */
    fun dismiss() = secondary()

    /**
     * The owner drops the dialog without an answer (the host leaving for Profiles/Setup, auth expiry):
     * closed with no dismissal, and the in-flight write's late outcome reaches no UI.
     */
    fun detach() {
        detached = true
        savedTimer?.cancel()
        _ui.value = null
    }

    private fun close() {
        savedTimer?.cancel()
        if (_ui.value == null) return
        _ui.value = null
        onClosed()
    }

    private suspend fun attempt(write: suspend () -> Unit): ApiException? = try {
        write()
        null
    } catch (e: ApiException) {
        e
    }

    companion object {
        /** How long "Puntuación guardada" stays before the dialog closes. */
        const val SAVED_MILLIS = 1_500L

        /** Detail screens: 404 `not_found`, a 401 after the renewal and a gone profile go to the screen's `fail(e)`. */
        fun detailCloses(e: Throwable): Boolean = e is ApiException && ScreenError.from(e, null).replacesScreen()

        /**
         * The root host: only 404 `not_found` (title gone) and a gone profile close the dialog. A plain 401
         * passed the renewal and the retry, so the session is alive and nothing was saved: FAILED.
         */
        fun hostCloses(e: Throwable): Boolean = e is ApiException.ProfileGone || isItemGone(e)

        /** A 404 `not_found`: the title is gone. */
        fun isItemGone(e: Throwable): Boolean = e is ApiException && ScreenError.from(e, null) == ScreenError.ItemGone
    }
}
