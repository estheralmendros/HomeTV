package com.hometv.tv.ui.detail

import com.hometv.tv.R
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.data.WatchlistKind
import com.hometv.tv.core.data.WatchlistRepository
import com.hometv.tv.core.data.WatchlistWriteException
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * The optimistic "Mi lista" toggle of Film and Show detail. The owning
 * ViewModel keeps the shown value in its detail model; this holds the value the server last confirmed
 * (the rollback target), the inline failure line and the write run being awaited.
 *
 * The write runs in `appScope` ([WatchlistRepository.write]), so it survives Back; [scope] (the
 * ViewModel's) only awaits it, once per run however many presses fed it.
 *
 * @param showsContent whether the screen shows its content: a late rollback over an Error or Loading
 *   screen still calls [onRolledBack] (the model follows the server) but sets no line and skips [publish].
 * @param onRolledBack sets the detail model's `in_watchlist` to the server's value (no publish).
 * @param onScreenError a cause that replaces the screen (404, sign-in, profile gone): the screen's `fail`.
 */
class DetailWatchlistToggle(
    private val kind: WatchlistKind,
    private val id: Long,
    private val watchlist: WatchlistRepository,
    private val store: SessionStore,
    private val scope: CoroutineScope,
    private val showsContent: () -> Boolean,
    private val onRolledBack: (Boolean) -> Unit,
    private val onScreenError: (ApiException) -> Unit,
    private val publish: () -> Unit,
) {
    /** The "Mi lista" value the server last confirmed (a fetch or an acknowledged write). */
    private var confirmed = false

    /** The write run being awaited. */
    private var write: Deferred<Boolean>? = null

    /** "No se ha podido actualizar Mi lista." after a failed write, until [clearError]. */
    var error: UiText? = null
        private set

    /**
     * A fetched detail answered [serverValue]. Returns the value to show: a write still in flight
     * wins over the fetched value (with [shown], the value on screen) until it settles.
     */
    fun fetched(serverValue: Boolean, shown: Boolean?): Boolean {
        confirmed = serverValue
        return if (shown != null && watchlist.isWriting(kind, id)) shown else serverValue
    }

    /** The caller already shows [target]; asks the server for it and awaits the run to confirm or roll back. */
    fun toggle(target: Boolean) {
        val pending = watchlist.write(kind, id, target)
        if (pending === write) return
        write = pending
        scope.launch {
            try {
                confirmed = pending.await()
            } catch (e: WatchlistWriteException) {
                onFailure(e)
            }
        }
    }

    /** The next action hides the failure line. Returns true when there was one (the caller republishes). */
    fun clearError(): Boolean {
        if (error == null) return false
        error = null
        return true
    }

    /** A screen-level cause goes to [onScreenError] (always `e.cause`); any other one rolls back to the server's state. */
    private fun onFailure(e: WatchlistWriteException) {
        if (ScreenError.from(e.cause, store.session.value.serverUrl).replacesScreen()) return onScreenError(e.cause)
        val value = e.acknowledged ?: confirmed
        confirmed = value
        onRolledBack(value)
        if (!showsContent()) return
        error = UiText.Res(R.string.watchlist_update_failed)
        publish()
    }
}
