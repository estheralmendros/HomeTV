package com.hometv.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.theme.HomeTvColors

/**
 * A single-choice list dialog (Versiones, Partes, Ordenar, Género, Años, Estado…); focus starts on the
 * selected entry. Long lists scroll inside a dialog that always fits the TV screen's safe area.
 */
@Composable
fun ListDialog(title: String, items: List<String>, selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val requesters = rememberFocusRequesters<Int>()
    // No platform dialog sizing: its window insets made tall dialogs overflow a TV screen.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(520.dp).heightIn(max = MAX_HEIGHT).background(HomeTvColors.Surface, RoundedCornerShape(12.dp)).padding(24.dp).testTag("list_dialog")) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                items.forEachIndexed { i, item ->
                    ListItem(
                        selected = i == selected,
                        onClick = { onSelect(i) },
                        headlineContent = { Text(item) },
                        trailingContent = if (i == selected) {
                            { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp)) }
                        } else null,
                        modifier = Modifier.focusRequester(requesters[i]).testTag("dialog_item_$i"),
                    )
                }
            }
        }
    }
    RequestFocus(selected) { requesters[selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))] }
}

/** 540 dp screen − 2 × 27 dp safe area − ~80 dp of dialog-window insets on a TV (see the PIN dialog). */
private val MAX_HEIGHT = 400.dp
