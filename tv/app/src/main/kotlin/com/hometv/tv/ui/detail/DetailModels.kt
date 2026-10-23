package com.hometv.tv.ui.detail

import com.hometv.tv.ui.text.UiText

/** The action buttons of the detail screens, in display order. */
enum class ButtonId { RESUME, PLAY_FROM_START, PLAY, PLAY_PART, WATCHED, VERSION, PART, PART_VERSION }

data class DetailButton(val id: ButtonId, val text: UiText, val enabled: Boolean = true)

/** A list dialog (Versions, Parts, Part versions); closing it returns focus to [opener]. */
data class ListDialogUi(val title: UiText, val items: List<UiText>, val selected: Int, val opener: ButtonId)
