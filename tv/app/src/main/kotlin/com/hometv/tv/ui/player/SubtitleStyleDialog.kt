package com.hometv.tv.ui.player

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.core.playback.StyleRow
import com.hometv.tv.core.playback.SubtitleColor
import com.hometv.tv.core.playback.SubtitleEdge
import com.hometv.tv.core.playback.SubtitleEdgeColor
import com.hometv.tv.core.playback.SubtitleLook
import com.hometv.tv.core.playback.SubtitlePosition
import com.hometv.tv.core.playback.SubtitleSize
import com.hometv.tv.core.playback.SubtitleStyleEditor
import com.hometv.tv.ui.components.RequestFocus
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens

/**
 * "Formato de los subtítulos" (prd3 FR-T10): five rows (label, value between two chevrons; Left/Right step
 * through `SubtitleStyleEditor`, clash values skipped, the ends stop; OK does nothing), then the sample line.
 * No dim and aligned top-centre inside the safe area, so the live Exo subtitles below it preview the draft at
 * their real height (the overlay is hidden meanwhile). "Color del contorno" is dimmed and skipped with "Sin
 * contorno". Back = [onClose] (closing saves: there is no Save button). Initial focus: Tamaño.
 */
@Composable
fun SubtitleStyleDialog(ui: SubtitleStyleUi, onStep: (StyleRow, Boolean) -> Unit, onClose: () -> Unit) {
    val requesters = remember { StyleRow.entries.associateWith { FocusRequester() } }
    val look = ui.draft
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize().padding(top = HomeTvDimens.SafeVertical), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.width(DIALOG_WIDTH)
                    .testTag("subtitle_style_dialog")
                    .background(HomeTvColors.Surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    stringResource(R.string.subtitle_style_title),
                    style = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
                    maxLines = 1,
                    modifier = Modifier.height(24.dp),
                )
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    StyleRow.entries.forEach { row ->
                        StyleRowItem(look, row, requesters.getValue(row), onStep)
                    }
                }
                Spacer(Modifier.height(4.dp))
                SubtitleSample(look)
            }
        }
    }
    RequestFocus(Unit) { requesters.getValue(StyleRow.SIZE) }
}

@Composable
private fun StyleRowItem(look: SubtitleLook, row: StyleRow, requester: FocusRequester, onStep: (StyleRow, Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val enabled = SubtitleStyleEditor.isEnabled(look, row)
    val value = SubtitleStyleEditor.value(look, row)
    val label = stringResource(rowLabel(row))
    val valueText = stringResource(valueLabel(value))
    val description = stringResource(R.string.subtitle_style_row_description, label, valueText)
    val scale = if (focused) HomeTvDimens.WIDE_FOCUSED_SCALE else 1f
    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else DISABLED_ALPHA
            }
            .focusRequester(requester)
            .focusProperties { canFocus = enabled }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val forward = when (event.key) {
                    Key.DirectionRight -> true
                    Key.DirectionLeft -> false
                    else -> return@onKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) onStep(row, forward)
                true
            }
            .focusable(enabled)
            .background(if (focused) HomeTvColors.SurfaceVariant else Color.Transparent, RoundedCornerShape(6.dp))
            .let { if (focused) it.border(2.dp, HomeTvColors.FocusRing, RoundedCornerShape(6.dp)) else it }
            .semantics { contentDescription = description }
            .testTag("style_row_${row.name.lowercase()}")
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TextStyle(fontSize = 18.sp, lineHeight = 22.sp), maxLines = 1, modifier = Modifier.weight(1f))
        Row(Modifier.width(VALUE_WIDTH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Chevron(R.drawable.ic_chevron_left, SubtitleStyleEditor.canStep(look, row, forward = false))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                swatch(value)?.let { color ->
                    Box(Modifier.size(SWATCH).background(color, CircleShape).border(1.dp, HomeTvColors.Muted, CircleShape))
                    Spacer(Modifier.width(8.dp))
                }
                Text(valueText, style = TextStyle(fontSize = 18.sp, lineHeight = 22.sp), maxLines = 1, textAlign = TextAlign.Center)
            }
            Chevron(R.drawable.ic_chevron_right, SubtitleStyleEditor.canStep(look, row, forward = true))
        }
    }
}

@Composable
private fun Chevron(@DrawableRes icon: Int, active: Boolean) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp).alpha(if (active) 1f else DISABLED_ALPHA))
}

/**
 * "Así se verán los subtítulos" on a translucent dark band, at the real subtitle size (0.0533 × factor of the
 * screen height): the text twice (an edge-coloured stroke, then the fill) for Contorno, a drop shadow for Sombra,
 * plain for Sin contorno.
 */
@Composable
fun SubtitleSample(look: SubtitleLook, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // The display's height in dp (the Exo SubtitleView fills it), read from the metrics: inside the dialog the window is not the screen.
    val screenHeight = LocalContext.current.resources.displayMetrics.let { it.heightPixels / it.density }
    val fontSize = with(density) { (SIZE_FRACTION * screenHeight * look.size.factor).dp.toSp() }
    val text = stringResource(R.string.subtitle_style_sample)
    val fill = Color(OPAQUE or look.color.rgb)
    val edge = Color(OPAQUE or look.effectiveEdgeColor.rgb)
    val base = TextStyle(fontSize = fontSize, lineHeight = fontSize * 1.15f, textAlign = TextAlign.Center)
    val px = with(density) { 2.dp.toPx() }
    Box(
        modifier.fillMaxWidth().height(if (look.size == SubtitleSize.LARGE) 48.dp else 44.dp)
            .background(Color(0x99000000), RoundedCornerShape(6.dp))
            .testTag("style_sample"),
        contentAlignment = Alignment.Center,
    ) {
        when (look.edge) {
            SubtitleEdge.OUTLINE -> {
                Text(text, style = base.copy(color = edge, drawStyle = Stroke(width = px)), maxLines = 1)
                Text(text, style = base.copy(color = fill), maxLines = 1)
            }
            SubtitleEdge.SHADOW -> Text(text, style = base.copy(color = fill, shadow = Shadow(edge, Offset(px, px), blurRadius = px)), maxLines = 1)
            SubtitleEdge.NONE -> Text(text, style = base.copy(color = fill), maxLines = 1)
        }
    }
}

@StringRes
private fun rowLabel(row: StyleRow): Int = when (row) {
    StyleRow.SIZE -> R.string.subtitle_style_size
    StyleRow.COLOR -> R.string.subtitle_style_color
    StyleRow.EDGE -> R.string.subtitle_style_edge
    StyleRow.EDGE_COLOR -> R.string.subtitle_style_edge_color
    StyleRow.POSITION -> R.string.subtitle_style_position
}

@StringRes
private fun valueLabel(value: Enum<*>): Int = when (value) {
    SubtitleSize.SMALL -> R.string.subtitle_size_small
    SubtitleSize.MEDIUM -> R.string.subtitle_size_medium
    SubtitleSize.LARGE -> R.string.subtitle_size_large
    SubtitleColor.WHITE -> R.string.subtitle_color_white
    SubtitleColor.YELLOW -> R.string.subtitle_color_yellow
    SubtitleColor.GREEN -> R.string.subtitle_color_green
    SubtitleColor.CYAN -> R.string.subtitle_color_cyan
    SubtitleColor.MAGENTA -> R.string.subtitle_color_magenta
    SubtitleColor.ORANGE -> R.string.subtitle_color_orange
    SubtitleEdge.OUTLINE -> R.string.subtitle_edge_outline
    SubtitleEdge.SHADOW -> R.string.subtitle_edge_shadow
    SubtitleEdge.NONE -> R.string.subtitle_edge_none
    SubtitleEdgeColor.BLACK -> R.string.subtitle_edge_color_black
    SubtitleEdgeColor.DARK_GRAY -> R.string.subtitle_edge_color_dark_gray
    SubtitleEdgeColor.WHITE -> R.string.subtitle_edge_color_white
    SubtitlePosition.LOW -> R.string.subtitle_position_low
    SubtitlePosition.MIDDLE -> R.string.subtitle_position_middle
    SubtitlePosition.HIGH -> R.string.subtitle_position_high
    else -> error("no label for $value")
}

/** The colour rows' swatch. */
private fun swatch(value: Enum<*>): Color? = when (value) {
    is SubtitleColor -> Color(OPAQUE or value.rgb)
    is SubtitleEdgeColor -> Color(OPAQUE or value.rgb)
    else -> null
}

private const val OPAQUE = 0xFF000000.toInt()
private const val SIZE_FRACTION = 0.0533f
private const val DISABLED_ALPHA = 0.4f
private val DIALOG_WIDTH = 640.dp
private val ROW_HEIGHT = 32.dp
private val VALUE_WIDTH = 280.dp
private val SWATCH = 24.dp
