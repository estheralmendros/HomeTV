package com.hometv.tv.ui.detail

import com.hometv.tv.R
import com.hometv.tv.core.api.CastMember
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.text.UiText

/** The action buttons of the detail screens, in display order. */
enum class ButtonId { RESUME, PLAY_FROM_START, PLAY, PLAY_PART, WATCHED, WATCHLIST, VERSION, PART, PART_VERSION }

/** [description] is the accessible name when it differs from [text] ("Mi lista" → "Añadir a Mi lista"). */
data class DetailButton(val id: ButtonId, val text: UiText, val enabled: Boolean = true, val description: UiText? = null)

/** The "Mi lista" button of Film and Show detail (watchlist prd3 FR-T5): the label and accessible name follow [inList]. */
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
 * A failed "Mi lista" write whose cause replaces the screen (watchlist prd3 FR-T6 step 3): 404
 * `not_found` (ItemGone), a 401 after the renewal (SignIn), a gone profile. Any other cause rolls back inline.
 */
fun ScreenError.replacesScreen(): Boolean = this == ScreenError.ItemGone || this == ScreenError.SignIn || this == ScreenError.ProfileGone
