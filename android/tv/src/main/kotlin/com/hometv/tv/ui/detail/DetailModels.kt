package com.hometv.tv.ui.detail

import com.hometv.tv.R
import com.hometv.tv.core.api.CastMember
import com.hometv.tv.core.util.Stars
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.text.UiText
import com.hometv.tv.ui.text.starsText

/**
 * The action buttons of the detail screens, in display order. [RATING_MINE] / [RATING_MORE] are the
 * ratings block's chips: focus and dialog-opener ids only, never in a button list.
 */
enum class ButtonId { RESUME, PLAY_FROM_START, PLAY, PLAY_PART, WATCHED, WATCHLIST, VERSION, PART, PART_VERSION, RATING_MINE, RATING_MORE }

/** [description] is the accessible name when it differs from [text] ("Mi lista" → "Añadir a Mi lista"). */
data class DetailButton(val id: ButtonId, val text: UiText, val enabled: Boolean = true, val description: UiText? = null)

/** The "Mi lista" button of Film and Show detail: the label and accessible name follow [inList]. */
fun watchlistButton(inList: Boolean) = DetailButton(
    ButtonId.WATCHLIST,
    UiText.Res(if (inList) R.string.watchlist_in_list else R.string.watchlist_toggle),
    description = UiText.Res(if (inList) R.string.watchlist_remove else R.string.watchlist_add),
)

/** The "Mi lista" icon: a check while the title is in the list, else a "+". */
fun watchlistIcon(inList: Boolean): Int = if (inList) R.drawable.ic_check else R.drawable.ic_add

/** A list dialog (Versions, Parts, Part versions); closing it returns focus to [opener]. */
data class ListDialogUi(val title: UiText, val items: List<UiText>, val selected: Int, val opener: ButtonId)

/** The Film detail cast card that opened the Person screen (focus restored on Back); [index] scrolls the row to it. */
data class CastFocus(val key: String, val index: Int)

/** The cast row's item key and test-tag suffix ("3:0"): unique even when a person is credited twice. */
fun castKey(member: CastMember, index: Int): String = "${member.person_id}:$index"

/**
 * A failed "Mi lista" write whose cause replaces the screen: 404
 * `not_found` (ItemGone), a 401 after the renewal (SignIn), a gone profile. Any other cause rolls back inline.
 */
fun ScreenError.replacesScreen(): Boolean = this == ScreenError.ItemGone || this == ScreenError.SignIn || this == ScreenError.ProfileGone

/** Another profile on line 2 of the ratings block: name, avatar initial and colour (`#RRGGBB`, null = fallback), stars. */
data class OtherRatingUi(val name: String, val initial: String, val color: String?, val stars: Stars)

/** The household average: [stars] rounded to ½ for the icons, [text] "4,0 (3)" (one decimal, then the count). */
data class AverageUi(val stars: Stars, val text: UiText)

/**
 * The two-line ratings block of Film and Show detail. [mineEnabled] is false
 * while a write for the title is in flight (the chip stays focusable, OK does nothing). [others] are
 * newest first; line 2 shows two of them, [moreCount] the rest ("y N más"). [error] = "No se ha podido
 * actualizar tu puntuación." after a failed removal, until the next action.
 */
data class RatingsRowUi(
    val mine: Stars?,
    val mineEnabled: Boolean,
    val average: AverageUi?,
    val others: List<OtherRatingUi>,
    val moreCount: Int,
    val error: UiText?,
) {
    /** "Tu puntuación: 4 estrellas. Cambiar", or "Puntuar". */
    val mineDescription: UiText
        get() = if (mine == null) UiText.Res(R.string.rating_rate) else UiText.Res(R.string.rating_mine_description, listOf(starsText(mine), UiText.Res(R.string.rating_change)))

    companion object {
        /** Others shown on line 2; the rest go to the "Puntuaciones" list. */
        const val SHOWN_OTHERS = 2

        val EMPTY = RatingsRowUi(null, mineEnabled = true, average = null, others = emptyList(), moreCount = 0, error = null)
    }
}
