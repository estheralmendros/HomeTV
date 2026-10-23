package com.hometv.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.theme.HomeTvColors

/** A single-choice list dialog (Versiones, Partes, Versiones de la parte); focus starts on the selected entry. */
@Composable
fun ListDialog(title: String, items: List<String>, selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val requesters = rememberFocusRequesters<Int>()
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(520.dp).background(HomeTvColors.Surface, RoundedCornerShape(12.dp)).padding(24.dp).testTag("list_dialog")) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
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
    RequestFocus(selected) { requesters[selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))] }
}
