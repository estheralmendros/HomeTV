package com.hometv.tv.core.data

import com.hometv.tv.core.api.HomeTvApi
import com.hometv.tv.core.api.TrackChoice
import com.hometv.tv.core.api.apiCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The remembered audio/subtitle choice writes (`PUT /api/track-choices/{segment}/{id}`), fire-and-forget.
 *
 * **One write per title at a time, latest wins** (like [WatchlistRepository.write]): a per-title
 * worker in [appScope] sends one `PUT`; a [save] made meanwhile replaces the queued wish, whose
 * `Deferred` then completes with the next result. Each wish records the stored profile when it is
 * made; a wish whose profile is no longer stored when the worker reaches it is dropped (`false`,
 * nothing sent). Requests renew as [Origin.Background]; failures are logged, never thrown. The
 * per-title state lives in a map guarded by one plain lock; no suspending call runs while holding it.
 */
class TrackChoiceRepository(
    private val api: () -> HomeTvApi,
    private val guard: SessionGuard,
    private val store: SessionStore,
    private val appScope: CoroutineScope,
    private val log: (Throwable) -> Unit,
) {
    private class Wish(val choice: TrackChoice, val profileId: Long?, val waiters: MutableList<CompletableDeferred<Boolean>>)

    private class TitleWrite(var queued: Wish?) {
        var current: Wish? = null
    }

    private val lock = Any()
    private val titles = HashMap<RatingKey, TitleWrite>()
    private val busy = MutableStateFlow(false)

    /**
     * Queues the wish for (profile, title); never throws. Resolves true when this wish (or a newer
     * one that replaced it) was confirmed, false on failure or when dropped for a profile change.
     */
    fun save(kind: RatingKind, id: Long, choice: TrackChoice): Deferred<Boolean> {
        val key = RatingKey(kind, id)
        val done = CompletableDeferred<Boolean>()
        val start: Job?
        val entry: TitleWrite
        synchronized(lock) {
            val existing = titles[key]
            val waiters = existing?.queued?.waiters ?: mutableListOf()
            waiters += done
            val wish = Wish(choice, store.session.value.profileId, waiters)
            if (existing != null) {
                existing.queued = wish
                return done
            }
            entry = TitleWrite(wish)
            titles[key] = entry
            busy.value = true
            start = appScope.launch(start = CoroutineStart.LAZY) { work(key, entry) }
        }
        // A worker cancelled with appScope (process teardown, tests) frees the title and cancels its waiters.
        start?.invokeOnCompletion { cause ->
            if (cause == null) return@invokeOnCompletion
            val pending = synchronized(lock) {
                if (titles[key] === entry) titles.remove(key)
                busy.value = titles.isNotEmpty()
                listOfNotNull(entry.current, entry.queued).flatMap { it.waiters }.also {
                    entry.current = null
                    entry.queued = null
                }
            }
            val cancellation = cause as? CancellationException ?: CancellationException(cause.message)
            pending.forEach { it.cancel(cancellation) }
        }
        // Started outside the lock: an unconfined appScope would otherwise run the first request while holding it.
        start?.start()
        return done
    }

    /** Suspends until no write is in flight or queued (tests). */
    suspend fun awaitIdle() {
        busy.first { !it }
    }

    private suspend fun work(key: RatingKey, entry: TitleWrite) {
        while (true) {
            val wish = synchronized(lock) {
                val next = entry.queued
                entry.queued = null
                entry.current = next
                if (next == null) {
                    if (titles[key] === entry) titles.remove(key)
                    busy.value = titles.isNotEmpty()
                }
                next
            } ?: return
            val ok = if (wish.profileId == null || wish.profileId != store.session.value.profileId) {
                false
            } else {
                try {
                    guard.withRenewal(Origin.Background) { apiCall { api().putTrackChoice(key.kind.segment, key.id, wish.choice) } }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(e)
                    false
                }
            }
            synchronized(lock) { entry.current = null }
            wish.waiters.forEach { it.complete(ok) }
        }
    }
}
