package com.hometv.tv.ui.text

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.hometv.tv.R

/**
 * Text a ViewModel emits without holding UI words (prd3 §UI Language rule 2): a catalog id plus
 * arguments, resolved in Compose by [UiFormat]. Arguments may be pre-formatted `String`s (numbers
 * come from core `Format`), core structured values (`Runtime`, `EpisodeCode`, `SeasonTitle`,
 * `EpisodeTitle`, `LanguageName`, `TrackLabel`, `PreparingEstimate`) or nested [UiText]. Raw numbers
 * are rejected: `stringResource` would format them with the TV's system locale.
 */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** `pluralStringResource(id, count, *args)`: [count] picks the form, the pre-formatted args print it. */
    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText

    /** A core structured value (`Runtime`, `EpisodeCode`, `SeasonTitle`, `LanguageName`, `TrackLabel`…) on its own. */
    data class Value(val value: Any) : UiText

    /** Data shown as received (titles, labels, years, URLs): never a UI word. */
    data class Data(val text: String) : UiText

    /** Parts joined with a catalog separator (" · " by default). */
    data class Joined(val parts: List<UiText>, @param:StringRes val separator: Int = R.string.separator_dot) : UiText
}
