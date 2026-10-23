package com.hometv.tv.ui

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The activity-level key hook: `MainActivity.dispatchKeyEvent` offers every key event here before
 * the view hierarchy (Compose focus). Only the most recently registered handler sees it; returning
 * true consumes the event. Phase B2's Player registers one with [KeyEventInterceptor] (media keys,
 * D-pad seek while the overlay is hidden, Back), so the keys reach its ViewModel, never `PlayerView`.
 */
class KeyDispatch {
    private val handlers = CopyOnWriteArrayList<(KeyEvent) -> Boolean>()

    fun dispatch(event: KeyEvent): Boolean = handlers.lastOrNull()?.invoke(event) == true

    /** Returns the unregister function. */
    fun register(handler: (KeyEvent) -> Boolean): () -> Unit {
        handlers += handler
        return { handlers.remove(handler) }
    }
}

val LocalKeyDispatch = staticCompositionLocalOf { KeyDispatch() }

/** Registers [handler] with the activity's [KeyDispatch] while this composable is in the composition. */
@Composable
fun KeyEventInterceptor(handler: (KeyEvent) -> Boolean) {
    val dispatch = LocalKeyDispatch.current
    val current by rememberUpdatedState(handler)
    DisposableEffect(dispatch) {
        val unregister = dispatch.register { current(it) }
        onDispose { unregister() }
    }
}
