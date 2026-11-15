package com.hometv.tv.core.data

import com.hometv.tv.core.api.HomeTvApi
import com.hometv.tv.core.api.TrackChoice
import com.hometv.tv.core.api.apiCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

/**
 * The remembered audio/subtitle choice writes (`PUT /api/track-choices/{segment}/{id}`), fire-and-forget.
 *
 * **One write per title at a time, latest wins**, through [LatestWinsWriter] (keyed by title): a
 * per-title worker in [appScope] sends one `PUT`; a [save] made meanwhile replaces the queued wish,
 * whose `Deferred` then completes with the next result when both wishes belong to the same profile
 * (else `false` at once). Each wish records the stored profile when it is made; a wish whose profile
 * is no longer stored when the worker reaches it is dropped (`false`, nothing sent). Requests renew
 * as [Origin.Background]; failures are logged, never thrown.
 */
class TrackChoiceRepository(
    private val api: () -> HomeTvApi,
    private val guard: SessionGuard,
    store: SessionStore,
    appScope: CoroutineScope,
    log: (Throwable) -> Unit,
) {
    private val writer = LatestWinsWriter<RatingKey, TrackChoice>(store, appScope, log) { key, choice ->
        guard.withRenewal(Origin.Background) { apiCall { api().putTrackChoice(key.kind.segment, key.id, choice) } }
    }

    /**
     * Queues the wish for (profile, title); never throws. Resolves true when this wish (or a newer
     * one that replaced it) was confirmed, false on failure or when dropped for a profile change.
     */
    fun save(kind: RatingKind, id: Long, choice: TrackChoice): Deferred<Boolean> = writer.submit(RatingKey(kind, id), choice)

    /** Suspends until no write is in flight or queued (tests). */
    suspend fun awaitIdle() = writer.awaitIdle()
}
