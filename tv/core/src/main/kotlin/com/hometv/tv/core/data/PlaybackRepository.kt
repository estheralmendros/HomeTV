package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiErrors
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.HomeTvApi
import com.hometv.tv.core.api.PlaybackRequest
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.ResponseTooLargeException
import com.hometv.tv.core.api.apiCall
import com.hometv.tv.core.api.fetch
import com.hometv.tv.core.api.stringField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

/** A `POST .../playback` running in `appScope`: abandoning its await never cancels it (prd3 §8 In-flight requests). */
class InFlightPlayback(val result: Deferred<Result<PlaybackResponse>>) {
    suspend fun await(): Result<PlaybackResponse> = result.await()
}

sealed interface PlaybackProgress {
    /** A POST was sent; keep it as `orphanRequest` if the await is abandoned. */
    data class Sent(val request: InFlightPlayback) : PlaybackProgress

    /** 503 `preparing`: waiting [retryAfterSeconds] before the next attempt. */
    data class Preparing(val estimatedSeconds: Int?, val retryAfterSeconds: Long) : PlaybackProgress

    data class Success(val response: PlaybackResponse) : PlaybackProgress

    data class Failure(val error: ApiException) : PlaybackProgress

    /** The `preparing` budget ran out: "El servidor todavía está preparando este vídeo…". */
    data object PreparingTimeout : PlaybackProgress
}

sealed interface SubtitleFetch {
    class Ready(val bytes: ByteArray) : SubtitleFetch

    data class Pending(val retryAfterSeconds: Long) : SubtitleFetch

    /** `subtitle_extraction_failed`, `subtitle_timeout`, `subtitle_not_found`, `subtitle_not_text`, a cap… */
    data class Unavailable(val code: String?) : SubtitleFetch

    data class Unreachable(val cause: ApiException) : SubtitleFetch
}

/** One subtitle GET. Returns Ready/Pending/Unavailable; throws `ApiException.Unauthorized` (401) and `ApiException.Unreachable` (IOException). */
fun interface SubtitleSource {
    suspend fun fetch(url: String): SubtitleFetch
}

enum class KeepAliveResult { ALIVE, SESSION_LOST, FAILED }

/**
 * Playback requests and HLS/subtitle side calls (prd3 §8, §10, Playback Flow rule 3).
 *
 * Every `POST .../playback` runs as `appScope.async`, so abandoning the await (Cancel, Back,
 * `ON_STOP`, `onCleared()`) never cancels a request on the wire; the flow announces each one with
 * [PlaybackProgress.Sent] so the ViewModel can keep it as `orphanRequest`. [clockMs] measures the
 * `preparing` budget (virtual time in tests).
 */
class PlaybackRepository(
    private val api: () -> HomeTvApi,
    private val guard: SessionGuard,
    private val appScope: CoroutineScope,
    private val subtitles: SubtitleSource,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /**
     * Sends the request and runs the 503 `preparing` loop: waits `retryAfter` (header, else
     * `details.retry_after_seconds`, else 5 s; clamped to 1–30 s) and repeats the same body with
     * `previous_session_id = null` (the server already stopped it), while the time since the first 503
     * is below `min(30 min, max(120 s, 2 × first estimated_seconds))`. Cancelling the collector stops
     * further attempts. `SessionGuard.ensureFresh()` runs before each POST; a 401 renews and re-sends once.
     * [retryStartPosition], when given, supplies `start_position_seconds` for each retry (a direct → HLS
     * audio switch retries at the direct media's position at that moment, prd3 §8 row 36).
     */
    fun requestPlayback(
        mediaFileId: Long,
        request: PlaybackRequest,
        origin: Origin = Origin.Foreground,
        retryStartPosition: (() -> Double)? = null,
    ): Flow<PlaybackProgress> = flow {
        var body = request
        var firstPreparingAt: Long? = null
        var budgetMs = MIN_PREPARING_BUDGET_MS
        while (true) {
            guard.ensureFresh()
            val sent = body
            val inFlight = InFlightPlayback(
                appScope.async {
                    try {
                        Result.success(guard.withRenewal(origin) { apiCall { api().playback(mediaFileId, sent) } })
                    } catch (e: ApiException) {
                        Result.failure(e)
                    }
                },
            )
            emit(PlaybackProgress.Sent(inFlight))
            val error = inFlight.await().fold(
                onSuccess = { emit(PlaybackProgress.Success(it)); return@flow },
                onFailure = { it as? ApiException ?: ApiException.Decode(it) },
            )
            if (error !is ApiException.Http || error.code != "preparing") {
                emit(PlaybackProgress.Failure(error))
                return@flow
            }
            val now = clockMs()
            val estimate = error.details.stringField("estimated_seconds")?.toDoubleOrNull()?.toInt()
            val startedAt = firstPreparingAt ?: now.also {
                firstPreparingAt = it
                budgetMs = (2_000L * (estimate ?: 0)).coerceIn(MIN_PREPARING_BUDGET_MS, MAX_PREPARING_BUDGET_MS)
            }
            if (now - startedAt >= budgetMs) {
                emit(PlaybackProgress.PreparingTimeout)
                return@flow
            }
            val wait = (error.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceIn(MIN_RETRY_AFTER_SECONDS, MAX_RETRY_AFTER_SECONDS)
            emit(PlaybackProgress.Preparing(estimate, wait))
            delay(wait * 1_000)
            body = body.copy(previous_session_id = null, start_position_seconds = retryStartPosition?.invoke() ?: body.start_position_seconds)
        }
    }

    /** One attempt (the 3 s budget for the default track before `prepare()`); a 401 renews in the background. */
    suspend fun fetchSubtitle(url: String): SubtitleFetch = try {
        guard.withRenewal(Origin.Background) { subtitles.fetch(url) }
    } catch (e: ApiException.Unreachable) {
        SubtitleFetch.Unreachable(e)
    } catch (e: ApiException.ProfileGone) {
        SubtitleFetch.Unavailable(null)
    } catch (e: ApiException) {
        SubtitleFetch.Unavailable((e as? ApiException.Http)?.code)
    }

    /**
     * The subtitle loop: emits each [SubtitleFetch.Pending] and ends with Ready or Unavailable.
     * Pending waits `Retry-After` (default 10 s) while [stillWanted], overall cap 10 min; network
     * failures (including a renewal IOException) retry after 5 s, at most 3 in a row; a 401 renews
     * as a background caller and continues; extraction failures end at once. Never the Connection Error screen.
     */
    fun subtitleLoop(url: String, stillWanted: () -> Boolean = { true }): Flow<SubtitleFetch> = flow {
        val startedAt = clockMs()
        var networkFailures = 0
        while (stillWanted()) {
            when (val result = fetchSubtitle(url)) {
                is SubtitleFetch.Ready, is SubtitleFetch.Unavailable -> {
                    emit(result)
                    return@flow
                }
                is SubtitleFetch.Pending -> {
                    networkFailures = 0
                    emit(result)
                    if (clockMs() - startedAt >= SUBTITLE_CAP_MS) {
                        emit(SubtitleFetch.Unavailable(null))
                        return@flow
                    }
                    delay(result.retryAfterSeconds.coerceAtLeast(1) * 1_000)
                }
                is SubtitleFetch.Unreachable -> {
                    networkFailures++
                    if (networkFailures >= SUBTITLE_MAX_NETWORK_FAILURES || clockMs() - startedAt >= SUBTITLE_CAP_MS) {
                        emit(SubtitleFetch.Unavailable(null))
                        return@flow
                    }
                    delay(SUBTITLE_NETWORK_RETRY_MS)
                }
            }
        }
    }

    /** `POST /api/hls/{sid}/keepalive` (session-id auth). 404 → the session is lost (next play recovers). */
    suspend fun keepAliveHlsSession(sessionId: String): KeepAliveResult = try {
        val response = api().keepAlive(sessionId)
        when {
            response.isSuccessful -> KeepAliveResult.ALIVE
            response.code() == 404 -> KeepAliveResult.SESSION_LOST
            else -> KeepAliveResult.FAILED
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        KeepAliveResult.FAILED
    }

    /** `DELETE /api/hls/{sid}`; failures are ignored (the server's idle cleanup is the safety net). */
    suspend fun deleteHlsSession(sessionId: String): Boolean = try {
        api().deleteHlsSession(sessionId).isSuccessful
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    companion object {
        const val DEFAULT_RETRY_AFTER_SECONDS = 5L
        const val MIN_RETRY_AFTER_SECONDS = 1L
        const val MAX_RETRY_AFTER_SECONDS = 30L
        const val MIN_PREPARING_BUDGET_MS = 120_000L
        const val MAX_PREPARING_BUDGET_MS = 30 * 60_000L
        const val SUBTITLE_DEFAULT_RETRY_AFTER_SECONDS = 10L
        const val SUBTITLE_CAP_MS = 10 * 60_000L
        const val SUBTITLE_NETWORK_RETRY_MS = 5_000L
        const val SUBTITLE_MAX_NETWORK_FAILURES = 3
    }
}

/**
 * Body cap of one subtitle GET. The server's WebVTT of a long film's dialogue is a few hundred KB; 16 MiB
 * leaves room for dense SDH tracks and still keeps a misbehaving answer off the heap.
 */
const val SUBTITLE_MAX_BODY_BYTES: Long = 16L shl 20

/** [SubtitleSource] over the stream client (Bearer from its `AuthInterceptor`, read timeout 45 s > the server's 30 s hold). */
class OkHttpSubtitleSource(private val client: OkHttpClient) : SubtitleSource {
    override suspend fun fetch(url: String): SubtitleFetch {
        // fetch() reads the body on OkHttp's thread: this may resume on the main thread.
        val response = try {
            client.newCall(Request.Builder().url(url).build()).fetch(SUBTITLE_MAX_BODY_BYTES)
        } catch (e: ResponseTooLargeException) {
            // Not a network failure: retrying would read the same oversized body again.
            return SubtitleFetch.Unavailable(null)
        } catch (e: IOException) {
            throw ApiException.Unreachable(e)
        }
        if (response.isSuccessful) return SubtitleFetch.Ready(response.body)
        return when (val error = ApiErrors.fromResponse(response.code, response.body.decodeToString(), response.header("Retry-After"))) {
            is ApiException.Unauthorized -> throw error
            is ApiException.Http ->
                if (error.status == 503 && error.code == "subtitle_pending") {
                    SubtitleFetch.Pending(error.retryAfterSeconds ?: PlaybackRepository.SUBTITLE_DEFAULT_RETRY_AFTER_SECONDS)
                } else {
                    SubtitleFetch.Unavailable(error.code)
                }
            else -> SubtitleFetch.Unavailable(null)
        }
    }
}
