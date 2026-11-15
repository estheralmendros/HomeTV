package com.hometv.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.core.util.Format
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.Artwork
import com.hometv.tv.ui.components.RequestFocus
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.text.LocalUiFormat
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors

/**
 * Next-episode countdown "A continuación · T2 · E4 Halloween — Empieza en 10" (data from the
 * prefetched `EpisodeDetail`) or the part prompt "Continuando con la parte 2" (5 s); stopped
 * (`EndedIdle`) without a number. [Reproducir ahora] is focused, [Cancelar] exits.
 */
@Composable
fun NextEpisodeCountdown(next: NextUi, onPlayNow: () -> Unit, onCancel: () -> Unit) {
    val requesters = rememberFocusRequesters<Int>()
    val format = LocalUiFormat.current
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).testTag("countdown"), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier.padding(48.dp).width(520.dp).background(HomeTvColors.Surface, RoundedCornerShape(12.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val part = next.partNumber?.let(Format::count)
            val heading = when {
                next.kind == NextKind.PART && next.seconds != null -> stringResource(R.string.part_prompt, part.orEmpty())
                next.kind == NextKind.PART -> stringResource(R.string.part_prompt_stopped, part.orEmpty())
                else -> stringResource(R.string.countdown_title)
            }
            Text(heading, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("countdown_heading"))
            if (next.kind == NextKind.EPISODE) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(next.still, null, Modifier.size(width = 160.dp, height = 90.dp))
                    Column {
                        next.code?.let { Text(format.episodeCode(it), style = MaterialTheme.typography.titleMedium) }
                        next.title?.let { Text(it.resolve(), modifier = Modifier.testTag("countdown_title")) }
                    }
                }
            }
            next.seconds?.let { Text(stringResource(R.string.countdown_starts_in, Format.count(it)), modifier = Modifier.testTag("countdown_seconds")) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionButton(stringResource(R.string.play_now), onPlayNow, Modifier.focusRequester(requesters[0]).testTag("countdown_play_now"), icon = R.drawable.ic_play)
                ActionButton(stringResource(R.string.action_cancel), onCancel, Modifier.focusRequester(requesters[1]).testTag("countdown_cancel"))
            }
        }
    }
    RequestFocus(next.kind to next.partNumber) { requesters[0] }
}
