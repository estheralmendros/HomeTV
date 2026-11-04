package com.hometv.tv.data

import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.ui.browse.BrowseSortMemory
import com.hometv.tv.ui.watchlist.WatchlistMemory
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * "Salir" on Home: everything a viewer leaves behind on this device is forgotten, in order: the
 * rating prompts, the account (avatar, info), the Browse / Mi lista sort memories, then the stored
 * profile, token and expiry (the server URL, the Home cache and the server-side tokens stay). The
 * store write is not cancellable and never waits on the network; an IOException is logged.
 */
class SignOut(
    private val store: SessionStore,
    private val ratingPrompts: RatingPrompts,
    private val account: AccountState,
    private val browseSorts: BrowseSortMemory,
    private val watchlistMemory: WatchlistMemory,
    private val log: (Throwable) -> Unit = {},
) {
    /** Forgets the profile on this device; never throws an IOException. */
    suspend operator fun invoke() {
        ratingPrompts.clear()
        account.clear()
        browseSorts.reset()
        watchlistMemory.reset()
        withContext(NonCancellable) {
            try {
                store.signOut()
            } catch (e: IOException) {
                log(e)
            }
        }
    }
}
