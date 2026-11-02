package com.hometv.tv.ui.detail

import com.hometv.tv.R
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.Avatar
import com.hometv.tv.core.api.HouseholdAverage
import com.hometv.tv.core.api.OtherRating
import com.hometv.tv.core.data.RatingKind
import com.hometv.tv.core.data.RatingKey
import com.hometv.tv.core.data.RatingsRepository
import com.hometv.tv.core.util.Format
import com.hometv.tv.core.util.Stars
import com.hometv.tv.ui.components.AvatarUi
import com.hometv.tv.ui.ratings.RatingDialogUi
import com.hometv.tv.ui.ratings.RatingEditor
import com.hometv.tv.ui.text.UiText
import com.hometv.tv.ui.text.starsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The ratings block of Film and Show detail: the shown and confirmed "Tu
 * puntuación", the manual [RatingEditor], the "Puntuaciones" list and the inline removal failure.
 * Pattern: [DetailWatchlistToggle] (the owner keeps its detail model; this keeps the rating state).
 *
 * - Save: optimistic `mine`; failure → rollback (the dialog shows FAILED); success → kept.
 * - Remove: the dialog closes; optimistic null; failure → rollback + [error], cleared by [clearError].
 * - A fetched detail while a write is in flight keeps the shown value.
 * - Every successful write of this title ([RatingsRepository.changes], from the root prompt or this
 *   screen) sets `mine` at once and asks the owner for a silent [refetch] (average and others).
 * - [RatingsRepository.writing] for this title republishes the row (the chip's enabled state).
 * - Nothing republishes over an Error or Loading screen ([showsContent] false): the state above still
 *   follows the writes, and the next fetch publishes it (like [DetailWatchlistToggle]).
 *
 * @param showsContent whether the screen shows its content; [publishScreen] is skipped otherwise.
 * @param onScreenError a write failure that replaces the screen (404, sign-in, gone profile): `fail(e)`.
 * @param onDialogClosed the manual dialog closed: the owner focuses `rating_mine` (no publish needed).
 */
class DetailRatings(
    private val kind: RatingKind,
    private val id: Long,
    private val ratings: RatingsRepository,
    private val scope: CoroutineScope,
    private val avatars: () -> List<Avatar>,
    private val onScreenError: (ApiException) -> Unit,
    private val onDialogClosed: () -> Unit,
    private val refetch: () -> Unit,
    private val showsContent: () -> Boolean,
    private val publishScreen: () -> Unit,
) {

    private var mine: Stars? = null
    private var confirmed: Stars? = null
    private var others: List<OtherRating> = emptyList()
    private var average: HouseholdAverage? = null
    private var error: UiText? = null
    private var editor: RatingEditor? = null
    private var editorWatch: Job? = null

    /** The "Puntuaciones" list (all other profiles), opened by `rating_more`. */
    var othersDialog: ListDialogUi? = null
        private set

    init {
        scope.launch {
            ratings.changes.collect { change ->
                if (change.kind != kind || change.id != id) return@collect
                mine = change.stars
                confirmed = change.stars
                publish()
                refetch()
            }
        }
        scope.launch {
            ratings.writing.map { RatingKey(kind, id) in it }.distinctUntilChanged().drop(1).collect { publish() }
        }
    }

    private fun publish() {
        if (showsContent()) publishScreen()
    }

    /** The open manual dialog, or null. */
    val dialog: RatingDialogUi? get() = editor?.ui?.value

    /** The open manual dialog's keys and buttons (no-ops once it closed). */
    fun step(delta: Int) {
        editor?.step(delta)
    }

    fun save() {
        editor?.save()
    }

    fun secondary() {
        editor?.secondary()
    }

    fun remove() {
        editor?.remove()
    }

    fun dismiss() {
        editor?.dismiss()
    }

    /** A fetched detail: a write still in flight keeps the shown value until it settles. */
    fun fetched(myRating: Double?, others: List<OtherRating>, average: HouseholdAverage?) {
        confirmed = Stars.of(myRating)
        if (!ratings.isWriting(kind, id)) mine = confirmed
        this.others = others
        this.average = average
    }

    fun row(): RatingsRowUi {
        val colors = avatars()
        val shown = others.mapNotNull { o ->
            val stars = Stars.of(o.stars) ?: return@mapNotNull null
            val avatar = AvatarUi.of(o.profile.name, o.profile.avatar_id, colors)
            OtherRatingUi(o.profile.name, avatar.initial, avatar.color, stars)
        }
        return RatingsRowUi(
            mine = mine,
            mineEnabled = !ratings.isWriting(kind, id),
            average = average?.let { a -> Stars.of(a.stars)?.let { AverageUi(it, UiText.Res(R.string.rating_average_value, listOf(Format.rating(a.stars), Format.count(a.count)))) } },
            others = shown,
            moreCount = (shown.size - RatingsRowUi.SHOWN_OTHERS).coerceAtLeast(0),
            error = error,
        )
    }

    /** OK on `rating_mine` ([Puntuar] / [Cambiar]): the manual dialog on the current value. Ignored while a write is in flight. */
    fun open(title: String, poster: String?) {
        if (editor != null || ratings.isWriting(kind, id)) return
        error = null
        val e = RatingEditor(
            kind, id, RatingDialogUi.Mode.MANUAL, title, poster, mine, ratings, scope,
            onSaving = { stars ->
                mine = stars
                publish()
            },
            onRemoving = {
                mine = null
                publish()
            },
            onSaved = { stars -> confirmed = stars },
            onRemoved = { confirmed = null },
            onFailed = { _, removal ->
                mine = confirmed
                if (removal) error = UiText.Res(R.string.rating_update_failed)
                publish()
            },
            onGone = { cause -> (cause as? ApiException)?.let(onScreenError) },
            onClosed = {
                editor = null
                editorWatch?.cancel()
                onDialogClosed()
            },
        )
        editor = e
        editorWatch = scope.launch { e.ui.collect { publish() } }
    }

    /** OK on `rating_more`: every other profile, "Ana · 4,5 estrellas", no check mark. */
    fun openOthers() {
        error = null
        othersDialog = ListDialogUi(
            title = UiText.Res(R.string.rating_others_title),
            items = row().others.map { UiText.Res(R.string.pair_dot, listOf(it.name, starsText(it.stars))) },
            selected = -1,
            opener = ButtonId.RATING_MORE,
        )
    }

    fun closeOthers() {
        othersDialog = null
    }

    /** The next action hides the failure line. Returns true when there was one. */
    fun clearError(): Boolean {
        if (error == null) return false
        error = null
        return true
    }
}
