package com.hometv.tv.core.playback

import com.hometv.tv.core.api.ProgressRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** What is playing right now; `durationSeconds` is the effective duration (null while unknown). */
data class ProgressSnapshot(
    val itemType: String,
    val itemId: Long,
    val mediaFileId: Long,
    val positionSeconds: Double,
    val durationSeconds: Double?,
)

/**
 * Periodic + event-driven `PUT /api/progress`: every 10 s while
 * playing, immediately on pause and at the end (position = duration), 2 s after a committed seek,
 * and a final report on exit/stop/background that runs in the app scope ([finish]). At most one
 * request in flight; a newer report replaces a queued one. No report while the duration is unknown
 * (the server answers 422). Failures are logged and dropped (no retry queue); `send` is normally
 * `WatchRepository.putProgress` (a background caller: a 401 renews, a renewal IOException drops it).
 */
class ProgressReporter(
    private val scope: CoroutineScope,
    private val send: suspend (ProgressRequest) -> Unit,
    private val snapshot: () -> ProgressSnapshot?,
    private val log: (Throwable) -> Unit = {},
) {
    private val queue = Channel<ProgressRequest>(Channel.CONFLATED)
    private val worker: Job = scope.launch {
        for (report in queue) {
            try {
                send(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(e)
            }
        }
    }
    private var ticker: Job? = null
    private var seekDebounce: Job? = null

    fun onPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) {
            ticker?.cancel()
            ticker = null
        } else if (ticker?.isActive != true) {
            ticker = scope.launch {
                while (true) {
                    delay(INTERVAL_MS)
                    report()
                }
            }
        }
    }

    fun onPaused() = report()

    fun onSeekCommitted() {
        seekDebounce?.cancel()
        seekDebounce = scope.launch {
            delay(SEEK_DEBOUNCE_MS)
            report()
        }
    }

    /** `STATE_ENDED`: position = effective duration. */
    fun onEnded() {
        finalReport(atEnd = true)?.let { queue.trySend(it) }
    }

    /** Queues a report of the current position (dropped while the duration is unknown). */
    fun report() {
        finalReport()?.let { queue.trySend(it) }
    }

    /** The report for the current snapshot, or null while nothing plays or the duration is unknown. */
    fun finalReport(atEnd: Boolean = false): ProgressRequest? {
        val s = snapshot() ?: return null
        val duration = s.durationSeconds ?: return null
        val position = if (atEnd) duration else s.positionSeconds.coerceIn(0.0, duration)
        return ProgressRequest(s.itemType, s.itemId, s.mediaFileId, position, duration)
    }

    /** Stops the reporter and sends the final report in [finalScope] (`appScope`), bounded by [timeoutMs]. */
    fun finish(finalScope: CoroutineScope, atEnd: Boolean = false, timeoutMs: Long = FINAL_TIMEOUT_MS) {
        val report = finalReport(atEnd)
        stop()
        if (report == null) return
        finalScope.launch {
            try {
                withTimeout(timeoutMs) { send(report) }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) log(e) else throw e
            } catch (e: Exception) {
                log(e)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        seekDebounce?.cancel()
        worker.cancel()
        queue.close()
    }

    companion object {
        const val INTERVAL_MS = 10_000L
        const val SEEK_DEBOUNCE_MS = 2_000L
        const val FINAL_TIMEOUT_MS = 10_000L
    }
}
