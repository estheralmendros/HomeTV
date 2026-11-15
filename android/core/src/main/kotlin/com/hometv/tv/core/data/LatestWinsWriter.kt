package com.hometv.tv.core.data

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
 * A fire-and-forget, latest-wins write queue per key, shared by [TrackChoiceRepository] and the
 * app's `SubtitleStyleStore`.
 *
 * - **One write per key at a time**: a lazy worker in [appScope] sends one [write]; a [submit] made
 *   meanwhile replaces the queued wish (sent when the in-flight one settles).
 * - **Each wish records the stored profile** when it is made. A replaced wish's `Deferred`s move to
 *   the newer wish (and complete with its result) only when both wishes belong to the same profile;
 *   otherwise they complete `false` at once (that wish is never sent). A wish whose profile is no
 *   longer stored (or no profile at all) when the worker reaches it is dropped: `false`, nothing sent.
 * - A [write] failure is logged and resolves `false`; [submit] never throws.
 * - **Cancellation rule**: when the worker is cancelled with [appScope] (process teardown, tests),
 *   the key is freed and every pending `Deferred` completes `false` (none is cancelled or left hanging).
 *
 * Thread-safe: the per-key state lives in a map guarded by one plain lock; [write], the worker start
 * and every `Deferred` completion run outside it.
 */
class LatestWinsWriter<K : Any, V>(
    private val store: SessionStore,
    private val appScope: CoroutineScope,
    private val log: (Throwable) -> Unit,
    private val write: suspend (key: K, value: V) -> Unit,
) {
    private class Wish<V>(val value: V, val profileId: Long?, val waiters: MutableList<CompletableDeferred<Boolean>>)

    private class Slot<V>(var queued: Wish<V>?) {
        var current: Wish<V>? = null
    }

    private val lock = Any()
    private val slots = HashMap<K, Slot<V>>()
    private val busy = MutableStateFlow(false)

    /**
     * Queues [value] for [key] under the stored profile. Resolves true when this wish (or a newer one
     * of the same profile that replaced it) was written, false on failure, on cancellation, or when
     * dropped for a profile change.
     */
    fun submit(key: K, value: V): Deferred<Boolean> {
        val done = CompletableDeferred<Boolean>()
        val profileId = store.session.value.profileId
        var dropped: List<CompletableDeferred<Boolean>> = emptyList()
        var start: Job? = null
        var started: Slot<V>? = null
        synchronized(lock) {
            val existing = slots[key]
            val replaced = existing?.queued
            val waiters = if (replaced != null && replaced.profileId == profileId) {
                replaced.waiters
            } else {
                if (replaced != null) dropped = replaced.waiters
                mutableListOf()
            }
            waiters += done
            val wish = Wish(value, profileId, waiters)
            if (existing != null) {
                existing.queued = wish
            } else {
                val slot = Slot(wish)
                slots[key] = slot
                busy.value = true
                started = slot
                start = appScope.launch(start = CoroutineStart.LAZY) { work(key, slot) }
            }
        }
        dropped.forEach { it.complete(false) }
        // Registered and started outside the lock: a cancelled or unconfined appScope would otherwise
        // run the handler or the first write while holding it.
        start?.let { job ->
            val slot = started!!
            job.invokeOnCompletion { cause -> if (cause != null) cancelled(key, slot) }
            job.start()
        }
        return done
    }

    /** Suspends until no write is in flight or queued (tests). */
    suspend fun awaitIdle() {
        busy.first { !it }
    }

    private fun cancelled(key: K, slot: Slot<V>) {
        val pending = synchronized(lock) {
            if (slots[key] === slot) slots.remove(key)
            busy.value = slots.isNotEmpty()
            listOfNotNull(slot.current, slot.queued).flatMap { it.waiters }.also {
                slot.current = null
                slot.queued = null
            }
        }
        pending.forEach { it.complete(false) }
    }

    private suspend fun work(key: K, slot: Slot<V>) {
        while (true) {
            val wish = synchronized(lock) {
                val next = slot.queued
                slot.queued = null
                slot.current = next
                if (next == null) {
                    if (slots[key] === slot) slots.remove(key)
                    busy.value = slots.isNotEmpty()
                }
                next
            } ?: return
            val ok = if (wish.profileId == null || wish.profileId != store.session.value.profileId) {
                false
            } else {
                try {
                    write(key, wish.value)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(e)
                    false
                }
            }
            synchronized(lock) { slot.current = null }
            wish.waiters.forEach { it.complete(ok) }
        }
    }
}
