package com.hometv.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.ui.theme.HomeTvColors
import kotlinx.coroutines.launch

/**
 * A read-only text dialog (the Person biography, cast-appearances prd3 FR-T9): title, then the full
 * text in a focusable scroll area that takes focus on open; Up/Down scroll it by 3/4 of its height.
 * Back closes it ([onDismiss]); there is no button, as in [ListDialog].
 */
@Composable
fun TextDialog(title: String, text: String, onDismiss: () -> Unit, textTestTag: String = "text_dialog") {
    // No platform dialog sizing: its window insets made tall dialogs overflow a TV screen (see ListDialog).
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val area = remember { FocusRequester() }
        var viewport by remember { mutableIntStateOf(0) }
        Column(Modifier.width(WIDTH).heightIn(max = MAX_HEIGHT).background(HomeTvColors.Surface, RoundedCornerShape(12.dp)).padding(24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            Column(
                Modifier
                    .onSizeChanged { viewport = it.height }
                    .focusRequester(area)
                    .onPreviewKeyEvent { event ->
                        val direction = when (event.key) {
                            Key.DirectionDown -> 1f
                            Key.DirectionUp -> -1f
                            else -> return@onPreviewKeyEvent false
                        }
                        if (event.type == KeyEventType.KeyDown) {
                            val delta = direction * viewport * SCROLL_FRACTION
                            // A held key repeats faster than the animation: each new animateScrollBy would cancel
                            // the previous one (jerky), so repeats scroll at once.
                            scope.launch { if (event.nativeKeyEvent.repeatCount > 0) scroll.scrollBy(delta) else scroll.animateScrollBy(delta) }
                        }
                        true
                    }
                    .focusable()
                    .testTag(textTestTag)
                    .verticalScroll(scroll),
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = HomeTvColors.OnBackground)
            }
        }
        LaunchedEffect(Unit) { area.requestFocusWhenReady() }
    }
}

private val WIDTH = 640.dp

/** Same budget as [ListDialog]: the 540 dp screen minus the safe areas and the dialog-window insets. */
private val MAX_HEIGHT = 400.dp
private const val SCROLL_FRACTION = 0.75f
