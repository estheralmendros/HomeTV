package com.hometv.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hometv.tv.R
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.RequestFocus
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors

/** Long-press on an episode (prd3 §7.5): [Ver desde el principio] [Marcar como visto | no visto]. */
@Composable
fun EpisodeActionsDialog(actions: EpisodeActionsUi, onPlayFromStart: () -> Unit, onToggleWatched: () -> Unit, onDismiss: () -> Unit) {
    val requesters = rememberFocusRequesters<Int>()
    Dialog(onDismissRequest = onDismiss) {
        Row(
            Modifier.background(HomeTvColors.Surface, RoundedCornerShape(12.dp)).padding(24.dp).testTag("episode_actions"),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ActionButton(stringResource(R.string.detail_play_from_start), onPlayFromStart, Modifier.focusRequester(requesters[0]).testTag("episode_play_from_start"), icon = R.drawable.ic_play)
            ActionButton(actions.watchedText.resolve(), onToggleWatched, Modifier.focusRequester(requesters[1]).testTag("episode_toggle_watched"))
        }
    }
    RequestFocus(actions.episodeId) { requesters[0] }
}
