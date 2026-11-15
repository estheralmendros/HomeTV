package com.hometv.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.ui.theme.HomeTvColors

/** A centred title and one centred button row [confirm] [cancel]; focus starts on confirm. Back = onDismiss. */
@Composable
fun ConfirmDialog(title: String, confirmLabel: String, cancelLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val confirmRequester = remember { FocusRequester() }
    // No platform dialog sizing, like ListDialog: its window insets made dialogs overflow a TV screen.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(520.dp).testTag("confirm_dialog").background(HomeTvColors.Surface, RoundedCornerShape(12.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(confirmLabel, onConfirm, Modifier.focusRequester(confirmRequester).testTag("confirm_dialog_confirm"))
                ActionButton(cancelLabel, onDismiss, Modifier.testTag("confirm_dialog_cancel"))
            }
        }
    }
    RequestFocus(Unit) { confirmRequester }
}
