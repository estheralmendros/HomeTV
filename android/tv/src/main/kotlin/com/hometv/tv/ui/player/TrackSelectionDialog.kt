package com.hometv.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.RequestFocus
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors

/**
 * "Audio y subtítulos": two columns, "Audio" (initial focus on the current track) and
 * "Subtítulos" ("Desactivados" first; a pending track shows "Preparando los subtítulos…"), then the
 * full-width "Formato de los subtítulos" button ([onStyle]: the style dialog). Down from the last item of either
 * column reaches the button, Up returns to the item it came from. Selecting a track applies at once and closes
 * the dialog; Back closes it without change. The columns take the height left by the button (they scroll), so
 * the button stays inside the dialog with many tracks.
 */
@Composable
fun TrackSelectionDialog(tracks: TrackDialogUi, onAudio: (String) -> Unit, onSubtitle: (String) -> Unit, onStyle: () -> Unit, onDismiss: () -> Unit) {
    val requesters = rememberFocusRequesters<String>()
    val styleButton = remember { FocusRequester() }
    var lastFocused by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.heightIn(max = MAX_HEIGHT).background(HomeTvColors.Surface, RoundedCornerShape(12.dp)).padding(24.dp).testTag("track_dialog")) {
            Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
                TrackColumn(stringResource(R.string.track_dialog_audio), tracks.audio, "track_audio_", requesters, onAudio) { lastFocused = it }
                TrackColumn(stringResource(R.string.track_dialog_subtitles), tracks.subtitles, "track_sub_", requesters, onSubtitle) { lastFocused = it }
            }
            Spacer(Modifier.height(16.dp))
            ActionButton(
                stringResource(R.string.subtitle_style_title),
                onClick = onStyle,
                modifier = Modifier.width(COLUMN_WIDTH * 2 + COLUMN_GAP)
                    .focusRequester(styleButton)
                    .focusProperties { lastFocused?.let { up = requesters[it] } }
                    .testTag("track_style"),
                icon = R.drawable.ic_subtitles,
            )
        }
    }
    val initial = tracks.audio.firstOrNull { it.selected } ?: tracks.audio.firstOrNull()
    val initialKey = initial?.let { "track_audio_${it.key}" } ?: tracks.subtitles.firstOrNull { it.selected }?.let { "track_sub_${it.key}" }
    RequestFocus(initialKey) { initialKey?.let { requesters[it] } ?: styleButton }
}

@Composable
private fun TrackColumn(
    title: String,
    options: List<TrackOption>,
    tagPrefix: String,
    requesters: com.hometv.tv.ui.components.FocusRequesters<String>,
    onSelect: (String) -> Unit,
    onFocused: (String) -> Unit,
) {
    Column(Modifier.width(COLUMN_WIDTH)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
        LazyColumn {
            items(options, key = { it.key }) { option ->
                val tag = tagPrefix + option.key
                ListItem(
                    selected = option.selected,
                    onClick = { onSelect(option.key) },
                    headlineContent = { Text(option.label.resolve()) },
                    supportingContent = if (option.pending) {
                        { Text(stringResource(R.string.player_subtitles_preparing), color = HomeTvColors.Muted) }
                    } else null,
                    trailingContent = if (option.selected) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp)) }
                    } else null,
                    modifier = Modifier.focusRequester(requesters[tag]).onFocusChanged { if (it.isFocused) onFocused(tag) }.testTag(tag),
                )
            }
        }
    }
}

private val COLUMN_WIDTH = 420.dp
private val COLUMN_GAP = 24.dp

/** The 540 dp screen minus the two 27 dp safe areas and the room a TV's dialog-window insets take (`UiTestBase.DIALOG_WINDOW_INSETS`). */
private val MAX_HEIGHT = 406.dp
