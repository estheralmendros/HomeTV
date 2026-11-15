package com.hometv.tv.ui.player

import com.hometv.tv.core.api.EpisodeDetail
import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.data.NextEpisodeResolver
import com.hometv.tv.core.data.NextEpisodeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where the Player's next-episode resolution stands. */
internal sealed interface NextState {
    data object NotStarted : NextState

    data object Running : NextState

    /** [plan]: the next episode's playable versions in preference order (empty: none is playable). */
    data class Found(val episode: EpisodeDetail, val plan: List<MediaFile>) : NextState

    data object None : NextState

    data object Failed : NextState
}

/**
 * The Player's next-episode resolution: the prefetch near the end of an
 * episode, the resolution at `STATE_ENDED` and for the "Siguiente episodio" button, with a failure
 * retried up to [MAX_FAILURES] attempts in total. It only resolves and keeps the result; what to do
 * with it (countdown, end screen, exit, toast) stays with [PlayerViewModel], told through `onResolved`.
 */
internal class NextEpisodeResolution(
    private val scope: CoroutineScope,
    private val resolver: NextEpisodeResolver,
    private val log: (String, Throwable?) -> Unit,
) {
    /** What the resolution needs from the episode being played. */
    interface Current {
        /** Loads the episode detail when missing (for `next_episode_id`); false when that failed. */
        suspend fun ensureDetail(): Boolean

        /** The current episode's `next_episode_id` (read after [ensureDetail]). */
        fun nextEpisodeId(): Long?

        /** The playable versions of the next episode, in preference order. */
        suspend fun plan(episode: EpisodeDetail): List<MediaFile>
    }

    var state: NextState = NextState.NotStarted
        private set

    private var failures = 0
    private var job: Job? = null

    /**
     * Starts a resolution unless one is running; [currentFile] null leaves it marked running (as
     * before the extraction). A failure is retried while [keepRetrying] and under [MAX_FAILURES].
     */
    fun start(current: Current, currentFile: MediaFile?, keepRetrying: () -> Boolean, onResolved: () -> Unit) {
        if (state == NextState.Running) return
        state = NextState.Running
        val file = currentFile ?: return
        job = scope.launch {
            var result: NextState
            while (true) {
                result = resolveOnce(current, file)
                if (result != NextState.Failed) break
                failures++
                if (failures >= MAX_FAILURES || !keepRetrying()) break
            }
            state = result
            onResolved()
        }
    }

    private suspend fun resolveOnce(current: Current, file: MediaFile): NextState {
        if (!current.ensureDetail()) return NextState.Failed
        return when (val result = resolver.resolve(current.nextEpisodeId(), file.id)) {
            is NextEpisodeResult.Found -> NextState.Found(result.episode, current.plan(result.episode))
            NextEpisodeResult.None -> NextState.None
            is NextEpisodeResult.Failed -> NextState.Failed.also { log("next episode failed", result.cause) }
        }
    }

    /** A new item: nothing resolved, no failure counted. */
    fun reset() {
        state = NextState.NotStarted
        failures = 0
    }

    /** The "Siguiente episodio" button starts a fresh resolution: its failures are counted from zero. */
    fun clearFailures() {
        failures = 0
    }

    /** A failed result was reported (toast): the next request resolves again. */
    fun forget() {
        state = NextState.NotStarted
    }

    /** Row 28, the end screen's [Reintentar]: one more attempt, no automatic retry. */
    fun prepareLastAttempt() {
        state = NextState.NotStarted
        failures = MAX_FAILURES - 1
    }

    /** Stops a running resolution (the state stays as it is). */
    fun cancel() {
        job?.cancel()
    }

    companion object {
        const val MAX_FAILURES = 2
    }
}
