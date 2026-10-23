package com.hometv.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.android.awaitFrame

/** One [FocusRequester] per key (cards, buttons, tabs), created on demand. */
class FocusRequesters<K> {
    private val map = HashMap<K, FocusRequester>()

    operator fun get(key: K): FocusRequester = map.getOrPut(key) { FocusRequester() }
}

@Composable
fun <K> rememberFocusRequesters(): FocusRequesters<K> = remember { FocusRequesters() }

/** `requestFocus()` that is a no-op (false) when the target is not laid out yet. */
fun FocusRequester.tryRequestFocus(): Boolean = runCatching { requestFocus() }.isSuccess

/**
 * Requests focus once per [key] after the first layout (`LaunchedEffect` + one frame), retrying a few
 * frames while the target (e.g. a lazy item being scrolled in) is not attached yet.
 */
@Composable
fun RequestFocus(key: Any?, enabled: Boolean = true, target: () -> FocusRequester?) {
    LaunchedEffect(key, enabled) {
        if (!enabled) return@LaunchedEffect
        repeat(FRAMES) {
            awaitFrame()
            val requester = target() ?: return@LaunchedEffect
            if (requester.tryRequestFocus()) return@LaunchedEffect
        }
    }
}

private const val FRAMES = 5

/** Requests focus on the next frames until the target is attached (lazy items being scrolled in). */
suspend fun FocusRequester.requestFocusWhenReady(frames: Int = FRAMES): Boolean {
    repeat(frames) {
        awaitFrame()
        if (tryRequestFocus()) return true
    }
    return false
}
