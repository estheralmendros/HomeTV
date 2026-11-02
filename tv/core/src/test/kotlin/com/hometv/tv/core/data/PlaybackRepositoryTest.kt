package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.api.PlaybackRequest
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.SessionResponse
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PlaybackRepositoryTest {
    private val caps = ClientCaps(listOf("mp4"), listOf("h264"), listOf("aac"), 6, true, true)
    private val first = PlaybackRequest(caps, audio_stream_index = 2, start_position_seconds = 1234.0, previous_session_id = "old-session")
    private val hls = PlaybackResponse(mode = "hls", url = "/api/hls/s1/index.m3u8", session_id = "s1", audio_stream_index = 2, audio_action = "copy", duration_seconds = 100.0)

    /** A POST recorded with the virtual time it was sent at. */
    private data class Call(val atMs: Long, val body: PlaybackRequest)

    private inner class Harness(
        val scope: TestScope,
        expiresAt: Instant = Instant.parse("2026-12-01T00:00:00Z"),
        val answers: MutableList<suspend () -> PlaybackResponse> = mutableListOf(),
    ) {
        val calls = mutableListOf<Call>()
        val sessions = mutableListOf<Long>()
        val store = InMemorySessionStore(Session("https://hometv.ts.net", 5, "tok", expiresAt))
        val api = object : FakeHomeTvApi() {
            override suspend fun playback(mediaFileId: Long, body: PlaybackRequest): PlaybackResponse {
                calls += Call(scope.testScheduler.currentTime, body)
                return answers.removeFirst().invoke()
            }

            override suspend fun createSession(profileId: Long): SessionResponse {
                sessions += scope.testScheduler.currentTime
                return session("tok-${sessions.size + 1}")
            }
        }
        val guard = SessionGuard(store, { api.createSession(it) }, {}, Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC))
        val repo = PlaybackRepository({ api }, guard, scope.backgroundScope, { error("unused") }, clockMs = { scope.testScheduler.currentTime })

        fun preparing(estimate: Int? = null, retryAfter: String? = "5", detailsRetry: Int? = null): suspend () -> PlaybackResponse = {
            val details = listOfNotNull(estimate?.let { "\"estimated_seconds\": $it" }, detailsRetry?.let { "\"retry_after_seconds\": $it" })
            throw httpError(503, "preparing", "{${details.joinToString()}}", retryAfter)
        }

        fun ok(response: PlaybackResponse = hls): suspend () -> PlaybackResponse = { response }

        /** Collects the flow, recording each emission with its virtual time. */
        suspend fun run(flow: Flow<PlaybackProgress> = repo.requestPlayback(91, first)): List<Pair<Long, PlaybackProgress>> {
            val out = mutableListOf<Pair<Long, PlaybackProgress>>()
            flow.collect { out += scope.testScheduler.currentTime to it }
            return out
        }
    }

    private fun List<Pair<Long, PlaybackProgress>>.withoutSent() = filterNot { it.second is PlaybackProgress.Sent }

    @Nested
    inner class PreparingLoop {
        @Test
        fun `preparing twice then 200 - three bodies 5 s apart, previous_session_id null after the first`() = runTest {
            val h = Harness(this)
            h.answers += listOf(h.preparing(412), h.preparing(400), h.ok())
            val events = h.run().withoutSent()

            assertEquals(listOf(0L, 5_000L, 10_000L), h.calls.map { it.atMs })
            assertEquals(first, h.calls[0].body)
            assertEquals(first.copy(previous_session_id = null), h.calls[1].body)
            assertEquals(first.copy(previous_session_id = null), h.calls[2].body)
            assertEquals(
                listOf(
                    0L to PlaybackProgress.Preparing(412, 5),
                    5_000L to PlaybackProgress.Preparing(400, 5),
                    10_000L to PlaybackProgress.Success(hls),
                ),
                events,
            )
        }

        @Test
        fun `every POST is announced with its in-flight handle`() = runTest {
            val h = Harness(this)
            h.answers += listOf(h.preparing(), h.ok())
            val sent = h.run().map { it.second }.filterIsInstance<PlaybackProgress.Sent>()
            assertEquals(2, sent.size)
            assertTrue(sent.all { it.request.result.isCompleted })
        }

        @Test
        fun `retryAfter from details when the header is missing, 5 s without both, clamped to 1-30 s`() = runTest {
            val h = Harness(this)
            h.answers += listOf(
                h.preparing(retryAfter = null, detailsRetry = 7),
                h.preparing(retryAfter = null),
                h.preparing(retryAfter = "120"),
                h.preparing(retryAfter = "0"),
                h.ok(),
            )
            h.run()
            assertEquals(listOf(0L, 7_000L, 12_000L, 42_000L, 43_000L), h.calls.map { it.atMs })
        }

        /** Runs a preparing-forever request; times are relative to its first POST. */
        private suspend fun TestScope.timeoutAt(firstEstimate: Int?): Pair<Long, List<Long>> {
            val start = testScheduler.currentTime
            val h = Harness(this)
            repeat(1_000) { h.answers += h.preparing(firstEstimate) }
            val events = h.run().withoutSent()
            val (at, last) = events.last()
            assertEquals(PlaybackProgress.PreparingTimeout, last)
            return at - start to h.calls.map { it.atMs - start }
        }

        @Test
        fun `budget - no estimate or a 30 s estimate times out at 120 s`() = runTest {
            assertEquals(120_000L, timeoutAt(null).first)
            assertEquals(120_000L, timeoutAt(30).first)
        }

        @Test
        fun `budget - a first estimate of 300 s is still retrying at 590 s and times out at 600 s`() = runTest {
            val (at, calls) = timeoutAt(300)
            assertTrue(590_000L in calls)
            assertEquals(600_000L, at)
        }

        @Test
        fun `budget - a first estimate of 3600 s is capped at 30 min`() = runTest {
            assertEquals(1_800_000L, timeoutAt(3600).first)
        }

        @Test
        fun `a retry can carry a fresh start position (direct to HLS audio switch)`() = runTest {
            val h = Harness(this)
            h.answers += listOf(h.preparing(), h.preparing(), h.ok())
            var position = 1234.0
            val flow = h.repo.requestPlayback(91, first, retryStartPosition = { position.also { position += 5.0 } })
            h.run(flow)
            assertEquals(listOf(1234.0, 1234.0, 1239.0), h.calls.map { it.body.start_position_seconds })
        }

        @Test
        fun `cancelling the collector stops further requests`() = runTest {
            val h = Harness(this)
            repeat(10) { h.answers += h.preparing(60) }
            val job = launch { h.run() }
            advanceTimeBy(7_000)
            job.cancel()
            advanceTimeBy(60_000)
            assertEquals(listOf(0L, 5_000L), h.calls.map { it.atMs })
        }

        @Test
        fun `too_many_sessions and hls_disk_full are not retried`() = runTest {
            for (code in listOf("too_many_sessions", "hls_disk_full")) {
                val h = Harness(this)
                h.answers += { throw httpError(503, code) }
                val last = h.run().last().second as PlaybackProgress.Failure
                assertEquals(code, (last.error as ApiException.Http).code)
                assertEquals(1, h.calls.size)
            }
        }

        @Test
        fun `other failures end the flow with the mapped error`() = runTest {
            val h = Harness(this)
            h.answers += { throw IOException("reset") }
            assertTrue((h.run().last().second as PlaybackProgress.Failure).error is ApiException.Unreachable)
        }
    }

    @Nested
    inner class InFlight {
        @Test
        fun `a sent POST completes in appScope after the collector is cancelled`() = runTest {
            val h = Harness(this)
            h.answers += { delay(3_000); hls }
            var sent: InFlightPlayback? = null
            val job = launch { h.repo.requestPlayback(91, first).collect { if (it is PlaybackProgress.Sent) sent = it.request } }
            advanceTimeBy(1_000)
            job.cancel()
            runCurrent()
            assertEquals(false, sent!!.result.isCompleted)
            advanceTimeBy(2_500)
            assertEquals(hls, sent!!.result.getCompleted().getOrThrow())
        }
    }

    @Nested
    inner class Session {
        @Test
        fun `ensureFresh renews before the POST when the token expires within 24 h`() = runTest {
            val h = Harness(this, expiresAt = Instant.parse("2026-10-23T06:00:00Z"))
            h.answers += h.ok()
            h.run()
            assertEquals(1, h.sessions.size)
            assertEquals("tok-2", h.store.session.value.token)
        }

        @Test
        fun `a 401 renews the session and re-sends the POST once`() = runTest {
            val h = Harness(this)
            h.answers += listOf({ throw httpError(401, "unauthorized") }, h.ok())
            assertEquals(PlaybackProgress.Success(hls), h.run().last().second)
            assertEquals(2, h.calls.size)
            assertEquals(1, h.sessions.size)
        }
    }

    @Nested
    inner class HlsSessions {
        @Test
        fun `keep-alive and delete`() = runTest {
            val statuses = ArrayDeque(listOf(204, 404, 500))
            val deleted = mutableListOf<String>()
            val h = Harness(this)
            val api = object : FakeHomeTvApi() {
                override suspend fun keepAlive(sessionId: String) = empty(statuses.removeFirst())
                override suspend fun deleteHlsSession(sessionId: String) = empty(204).also { deleted += sessionId }
            }
            val repo = PlaybackRepository({ api }, h.guard, backgroundScope, { error("unused") })
            assertEquals(KeepAliveResult.ALIVE, repo.keepAliveHlsSession("s1"))
            assertEquals(KeepAliveResult.SESSION_LOST, repo.keepAliveHlsSession("s1"))
            assertEquals(KeepAliveResult.FAILED, repo.keepAliveHlsSession("s1"))
            assertTrue(repo.deleteHlsSession("s1"))
            assertEquals(listOf("s1"), deleted)
        }

        @Test
        fun `keep-alive and delete failures are swallowed`() = runTest {
            val h = Harness(this)
            val api = object : FakeHomeTvApi() {
                override suspend fun keepAlive(sessionId: String): retrofit2.Response<Unit> = throw IOException("down")
                override suspend fun deleteHlsSession(sessionId: String): retrofit2.Response<Unit> = throw IOException("down")
            }
            val repo = PlaybackRepository({ api }, h.guard, backgroundScope, { error("unused") })
            assertEquals(KeepAliveResult.FAILED, repo.keepAliveHlsSession("s1"))
            assertEquals(false, repo.deleteHlsSession("s1"))
        }
    }

    @Nested
    inner class Subtitles {
        private val url = "https://hometv.ts.net/api/media-files/91/subtitles/emb-3.vtt"

        private inner class Source(val scope: TestScope, vararg answers: () -> SubtitleFetch) : SubtitleSource {
            val queue = ArrayDeque(answers.toList())
            val times = mutableListOf<Long>()
            override suspend fun fetch(url: String): SubtitleFetch {
                times += scope.testScheduler.currentTime
                // The last answer repeats forever.
                return (if (queue.size > 1) queue.removeFirst() else queue.first()).invoke()
            }
        }

        private fun repo(scope: TestScope, source: SubtitleSource, h: Harness = Harness(scope)) =
            PlaybackRepository({ h.api }, h.guard, scope.backgroundScope, source, clockMs = { scope.testScheduler.currentTime })

        private fun collect(scope: TestScope, flow: Flow<SubtitleFetch>): List<Pair<Long, SubtitleFetch>> {
            val out = mutableListOf<Pair<Long, SubtitleFetch>>()
            scope.launch { flow.collect { out += scope.testScheduler.currentTime to it } }
            scope.advanceUntilIdle()
            return out
        }

        @Test
        fun `pending (Retry-After 10) then 200 - Ready after 10 s`() = runTest {
            val bytes = "WEBVTT".toByteArray()
            val source = Source(this, { SubtitleFetch.Pending(10) }, { SubtitleFetch.Ready(bytes) })
            val events = collect(this, repo(this, source).subtitleLoop(url))
            assertEquals(listOf(0L, 10_000L), source.times)
            assertEquals(SubtitleFetch.Pending(10), events[0].second)
            assertEquals(10_000L, events[1].first)
            assertTrue((events[1].second as SubtitleFetch.Ready).bytes.contentEquals(bytes))
        }

        @Test
        fun `extraction failure, timeout and 404 are Unavailable without retry`() = runTest {
            for (code in listOf("subtitle_extraction_failed", "subtitle_timeout", "subtitle_not_found", "subtitle_not_text")) {
                val source = Source(this, { SubtitleFetch.Unavailable(code) })
                assertEquals(listOf(SubtitleFetch.Unavailable(code)), collect(this, repo(this, source).subtitleLoop(url)).map { it.second })
                assertEquals(1, source.times.size)
            }
        }

        @Test
        fun `the 10 min cap ends as Unavailable`() = runTest {
            val source = Source(this, { SubtitleFetch.Pending(10) })
            val events = collect(this, repo(this, source).subtitleLoop(url))
            val (at, last) = events.last()
            assertEquals(SubtitleFetch.Unavailable(null), last)
            assertEquals(600_000L, at)
        }

        @Test
        fun `three consecutive network failures end as Unavailable`() = runTest {
            val down = { throw ApiException.Unreachable(IOException("down")) }
            val source = Source(this, down, { SubtitleFetch.Pending(10) }, down, down, down)
            val events = collect(this, repo(this, source).subtitleLoop(url)).map { it.second }
            assertEquals(listOf(SubtitleFetch.Pending(10), SubtitleFetch.Unavailable(null)), events)
            assertEquals(5, source.times.size)
        }

        @Test
        fun `a 401 renews in the background and the loop continues`() = runTest {
            val h = Harness(this)
            val source = Source(this, { throw ApiException.Unauthorized("unauthorized", null) }, { SubtitleFetch.Ready(ByteArray(1)) })
            val events = collect(this, repo(this, source, h).subtitleLoop(url)).map { it.second }
            assertTrue(events.single() is SubtitleFetch.Ready)
            assertEquals(1, h.sessions.size)
        }

        @Test
        fun `polling stops when the track is no longer wanted or the collector is cancelled`() = runTest {
            var wanted = true
            val source = Source(this, { SubtitleFetch.Pending(10) })
            val r = repo(this, source)
            val job = backgroundScope.launch { r.subtitleLoop(url) { wanted }.toList() }
            advanceTimeBy(25_000)
            wanted = false
            advanceTimeBy(60_000)
            assertEquals(listOf(0L, 10_000L, 20_000L), source.times)
            job.cancel()

            val source2 = Source(this, { SubtitleFetch.Pending(10) })
            val job2 = backgroundScope.launch { repo(this@runTest, source2).subtitleLoop(url).toList() }
            advanceTimeBy(15_000)
            job2.cancel()
            advanceTimeBy(60_000)
            assertEquals(2, source2.times.size)
        }

        @Test
        fun `fetchSubtitle is one attempt and maps a network failure to Unreachable`() = runTest {
            val source = Source(this, { throw ApiException.Unreachable(IOException("down")) })
            assertTrue(repo(this, source).fetchSubtitle(url) is SubtitleFetch.Unreachable)
            assertEquals(1, source.times.size)
        }
    }
}
