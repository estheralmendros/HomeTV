package com.hometv.tv.ui.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.core.util.Format
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.components.requestFocusWhenReady
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvFocus
import kotlin.math.roundToInt

/** The room between the overlay's bottom block and the subtitles above it. */
private val CLEARANCE_GAP = 16.dp

/**
 * The custom Compose overlay: title/subtitle at the top left; progress bar (elapsed, total,
 * remaining, buffered, pending seek target) and the button row [⏪10] [⏯] [10⏩] [Audio y subtítulos]
 * [Siguiente episodio | Siguiente parte] at the bottom; the status chip and the audio-switch chip
 * with [Cancelar] at the top right. [OverlayMode.PROGRESS] shows the progress bar only.
 */
@Composable
fun PlayerOverlay(state: PlayerUiState, vm: PlayerViewModel, onClearance: (Float) -> Unit = {}) {
    val requesters = rememberFocusRequesters<String>()
    var tracksWereOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        if (state.overlay == OverlayMode.FULL) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, Color(0xCC000000)))))
            Column(OverlayPadding.align(Alignment.TopStart)) {
                state.title?.let { Text(it.resolve(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("player_title")) }
                state.subtitle?.let { Text(it.resolve(), style = MaterialTheme.typography.titleMedium, color = HomeTvColors.Muted) }
            }
        }
        Column(OverlayPadding.align(Alignment.TopEnd), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.chip?.let { Chip(it.resolve(), Modifier.testTag("player_chip")) }
            state.switchChip?.let { text ->
                Row(
                    Modifier.background(HomeTvColors.Surface, RoundedCornerShape(12.dp)).padding(12.dp).testTag("player_switch_chip"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text.resolve())
                    if (state.overlay == OverlayMode.FULL) ActionButton(stringResource(R.string.action_cancel), vm::onCancelLoading, Modifier.testTag("switch_cancel"))
                }
            }
        }
        if (state.spinner) {
            Text(stringResource(R.string.loading), Modifier.align(Alignment.Center).testTag("player_spinner"), color = HomeTvColors.Muted)
        }
        if (state.overlay != OverlayMode.HIDDEN) {
            // FR-2.5: the Exo subtitles sit above the bottom block while it shows (back to their position once it hides).
            val gap = with(LocalDensity.current) { CLEARANCE_GAP.toPx() }
            DisposableEffect(Unit) { onDispose { onClearance(0f) } }
            Column(
                OverlayPadding.align(Alignment.BottomCenter).fillMaxWidth().onPlaced { c ->
                    val height = c.findRootCoordinates().size.height.toFloat()
                    if (height > 0f) onClearance(((height - c.positionInRoot().y + gap) / height * 100f).roundToInt() / 100f)
                },
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProgressBar(state, focusable = state.overlay == OverlayMode.FULL, vm = vm)
                if (state.overlay == OverlayMode.FULL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OverlayIcon(R.drawable.ic_replay_10, stringResource(R.string.player_rewind), "player_rewind", requesters["rewind"]) { vm.onSeekButton(forward = false) }
                        OverlayIcon(
                            if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
                            stringResource(if (state.playing) R.string.player_pause else R.string.player_play),
                            "player_play_pause",
                            requesters["play"],
                        ) { vm.onPlayPause() }
                        OverlayIcon(R.drawable.ic_forward_10, stringResource(R.string.player_forward), "player_forward", requesters["forward"]) { vm.onSeekButton(forward = true) }
                        ActionButton(
                            stringResource(R.string.player_audio_subtitles),
                            onClick = {
                                tracksWereOpen = true
                                vm.onOpenTracks()
                            },
                            modifier = Modifier.focusRequester(requesters["tracks"]).testTag("player_tracks"),
                            icon = R.drawable.ic_subtitles,
                        )
                        state.nextButton?.let { kind ->
                            ActionButton(
                                stringResource(if (kind == NextKind.EPISODE) R.string.player_next_episode else R.string.player_next_part),
                                onClick = vm::onNext,
                                modifier = Modifier.focusRequester(requesters["next"]).testTag("player_next"),
                                icon = R.drawable.ic_skip_next,
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(state.focusPlayPause, state.overlay == OverlayMode.FULL) {
        // Back from the track/style dialogs: focus goes to "Audio y subtítulos" (the effect below), not to play/pause.
        if (state.overlay == OverlayMode.FULL && !tracksWereOpen) requesters["play"].requestFocusWhenReady()
    }
    // A closed track dialog returns focus to its opener.
    LaunchedEffect(state.dialog == null) {
        if (state.dialog == null && tracksWereOpen) {
            tracksWereOpen = false
            if (state.overlay == OverlayMode.FULL) requesters["tracks"].requestFocusWhenReady()
        }
    }
}

@Composable
private fun OverlayIcon(@DrawableRes icon: Int, description: String, tag: String, requester: androidx.compose.ui.focus.FocusRequester, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.focusRequester(requester).testTag(tag),
        scale = IconButtonDefaults.scale(focusedScale = 1.1f),
        border = IconButtonDefaults.border(focusedBorder = HomeTvFocus.circleRing),
    ) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(28.dp))
    }
}

/** Elapsed · bar (played, buffered, pending target) · total and "quedan …"; Left/Right scrub when focused. */
@Composable
private fun ProgressBar(state: PlayerUiState, focusable: Boolean, vm: PlayerViewModel) {
    var focused by remember { mutableStateOf(false) }
    val duration = state.durationMs
    val shown = state.pendingSeekMs ?: state.positionMs
    val description = stringResource(R.string.player_progress)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(Format.clock(shown / 1000.0), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("player_elapsed"))
            val barModifier = Modifier.weight(1f).height(if (focused) 10.dp else 6.dp)
                .semantics { contentDescription = description }
                .testTag("player_progress")
                .let { if (focused) it.border(2.dp, HomeTvColors.FocusRing, RoundedCornerShape(4.dp)) else it }
                .let { base ->
                    if (!focusable) base else base.onFocusChanged { focused = it.isFocused }.onKeyEvent { event ->
                        val forward = when (event.key) {
                            Key.DirectionRight -> true
                            Key.DirectionLeft -> false
                            else -> return@onKeyEvent false
                        }
                        when (event.type) {
                            KeyEventType.KeyDown -> vm.onSeekKey(forward, event.nativeKeyEvent.repeatCount)
                            KeyEventType.KeyUp -> vm.onSeekKeyUp()
                        }
                        true
                    }.focusable()
                }
            Box(barModifier.background(Color(0x55FFFFFF), RoundedCornerShape(4.dp))) {
                if (duration != null && duration > 0) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((state.bufferedMs.toFloat() / duration).coerceIn(0f, 1f)).background(Color(0x88FFFFFF)))
                    Box(Modifier.fillMaxHeight().fillMaxWidth((shown.toFloat() / duration).coerceIn(0f, 1f)).background(HomeTvColors.Progress))
                }
            }
            if (duration != null) Text(Format.clock(duration / 1000.0), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("player_total"))
        }
        if (duration != null) {
            Text(
                stringResource(R.string.player_remaining, Format.clock(((duration - shown).coerceAtLeast(0)) / 1000.0)),
                color = HomeTvColors.Muted,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp).width(160.dp),
            )
        }
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.background(HomeTvColors.Surface, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp))
}
