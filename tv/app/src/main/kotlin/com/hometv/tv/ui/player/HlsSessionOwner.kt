package com.hometv.tv.ui.player

import com.hometv.tv.core.data.InFlightPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The HLS sessions the Player gives up (prd3 §8 Lifecycle): `DELETE /api/hls/{sid}` runs in
 * `appScope` (it must outlive the ViewModel) with [PlayerViewModel.FINAL_TIMEOUT_MS], and row 33's
 * orphans: a `POST .../playback` abandoned on the wire is awaited in `appScope`, and the session it
 * answers with is deleted. Orphans are handled in order; the next request first waits for them
 * (at most [PlayerViewModel.ORPHAN_WAIT_MS]) so the server never counts them against
 * `too_many_sessions`. Main-thread confined, like the ViewModel that owns it.
 */
class HlsSessionOwner(
    private val appScope: CoroutineScope,
    private val delete: suspend (sessionId: String) -> Unit,
) {
    private var orphans: Job? = null

    fun delete(sessionId: String) {
        appScope.launch { withTimeoutOrNull(PlayerViewModel.FINAL_TIMEOUT_MS) { delete.invoke(sessionId) } }
    }

    /** Row 33: [request] was sent but nobody awaits it any more. */
    fun adopt(request: InFlightPlayback) {
        val previous = orphans
        orphans = appScope.launch {
            previous?.join()
            val sessionId = request.await().getOrNull()?.session_id ?: return@launch
            withTimeoutOrNull(PlayerViewModel.FINAL_TIMEOUT_MS) { delete.invoke(sessionId) }
        }
    }

    /** Before the next `POST .../playback`: lets the orphans' DELETEs land first (bounded wait). */
    suspend fun awaitOrphans() {
        val job = orphans ?: return
        withTimeoutOrNull(PlayerViewModel.ORPHAN_WAIT_MS) { job.join() }
        if (orphans === job) orphans = null
    }
}
