package com.hometv.tv.ui.components

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

/**
 * OK long press for a tv-material clickable, for every remote: tv-material only reads a long press from the
 * first auto-repeat (`repeatCount == 1`), and a TV remote forwarded over HDMI-CEC (a Samsung TV driving a
 * Chromecast) never repeats: Android injects one down and, for a hold, the release ~550 ms later.
 *
 * The press starts a [LONG_PRESS_MS] timer; the long press runs when it elapses with OK still down, or at the
 * first repeat (a Bluetooth/IR remote), whichever comes first. A release before that is a click. The release of a
 * press that long-pressed reaches tv-material, which calls [onClick] on this element: that call is swallowed. On
 * a screen or dialog the long press opened, [okKeyGuard] drops that release.
 *
 * Pass [modifier] to the element (`modifier.then(…)`), [onClick] and [interactionSource] to tv-material, and no
 * `onLongClick` there.
 */
class OkLongPress internal constructor(
    val modifier: Modifier,
    val onClick: () -> Unit,
    val interactionSource: MutableInteractionSource,
)

/** Between a quick tap (100–170 ms over HDMI-CEC) and the ~550 ms at which Android releases a CEC hold. */
const val LONG_PRESS_MS = 450L

@Composable
fun rememberOkLongPress(onClick: () -> Unit, onLongClick: () -> Unit): OkLongPress {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val scope = rememberCoroutineScope()
    val interactions = remember { MutableInteractionSource() }
    val state = remember { OkLongPressState() }
    // tv-material releases its press visuals itself only on its own long press: do the same when ours fires.
    LaunchedEffect(interactions) {
        interactions.interactions.collect {
            when (it) {
                is PressInteraction.Press -> state.press = it
                is PressInteraction.Release, is PressInteraction.Cancel -> state.press = null
            }
        }
    }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return remember(state) {
        state.onFire = {
            state.press?.let { press -> scope.launch { interactions.emit(PressInteraction.Release(press)) } }
            longClick()
        }
        OkLongPress(
            modifier = Modifier
                .onPreviewKeyEvent { state.onKey(it.nativeKeyEvent) }
                .onFocusChanged { if (!it.isFocused) state.cancel() }
                .semantics { onLongClick { longClick(); true } },
            onClick = { if (!state.consumeFired()) click() },
            interactionSource = interactions,
        )
    }
}

/** The press of one element: idle → held (timer running) → fired (until the release's click or the next press). */
internal class OkLongPressState(private val handler: Handler = Handler(Looper.getMainLooper())) {
    var onFire: () -> Unit = {}
    var press: PressInteraction.Press? = null
    private var held = false
    private var fired = false
    private val timer = Runnable { fire() }

    /** Never consumes: tv-material keeps its press visuals and its click on the release. */
    fun onKey(event: KeyEvent): Boolean {
        if (event.keyCode !in OkKeyCodes) return false
        when (event.action) {
            // A new press always restarts: the release of a hold that opened a dialog never comes back here.
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && !event.isLongPress) {
                handler.removeCallbacks(timer)
                held = true
                fired = false
                handler.postDelayed(timer, LONG_PRESS_MS)
            } else {
                fire()
            }
            KeyEvent.ACTION_UP -> cancel()
        }
        return false
    }

    /** True once for the click tv-material sends on the release of a press that long-pressed. */
    fun consumeFired(): Boolean = fired.also { fired = false }

    fun cancel() {
        handler.removeCallbacks(timer)
        held = false
    }

    private fun fire() {
        if (!held || fired) return
        handler.removeCallbacks(timer)
        fired = true
        onFire()
    }
}
