package com.hometv.tv.ui.settings

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.core.util.Format
import com.hometv.tv.ui.components.RequestFocus
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens
import com.hometv.tv.ui.theme.HomeTvFocus

/** The keypad rows: digits, then ⌫ and 0 (the 4th digit submits, so there is no OK key). */
private val KEYS = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(DELETE, "0"))
private const val DELETE = "delete"

/**
 * The admin PIN dialog before a server change: 4 dots, a D-pad keypad and the
 * remote's digit keys; Back dismisses. The ViewModel ignores input while checking or locked.
 */
@Composable
fun PinDialog(pin: PinUi, onDigit: (Int) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val requesters = rememberFocusRequesters<String>()
    // No platform dialog sizing: its window insets made the dialog taller than a TV screen.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(400.dp)
                .background(HomeTvColors.Surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .testTag("pin_dialog")
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (val code = event.key.nativeKeyCode) {
                        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> onDigit(code - AndroidKeyEvent.KEYCODE_0)
                        in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> onDigit(code - AndroidKeyEvent.KEYCODE_NUMPAD_0)
                        AndroidKeyEvent.KEYCODE_DEL -> onDelete()
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.pin_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(stringResource(R.string.pin_subtitle), style = MaterialTheme.typography.bodyMedium, color = HomeTvColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            val entered = stringResource(R.string.pin_entered, Format.count(pin.entered))
            Row(Modifier.padding(top = 16.dp).semantics { contentDescription = entered }.testTag("pin_dots"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(PIN_DOTS) { i ->
                    val dot = Modifier.size(16.dp)
                    Box(if (i < pin.entered) dot.background(HomeTvColors.OnBackground, CircleShape) else dot.border(BorderStroke(2.dp, HomeTvColors.Muted), CircleShape))
                }
            }
            Box(Modifier.padding(top = 8.dp).size(width = 352.dp, height = MESSAGE_HEIGHT), contentAlignment = Alignment.Center) {
                pin.message?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = HomeTvColors.Error, textAlign = TextAlign.Center, modifier = Modifier.testTag("pin_message")) }
            }
            // Locked (429): the keys are disabled and dimmed but stay focusable, so focus stays in the dialog.
            val enabled = !pin.locked
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(KEY_SPACING)) {
                KEYS.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(KEY_SPACING)) {
                        row.forEach { key ->
                            if (key == DELETE) {
                                PinKey(stringResource(R.string.pin_delete), enabled, Modifier.focusRequester(requesters[key]).testTag("pin_key_delete"), onDelete)
                            } else {
                                PinKey(key, enabled, Modifier.focusRequester(requesters[key]).testTag("pin_key_$key")) { onDigit(key.toInt()) }
                            }
                        }
                        if (row.size < 3) Spacer(Modifier.size(KEY_SIZE))
                    }
                }
            }
        }
    }
    RequestFocus(Unit) { requesters["1"] }
}

@Composable
private fun PinKey(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(KEY_SIZE),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = HomeTvColors.SurfaceVariant,
            disabledContainerColor = HomeTvColors.SurfaceVariant.copy(alpha = DISABLED_ALPHA),
            disabledContentColor = HomeTvColors.OnBackground.copy(alpha = DISABLED_ALPHA),
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = HomeTvFocus.ring),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HomeTvDimens.FOCUSED_SCALE),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = if (label.length == 1) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge)
        }
    }
}

// Sized so the dialog fits a TV's safe area with real text metrics (ServerChangePinTest, native graphics).
private val KEY_SIZE = 52.dp
private val KEY_SPACING = 8.dp
private val MESSAGE_HEIGHT = 20.dp
private const val DISABLED_ALPHA = 0.4f
private const val PIN_DOTS = 4
