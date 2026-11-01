package com.hometv.tv.core.data

import com.hometv.tv.core.api.ActivityEntry
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.HomeTvApi
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.api.RatingRequest
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.api.apiCall
import com.hometv.tv.core.util.Stars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A rated title's kind: [wire] = `kind`/`type` on the API (and `AppEvent.ItemGone`), [segment] = the path segment. */
enum class RatingKind(val wire: String, val segment: String) {
    FILM("film", "films"),
    SHOW("show", "shows"),
    ;

    companion object {
        fun fromWire(wire: String): RatingKind? = entries.firstOrNull { it.wire == wire }
    }
}

data class RatingKey(val kind: RatingKind, val id: Long)

/** A successful write: [stars] null = removed. */
data class RatingChange(val kind: RatingKind, val id: Long, val stars: Stars?)

/**
 * Ratings reads and writes (ratings prd3 FR-T4). A write runs in [appScope] and the caller awaits it,
 * so leaving the screen (Back, a cleared ViewModel) never cancels it; failures reach the awaiting
 * caller as `ApiException`. At most one write per title is in flight: a second [set]/[remove] for a
 * title already in [writing] sends nothing and returns the in-flight write's outcome (whichever kind
 * it is; the UI disables every control that starts one while [isWriting], so this only guards against
 * a missed check). Every successful write is published on [changes].
 */
class RatingsRepository(
    private val api: () -> HomeTvApi,
    private val guard: SessionGuard,
    private val appScope: CoroutineScope,
    private val log: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private val inFlight = HashMap<RatingKey, CompletableDeferred<Unit>>()
    private val _writing = MutableStateFlow<Set<RatingKey>>(emptySet())
    private val _changes = MutableSharedFlow<RatingChange>(extraBufferCapacity = 16)

    /** The titles with a write in flight. */
    val writing: StateFlow<Set<RatingKey>> = _writing.asStateFlow()

    /** Successful `set` (stars) and `remove` (null), whatever UI started them. Dismissals emit nothing. */
    val changes: SharedFlow<RatingChange> = _changes.asSharedFlow()

    fun isWriting(kind: RatingKind, id: Long): Boolean = synchronized(lock) { RatingKey(kind, id) in inFlight }

    suspend fun set(kind: RatingKind, id: Long, stars: Stars) {
        write(RatingKey(kind, id), stars) { api().setRating(kind.segment, id, RatingRequest(stars.value)) }
    }

    suspend fun remove(kind: RatingKind, id: Long) {
        write(RatingKey(kind, id), null) { api().removeRating(kind.segment, id) }
    }

    /** "Ahora no" / Back on a prompt: fire-and-forget in [appScope], failures logged and swallowed. */
    fun dismiss(kind: RatingKind, id: Long) {
        appScope.launch {
            try {
                guard.withRenewal(Origin.Background) { apiCall { api().dismissRatingPrompt(kind.segment, id) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(e)
            }
        }
    }

    /** Entries whose item is not a film/show summary are dropped (like `WatchlistRepository.list`). */
    suspend fun activity(page: Int, pageSize: Int = PAGE_SIZE): Page<ActivityEntry> {
        val p = guard.withRenewal(Origin.Foreground) { apiCall { api().activity(page, pageSize) } }
        return p.copy(items = p.items.filter { it.item is FilmSummary || it.item is ShowSummary })
    }

    private suspend fun write(key: RatingKey, stars: Stars?, request: suspend () -> Unit) {
        val (outcome, job) = synchronized(lock) { claim(key, stars, request) }
        if (job != null) {
            // A write cancelled with appScope (process teardown, tests) never leaves the title "writing".
            job.invokeOnCompletion { cause ->
                if (cause == null) return@invokeOnCompletion
                finish(key)
                outcome.cancel(cause as? CancellationException)
            }
            // Started outside the lock: an unconfined appScope would otherwise run the request while holding it.
            job.start()
        }
        outcome.await()
    }

    /** Under [lock]: the in-flight write for [key], or a new one (with its not yet started job). */
    private fun claim(key: RatingKey, stars: Stars?, request: suspend () -> Unit): Pair<CompletableDeferred<Unit>, Job?> {
        val running = inFlight[key]
        if (running != null) return Pair(running, null)
        val outcome = CompletableDeferred<Unit>()
        inFlight[key] = outcome
        _writing.value = inFlight.keys.toSet()
        val job = appScope.launch(start = CoroutineStart.LAZY) { perform(key, stars, request, outcome) }
        return Pair(outcome, job)
    }

    private suspend fun perform(key: RatingKey, stars: Stars?, request: suspend () -> Unit, outcome: CompletableDeferred<Unit>) {
        val failure: Exception? = try {
            guard.withRenewal(Origin.Foreground) { apiCall { request() } }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        finish(key)
        if (failure == null) {
            _changes.tryEmit(RatingChange(key.kind, key.id, stars))
            outcome.complete(Unit)
        } else {
            outcome.completeExceptionally(failure)
        }
    }

    private fun finish(key: RatingKey) {
        synchronized(lock) {
            inFlight.remove(key)
            _writing.value = inFlight.keys.toSet()
        }
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
