package com.hometv.tv.data

import com.hometv.tv.core.data.ProfileRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.playback.SubtitleLook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The profile's subtitle style for the Player: [style] is [AccountState.subtitleStyle]; [save]
 * applies a look at once for the session, then `PUT /api/me/subtitle-style` in [appScope] (it
 * outlives the Player). Writes are a latest-wins single flight: one `PUT` in flight; a [save] made
 * meanwhile replaces the queued look, sent when the in-flight one settles; every `Deferred` of a run
 * completes with the run's last result. A queued look is dropped (`false`, nothing sent) when the
 * stored profile changed since it was made. A failure keeps the applied look until the next
 * `AccountState.refresh`.
 *
 * Thread-safe: [save] runs on the main thread, the worker in [appScope]. The queued look (and its
 * profile), the in-flight flag and the pending `Deferred`s are touched only under [lock]; the `PUT`,
 * the `StateFlow` write and the completions run outside it.
 */
class SubtitleStyleStore(
    private val account: AccountState,
    private val profiles: ProfileRepository,
    private val store: SessionStore,
    private val appScope: CoroutineScope,
) {
    val style: StateFlow<SubtitleLook> get() = account.subtitleStyle

    private val lock = Any()
    private var queued: SubtitleLook? = null
    private var queuedProfile: Long? = null
    private var inFlight = false
    private val waiters = mutableListOf<CompletableDeferred<Boolean>>()

    /** Applies at once for the session, then PUTs in appScope; resolves false on failure. */
    fun save(look: SubtitleLook): Deferred<Boolean> {
        account.setSubtitleStyle(look)
        val done = CompletableDeferred<Boolean>()
        val startWorker = synchronized(lock) {
            queued = look
            queuedProfile = store.session.value.profileId
            waiters += done
            if (inFlight) false else true.also { inFlight = true }
        }
        if (startWorker) {
            val worker = appScope.launch(start = CoroutineStart.LAZY) { work() }
            // A worker cancelled with appScope (process teardown, tests) never leaves a save hanging.
            worker.invokeOnCompletion { cause ->
                if (cause == null) return@invokeOnCompletion
                val pending = synchronized(lock) {
                    inFlight = false
                    queued = null
                    waiters.toList().also { waiters.clear() }
                }
                pending.forEach { it.complete(false) }
            }
            worker.start()
        }
        return done
    }

    private suspend fun work() {
        var last = false
        while (true) {
            // One locked step: take the queued look, or go idle and take this run's Deferreds, so a
            // concurrent save either joins this run's queue or starts a new run, never neither.
            var finished: List<CompletableDeferred<Boolean>>? = null
            val next = synchronized(lock) {
                val look = queued
                if (look == null) {
                    inFlight = false
                    finished = waiters.toList().also { waiters.clear() }
                    null
                } else {
                    queued = null
                    look to queuedProfile
                }
            }
            if (next == null) {
                finished?.forEach { it.complete(last) }
                return
            }
            val (look, profileId) = next
            last = if (profileId == null || profileId != store.session.value.profileId) {
                false
            } else {
                try {
                    profiles.saveSubtitleStyle(look.toWire())
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }
        }
    }
}
