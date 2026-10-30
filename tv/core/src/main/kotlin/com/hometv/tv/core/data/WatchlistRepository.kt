package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.HomeTvApi
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.api.WatchlistEntry
import com.hometv.tv.core.api.apiCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A "Mi lista" title kind and its path segment (`PUT`/`DELETE /api/watchlist/{segment}/{id}`). */
enum class WatchlistKind(val segment: String) { FILM("films"), SHOW("shows") }

/** The `type` filter of `GET /api/watchlist`. */
enum class WatchlistType(val wire: String) { ALL("all"), FILMS("films"), SHOWS("shows") }

/** The `sort` of `GET /api/watchlist` (fixed server directions: newest added, title A–Z, newest year). */
enum class WatchlistSort(val wire: String) { ADDED("added"), TITLE("title"), YEAR("year") }

/**
 * A write worker stopped on a failed request. [acknowledged] is the value the server confirmed
 * earlier in the same worker run (a `PUT`/`DELETE` that succeeded before the failed one), or `null`
 * when no request of that run succeeded.
 */
class WatchlistWriteException(val acknowledged: Boolean?, override val cause: ApiException) : Exception(cause)

/**
 * "Mi lista" reads and writes (watchlist prd3 FR-T3). Every call renews the session once on a 401,
 * as a foreground action (a write started on a detail screen stays one even if it lands after Back).
 *
 * Writes are coalesced per title, latest wins, by a worker in [appScope] (so leaving the screen never
 * cancels one). The per-title state lives in a map guarded by one plain lock: [write], [isWriting] and
 * [writing] are called from click handlers and [appScope] is multi-threaded. No suspending or network
 * call ever runs while holding it.
 */
class WatchlistRepository(
    private val api: () -> HomeTvApi,
    private val guard: SessionGuard,
    private val appScope: CoroutineScope,
) {
    /**
     * One title's worker run: the latest wish (and the profile current when it was made), the value
     * the server acknowledged in this run, the run's result.
     */
    private class TitleWrite(var desired: Boolean, var profileId: Long?) {
        var acknowledged: Boolean? = null
        var worker: Job? = null
        val done = CompletableDeferred<Boolean>()
    }

    private val lock = Any()
    private val titles = HashMap<Pair<WatchlistKind, Long>, TitleWrite>()
    private val _writing = MutableStateFlow(false)

    /** True while any title has a write in flight or pending. */
    val writing: StateFlow<Boolean> = _writing.asStateFlow()

    /** Entries whose item is not a film/show summary are dropped (like `CatalogRepository.person`). */
    suspend fun list(type: WatchlistType, sort: WatchlistSort, page: Int, pageSize: Int = PAGE_SIZE): Page<WatchlistEntry> =
        guard.withRenewal(Origin.Foreground) { apiCall { api().watchlist(type.wire, sort.wire, page, pageSize) } }.let { p ->
            p.copy(items = p.items.filter { it.item is FilmSummary || it.item is ShowSummary })
        }

    /**
     * Asks the server to reach [inList] for this title. The wish is recorded before this returns, so
     * presses keep their call order whatever thread the worker runs on. At most one request per title
     * is in flight; when it ends the worker sends the latest wish if it differs from what the server
     * now has (nothing otherwise). The returned [Deferred] completes when the worker goes idle: with
     * the value the server last acknowledged, or with a [WatchlistWriteException] (the pending wish is
     * dropped; nothing is retried). Awaiting it from a cancelled caller does not cancel the write.
     *
     * A follow-up wish made under a profile that is no longer current when it would be sent (a
     * profile switch during a slow request) is dropped: the request would carry the new profile's
     * token. The run then completes normally with the value the old profile's list acknowledged.
     */
    fun write(kind: WatchlistKind, id: Long, inList: Boolean): Deferred<Boolean> {
        val key = kind to id
        val start: Job?
        val entry: TitleWrite
        synchronized(lock) {
            val profileId = guard.currentProfileId()
            entry = titles.getOrPut(key) { TitleWrite(inList, profileId) }
            entry.desired = inList
            entry.profileId = profileId
            _writing.value = true
            start = if (entry.worker == null) appScope.launch(start = CoroutineStart.LAZY) { work(key, entry) }.also { entry.worker = it } else null
        }
        // A worker cancelled with appScope (process teardown, tests) never leaves the title "writing".
        start?.invokeOnCompletion { cause ->
            if (cause == null) return@invokeOnCompletion
            synchronized(lock) { finish(key, entry) }
            entry.done.cancel(cause as? CancellationException ?: CancellationException(cause.message))
        }
        // Started outside the lock: an unconfined appScope would otherwise run the first request while holding it.
        start?.start()
        return entry.done
    }

    /** True while this title has a write in flight or pending. */
    fun isWriting(kind: WatchlistKind, id: Long): Boolean = synchronized(lock) { (kind to id) in titles }

    /** Suspends until no write is in flight or pending (returns at once when idle). */
    suspend fun awaitIdle() {
        writing.first { !it }
    }

    private suspend fun work(key: Pair<WatchlistKind, Long>, entry: TitleWrite) {
        while (true) {
            val send = synchronized(lock) {
                // Only a follow-up can be stale: the first request leaves right after the call that recorded it.
                val stale = entry.acknowledged != null && entry.profileId != guard.currentProfileId()
                if (stale || entry.desired == entry.acknowledged) {
                    finish(key, entry)
                    null
                } else {
                    entry.desired
                }
            }
            if (send == null) {
                // Non-null: an equal wish or a stale follow-up both come after an acknowledged request.
                entry.done.complete(entry.acknowledged ?: entry.desired)
                return
            }
            try {
                guard.withRenewal(Origin.Foreground) {
                    apiCall { if (send) api().addToWatchlist(key.first.segment, key.second) else api().removeFromWatchlist(key.first.segment, key.second) }
                }
            } catch (e: ApiException) {
                val acknowledged = synchronized(lock) {
                    finish(key, entry)
                    entry.acknowledged
                }
                entry.done.completeExceptionally(WatchlistWriteException(acknowledged, e))
                return
            }
            synchronized(lock) { entry.acknowledged = send }
        }
    }

    /** Under [lock]: the run is over; a later [write] starts a new one with no acknowledged value. */
    private fun finish(key: Pair<WatchlistKind, Long>, entry: TitleWrite) {
        if (titles[key] === entry) titles.remove(key)
        _writing.value = titles.isNotEmpty()
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
