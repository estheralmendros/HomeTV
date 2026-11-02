package com.hometv.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
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
import com.hometv.tv.ui.components.RequestFocus
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors

/**
 * "Audio y subtítulos": two columns, "Audio" (initial focus on the current track) and
 * "Subtítulos" ("Desactivados" first; a pending track shows "Preparando los subtítulos…"). Selecting
 * applies at once and closes the dialog; Back closes it without change.
 */
@Composable
fun TrackSelectionDialog(tracks: TrackDialogUi, onAudio: (String) -> Unit, onSubtitle: (String) -> Unit, onDismiss: () -> Unit) {
    val requesters = rememberFocusRequesters<String>()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.background(HomeTvColors.Surface, RoundedCornerShape(12.dp)).padding(24.dp).testTag("track_dialog"),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            TrackColumn(stringResource(R.string.track_dialog_audio), tracks.audio, "track_audio_", requesters, onAudio)
            TrackColumn(stringResource(R.string.track_dialog_subtitles), tracks.subtitles, "track_sub_", requesters, onSubtitle)
        }
    }
    val initial = tracks.audio.firstOrNull { it.selected } ?: tracks.audio.firstOrNull()
    val initialKey = initial?.let { "track_audio_${it.key}" } ?: tracks.subtitles.firstOrNull { it.selected }?.let { "track_sub_${it.key}" }
    RequestFocus(initialKey) { initialKey?.let { requesters[it] } }
}

@Composable
private fun TrackColumn(
    title: String,
    options: List<TrackOption>,
    tagPrefix: String,
    requesters: com.hometv.tv.ui.components.FocusRequesters<String>,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.width(420.dp)) {
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
                    modifier = Modifier.focusRequester(requesters[tag]).testTag(tag),
                )
            }
        }
    }
}
