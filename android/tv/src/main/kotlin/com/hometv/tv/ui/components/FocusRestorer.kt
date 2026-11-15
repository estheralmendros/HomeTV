package com.hometv.tv.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
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

/**
 * Requests focus on the next frames until the target is attached (lazy items being scrolled in).
 * Gives up once [stillWanted] is false: a newer target was chosen while this one waited for a frame
 * (the effect that asked is cancelled only at the next recomposition, after the frame resumed it).
 */
suspend fun FocusRequester.requestFocusWhenReady(frames: Int = FRAMES, stillWanted: () -> Boolean = { true }): Boolean {
    repeat(frames) {
        awaitFrame()
        if (!stillWanted()) return false
        if (tryRequestFocus()) return true
    }
    return false
}

/**
 * The key of the lazy-row item that has focus right now (null when none has). Selecting an item
 * publishes it as a focus target while the screen is leaving; requesting the item that already has
 * focus would race the next screen, so [focusItem] skips it.
 */
class FocusedItemKey<K> {
    var current: K? by mutableStateOf(null)
        private set

    /** Tracks focus on the item [key] (chain it on that item). */
    fun track(key: K): Modifier = Modifier.onFocusChanged { if (it.isFocused) current = key else if (current == key) current = null }
}

@Composable
fun <K> rememberFocusedItemKey(): FocusedItemKey<K> = remember { FocusedItemKey() }

/**
 * Restores focus on the lazy item [key] at [index] (a card that opened another screen, after Back or
 * a refetch): nothing when it already has focus ([focused]); otherwise waits for the first layout,
 * scrolls the item into view when it is not visible, then requests focus on [requester].
 */
suspend fun <K : Any> LazyListState.focusItem(key: K, index: Int, focused: FocusedItemKey<K>, requester: FocusRequester) {
    if (focused.current == key) return
    if (layoutInfo.visibleItemsInfo.isEmpty()) awaitFrame()
    if (layoutInfo.visibleItemsInfo.none { it.key == key }) scrollToItem(index)
    requester.requestFocusWhenReady()
}
