package com.hometv.tv.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.components.Artwork
import com.hometv.tv.ui.components.parseColor
import com.hometv.tv.ui.ratings.StarRow
import com.hometv.tv.ui.text.LocalUiFormat
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.text.starsText
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens
import com.hometv.tv.ui.theme.HomeTvFocus

/**
 * One Actividad row (ratings prd3 FR-T16), 72 dp, focusable: avatar, "**Luis** ha puntuado **Dune**
 * con" (or "Has puntuado **Dune** con") + 20 dp stars, the relative time (muted, right-aligned) and the
 * poster. Only the title shrinks (ellipsized); one accessible name with the stars and the time. The
 * content fills the row height, so its items centre in the focused fill and ring (a wrapped Row sits at
 * the Surface's top: 60 dp of poster in 72 dp, 6 dp too high).
 */
@Composable
fun ActivityRow(row: ActivityRowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val format = LocalUiFormat.current
    val stars = starsText(row.stars).resolve()
    val time = row.time?.let(format::arg)
    val description = when {
        row.own && time != null -> stringResource(R.string.activity_row_own_description, row.title, stars, time)
        row.own -> stringResource(R.string.activity_row_own_description_untimed, row.title, stars)
        time != null -> stringResource(R.string.activity_row_description, row.name, row.title, stars, time)
        else -> stringResource(R.string.activity_row_description_untimed, row.name, row.title, stars)
    }
    // The sentence template with markers in place of the bold arguments, split into its parts.
    val template = if (row.own) stringResource(R.string.activity_row_own, TITLE) else stringResource(R.string.activity_row, NAME, TITLE)
    val parts = remember(template) { sentenceParts(template) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(ROW_HEIGHT).semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = HomeTvColors.SurfaceVariant),
        border = ClickableSurfaceDefaults.border(focusedBorder = HomeTvFocus.ring),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HomeTvDimens.WIDE_FOCUSED_SCALE),
    ) {
        Row(
            Modifier.testTag(CONTENT_TAG).fillMaxHeight().padding(horizontal = 12.dp).clearAndSetSemantics { },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(AVATAR_SIZE).background(parseColor(row.color) ?: HomeTvColors.SurfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(row.initial, style = MaterialTheme.typography.titleMedium, color = HomeTvColors.OnBackground)
            }
            Row(Modifier.padding(start = 16.dp).weight(1f), verticalAlignment = Alignment.CenterVertically) {
                parts.forEachIndexed { i, part ->
                    if (i > 0) Spacer(Modifier.width(WORD_SPACE))
                    when (part) {
                        NAME -> Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        TITLE -> Text(
                            row.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        else -> Text(part, style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false)
                    }
                }
                Spacer(Modifier.width(8.dp))
                StarRow(row.stars, STAR_SIZE)
            }
            if (time != null) {
                Text(time, style = MaterialTheme.typography.bodySmall, color = HomeTvColors.Muted, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
            }
            Artwork(row.poster, row.title, Modifier.padding(start = 16.dp).size(POSTER_WIDTH, POSTER_HEIGHT).clip(RoundedCornerShape(4.dp)))
        }
    }
}

/** The template's words around the [NAME] / [TITLE] markers, each marker its own part, spaces dropped. */
private fun sentenceParts(template: String): List<String> =
    template.split(MARKER_BOUNDARY).map { it.trim() }.filter { it.isNotEmpty() }

/** The row content (test tag): fills the row height so its items centre in the focus frame. */
const val CONTENT_TAG = "activity_row_content"
private const val NAME = "\u0001"
private const val TITLE = "\u0002"
private val MARKER_BOUNDARY = Regex("(?=[$NAME$TITLE])|(?<=[$NAME$TITLE])")
private val ROW_HEIGHT = 72.dp
private val AVATAR_SIZE = 40.dp
private val STAR_SIZE = 20.dp
private val POSTER_WIDTH = 40.dp
private val POSTER_HEIGHT = 60.dp
private val WORD_SPACE = 5.dp
