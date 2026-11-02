package com.hometv.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.core.util.Format
import com.hometv.tv.ui.components.parseColor
import com.hometv.tv.ui.ratings.StarRow
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens
import com.hometv.tv.ui.theme.HomeTvFocus

/**
 * The ratings block of Film and Show detail, tag `rating_block`:
 * ```
 * line 1: [ Tu puntuación ★★★★☆ Cambiar ]  Media de la familia ★★★½☆ 3,5 (3)
 * line 2: (A) Ana ★★★★½   (L) Luis ★★   [ y 2 más ]
 * ```
 * 40 dp lines, 8 dp apart; line 2 only when another profile rated. Only `rating_mine` and
 * `rating_more` are focusable; the screens give their D-pad targets ([mineFocus], [moreFocus]). Only
 * the other profiles' names shrink (ellipsized, at most 140 dp each).
 */
@Composable
fun RatingsBlock(
    row: RatingsRowUi,
    mine: FocusRequester,
    more: FocusRequester,
    onMine: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    mineFocus: FocusProperties.() -> Unit = {},
    moreFocus: FocusProperties.() -> Unit = {},
    /** Down on `rating_mine` / `rating_more` before focus search (true = handled), e.g. a lazy target scrolled in first. */
    onMineDown: (() -> Boolean)? = null,
    onMoreDown: (() -> Boolean)? = null,
) {
    Column(modifier.testTag("rating_block"), verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
        Row(Modifier.height(LINE_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            val description = row.mineDescription.resolve()
            Chip(
                onClick = onMine,
                enabled = row.mineEnabled,
                modifier = Modifier.focusRequester(mine).focusProperties(mineFocus).onDown(onMineDown).semantics { contentDescription = description }.testTag("rating_mine"),
            ) {
                if (row.mine == null) {
                    Text(stringResource(R.string.rating_rate))
                } else {
                    Text(stringResource(R.string.rating_mine), maxLines = 1)
                    StarRow(
                        row.mine,
                        STAR_SIZE,
                        Modifier.padding(horizontal = 8.dp).alpha(if (row.mineEnabled) 1f else RatingChipColors.DISABLED_ALPHA),
                        spacing = 1.dp,
                        emptyColor = DETAIL_EMPTY_STAR,
                        described = false,
                    )
                    Text(stringResource(R.string.rating_change), maxLines = 1)
                }
            }
            row.average?.let { average ->
                Row(Modifier.padding(start = 24.dp).testTag("rating_average"), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.rating_average),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DetailText.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StarRow(average.stars, STAR_SIZE, Modifier.padding(horizontal = 8.dp), spacing = 1.dp, emptyColor = DETAIL_EMPTY_STAR)
                    Text(average.text.resolve(), style = MaterialTheme.typography.bodyMedium, color = DetailText.secondary, maxLines = 1)
                }
            }
        }
        if (row.others.isNotEmpty()) {
            Row(Modifier.height(LINE_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                row.others.take(RatingsRowUi.SHOWN_OTHERS).forEachIndexed { i, other ->
                    if (i > 0) Spacer(Modifier.width(24.dp))
                    Box(Modifier.size(AVATAR_SIZE).background(parseColor(other.color) ?: HomeTvColors.SurfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                        Text(other.initial, style = MaterialTheme.typography.bodySmall, color = HomeTvColors.OnBackground)
                    }
                    Text(
                        other.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false).widthIn(max = NAME_MAX_WIDTH).testTag("rating_other_$i"),
                    )
                    StarRow(other.stars, STAR_SIZE, Modifier.padding(start = 8.dp), spacing = 1.dp, emptyColor = DETAIL_EMPTY_STAR)
                }
                if (row.moreCount > 0) {
                    Spacer(Modifier.width(24.dp))
                    Chip(onClick = onMore, enabled = true, modifier = Modifier.focusRequester(more).focusProperties(moreFocus).onDown(onMoreDown).testTag("rating_more")) {
                        Text(stringResource(R.string.rating_more, Format.count(row.moreCount)), maxLines = 1)
                    }
                }
            }
        }
        row.error?.let {
            Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = DetailText.secondary, maxLines = 1, modifier = Modifier.testTag("rating_error_line"))
        }
    }
}

private fun Modifier.onDown(handler: (() -> Boolean)?): Modifier =
    if (handler == null) this else onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && handler() }

/** The block's focusable chips: `ActionButton`'s look (pill, 40 dp, focus scale and ring) with free content. */
@Composable
private fun Chip(onClick: () -> Unit, enabled: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(LINE_HEIGHT),
        scale = ButtonDefaults.scale(focusedScale = HomeTvDimens.FOCUSED_SCALE),
        colors = ButtonDefaults.colors(
            containerColor = RatingChipColors.container,
            contentColor = RatingChipColors.content,
            focusedContainerColor = RatingChipColors.focusedContainer,
            focusedContentColor = RatingChipColors.focusedContent,
            pressedContainerColor = RatingChipColors.focusedContainer,
            pressedContentColor = RatingChipColors.focusedContent,
            disabledContainerColor = RatingChipColors.container.copy(alpha = RatingChipColors.container.alpha * RatingChipColors.DISABLED_ALPHA),
            disabledContentColor = RatingChipColors.content.copy(alpha = RatingChipColors.DISABLED_ALPHA),
        ),
        border = ButtonDefaults.border(focusedBorder = Border(HomeTvFocus.ring.border, shape = RoundedCornerShape(percent = 50))),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/**
 * The chips' colours: tv-material's default focused fill is near-white, where the amber and light
 * stars vanish (DetailLegibilityTest). Focus shows as the scale and the white ring instead.
 */
object RatingChipColors {
    val container = HomeTvColors.SurfaceVariant.copy(alpha = 0.8f)
    val focusedContainer = HomeTvColors.SurfaceVariant
    val content = HomeTvColors.OnBackground
    val focusedContent = HomeTvColors.OnBackground

    /** A disabled chip (a write in flight) dims its text and its stars alike. */
    const val DISABLED_ALPHA = 0.4f
}

/** Empty star outlines over the backdrop: [HomeTvColors.Muted] is under 3:1 over white art (DetailLegibilityTest). */
val DETAIL_EMPTY_STAR = DetailText.secondary

private val LINE_HEIGHT = 40.dp
private val LINE_GAP = 8.dp
private val STAR_SIZE = 20.dp
private val AVATAR_SIZE = 28.dp
private val NAME_MAX_WIDTH = 140.dp
