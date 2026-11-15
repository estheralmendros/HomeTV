package com.hometv.tv.ui.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.playback.VideoSurface
import com.hometv.tv.playback.ControllerSessionPlayer
import com.hometv.tv.playback.PlayerMediaSession
import com.hometv.tv.ui.KeyEventInterceptor
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.ErrorAction
import com.hometv.tv.ui.components.ErrorScreen
import com.hometv.tv.ui.components.ListDialog
import com.hometv.tv.ui.components.RequestFocus
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.text.PreparingText
import com.hometv.tv.ui.text.UiText
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens
import androidx.compose.ui.focus.focusRequester

/**
 * (9) Player: the active engine's video view (ExoPlayer: a Media3 `PlayerView`, own controller disabled,
 * `SubtitleView` for text and bitmap cues, last frame kept across media-item rebuilds; libVLC: a
 * `VLCVideoLayout`) under the Compose overlay, chips, dialogs, the countdown and the error screens, which look
 * the same with both engines. The screen owns the media session (headset / Bluetooth play-pause). Keys go to the ViewModel through [KeyEventInterceptor] (offered
 * before Compose focus by `MainActivity`) while the video is shown with the overlay hidden; Back goes
 * through [BackHandler]; `ON_STOP` / `ON_START` drive the session release and recovery.
 */
@Composable
fun PlayerScreen(vm: PlayerViewModel, onExit: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val exit by rememberUpdatedState(onExit)
    LaunchedEffect(vm) { vm.effects.collect { if (it == PlayerEffect.Exit) exit() } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onStop()
                Lifecycle.Event.ON_START -> vm.onStart()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // One media session per Player screen, for either engine: headset / Bluetooth / media controller commands
    // reach the ViewModel (the listener), and every `PlayerUiState` change republishes the active controller's state.
    val context = LocalContext.current
    val sessionPlayer = remember(vm) { ControllerSessionPlayer({ vm.playerController }, { vm }, { vm.state.value.playing }) }
    DisposableEffect(vm) {
        val session = PlayerMediaSession(context, sessionPlayer)
        onDispose { session.release() }
    }
    LaunchedEffect(sessionPlayer) { vm.state.collect { sessionPlayer.refresh() } }
    BackHandler { vm.onBack() }
    val consumed = remember { mutableSetOf<Int>() }
    val current by rememberUpdatedState(state)
    KeyEventInterceptor { event -> handlePlayerKey(vm, current, event, consumed) }

    // The fraction of the screen the overlay's bottom block covers (0 while it is hidden or gone): Exo subtitles sit above it.
    var overlayClearance by remember { mutableFloatStateOf(0f) }
    val onClearance: (Float) -> Unit = remember { { overlayClearance = it } }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("player")) {
        VideoSurface(vm.playerController, engine = state.engine, keepScreenOn = state.keepScreenOn, look = state.subtitleLook, overlayClearance = overlayClearance)
        when (val screen = state.screen) {
            is PlayerScreenUi.Loading -> PreparingView(state.title, screen, onCancel = vm::onCancelLoading)
            PlayerScreenUi.Video -> PlayerOverlay(state, vm, onClearance = onClearance)
            is PlayerScreenUi.Countdown -> NextEpisodeCountdown(screen.next, onPlayNow = vm::onPlayNow, onCancel = vm::onBack)
            is PlayerScreenUi.EndScreen -> PlayerEndScreen(screen, onRetry = vm::onEndScreenRetry, onBack = vm::onBack)
            is PlayerScreenUi.Error -> PlayerErrorScreen(screen, onAction = vm::onErrorAction)
        }
        state.toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter)) }
    }

    when (val dialog = state.dialog) {
        is PlayerDialog.Tracks -> TrackSelectionDialog(
            dialog.tracks,
            onAudio = { vm.onSelectAudio(it.toInt()) },
            onSubtitle = { vm.onSelectSubtitle(it.takeUnless { key -> key == PlayerViewModel.OFF_KEY }) },
            onStyle = vm::onOpenSubtitleStyle,
            onDismiss = vm::onDialogDismiss,
        )
        is PlayerDialog.SubtitleStyle -> SubtitleStyleDialog(dialog.ui, onStep = vm::onStyleStep, onClose = vm::onStyleDialogClosed)
        // None of the alternatives is the current version: no check mark (focus starts on the first).
        is PlayerDialog.Versions -> ListDialog(stringResource(R.string.detail_versions_title), dialog.labels.map { it.resolve() }, -1, vm::onVersionChosen, vm::onDialogDismiss)
        null -> Unit
    }
}

/** Keys while the video is shown (inputs while the overlay is hidden); dialogs and other screens use Compose focus. */
internal fun handlePlayerKey(vm: PlayerViewModel, state: PlayerUiState, event: KeyEvent, consumed: MutableSet<Int>): Boolean {
    val code = event.keyCode
    if (event.action == KeyEvent.ACTION_UP) {
        if (!consumed.remove(code)) return false
        if (code in SEEK_KEYS) vm.onSeekKeyUp()
        return true
    }
    if (event.action != KeyEvent.ACTION_DOWN) return false
    if (state.dialog != null || state.screen != PlayerScreenUi.Video) return false
    // Play/pause keys act on the first ACTION_DOWN only; the autorepeats of a held key are consumed
    // (so they never reach the system media session) but toggle nothing.
    val first = event.repeatCount == 0
    val handled = when (code) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> true.also { if (first) { vm.onPlayPause(); vm.onShowOverlay() } }
        KeyEvent.KEYCODE_MEDIA_PLAY -> true.also { if (first) { vm.onPlay(); vm.onShowOverlay() } }
        KeyEvent.KEYCODE_MEDIA_PAUSE -> true.also { if (first) vm.onPause() }
        KeyEvent.KEYCODE_MEDIA_REWIND -> true.also { vm.onSeekKey(forward = false, repeatCount = event.repeatCount) }
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true.also { vm.onSeekKey(forward = true, repeatCount = event.repeatCount) }
        else -> if (state.overlay != OverlayMode.FULL) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> true.also {
                    if (first) {
                        vm.onPlayPause()
                        vm.onShowOverlay()
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> true.also { vm.onSeekKey(forward = false, repeatCount = event.repeatCount) }
                KeyEvent.KEYCODE_DPAD_RIGHT -> true.also { vm.onSeekKey(forward = true, repeatCount = event.repeatCount) }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true.also { vm.onShowOverlay() }
                else -> false
            }
        } else {
            vm.onOverlayInput()
            false
        }
    }
    if (handled) consumed += code
    return handled
}

private val SEEK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)

/** Item load / "Preparando el vídeo… quedan unos N min" with [Cancelar] (503 `preparing`). */
@Composable
private fun PreparingView(title: UiText?, screen: PlayerScreenUi.Loading, onCancel: () -> Unit) {
    val requesters = rememberFocusRequesters<Int>()
    Box(Modifier.fillMaxSize().background(HomeTvColors.Background).testTag("player_loading"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it.resolve(), style = MaterialTheme.typography.headlineSmall) }
            val estimate = screen.preparing
            if (estimate == null) {
                Text(stringResource(R.string.player_preparing), style = MaterialTheme.typography.titleMedium, color = HomeTvColors.Muted)
            } else {
                Text(UiText.Value(PreparingText(estimate)).resolve(), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.player_preparing_body), color = HomeTvColors.Muted, textAlign = TextAlign.Center)
                ActionButton(stringResource(R.string.action_cancel), onCancel, Modifier.focusRequester(requesters[0]).testTag("preparing_cancel"))
            }
        }
    }
    RequestFocus(screen.preparing != null, enabled = screen.preparing != null) { requesters[0] }
}

/** "El siguiente episodio no se puede reproducir…" / "No se ha podido cargar el siguiente episodio" (autoplay). */
@Composable
private fun PlayerEndScreen(screen: PlayerScreenUi.EndScreen, onRetry: () -> Unit, onBack: () -> Unit) {
    val actions = buildList {
        if (screen.canRetry) add(ErrorAction(stringResource(R.string.action_retry), onRetry))
        add(ErrorAction(stringResource(R.string.action_back), onBack))
    }
    ErrorScreen(screen.message.resolve(), actions, Modifier.background(HomeTvColors.Background).testTag("player_end_screen"), detail = screen.detail?.resolve())
}

@Composable
private fun Toast(text: UiText, modifier: Modifier) {
    Box(modifier.padding(bottom = 140.dp).background(HomeTvColors.SurfaceVariant, RoundedCornerShape(24.dp)).padding(horizontal = 24.dp, vertical = 12.dp).testTag("player_toast")) {
        Text(text.resolve(), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Padding of the overlay inside the TV safe area. */
internal val OverlayPadding = Modifier.padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = HomeTvDimens.SafeVertical)
