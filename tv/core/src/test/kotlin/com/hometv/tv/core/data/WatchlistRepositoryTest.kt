package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import java.io.IOException
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** watchlist prd3 FR-T3: writes run in `appScope`, coalesced per title (latest wins), with the acknowledged value of one worker run. */
class WatchlistRepositoryTest {
    /** `PUT`/`DELETE /api/watchlist/...` recorded in order; each answer waits for its gate when one is set. */
    private class Api : FakeHomeTvApi() {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** Gates handed out in request order (one per request); null = answer at once. */
        val gates: MutableList<CompletableDeferred<Unit>> = Collections.synchronizedList(mutableListOf())
        var gated = false

        /** Thrown by the request with this (1-based) number. */
        val failures = mutableMapOf<Int, Exception>()

        override suspend fun addToWatchlist(segment: String, id: Long) = answer("PUT /api/watchlist/$segment/$id")

        override suspend fun removeFromWatchlist(segment: String, id: Long) = answer("DELETE /api/watchlist/$segment/$id")

        private suspend fun answer(call: String) {
            calls += call
            val number = calls.size
            if (gated) CompletableDeferred<Unit>().also { gates += it }.await()
            failures[number]?.let { throw it }
        }

        fun release(index: Int = 0) = gates[index].complete(Unit)
    }

    private val api = Api()
    private val store = InMemorySessionStore(Session("https://hometv.ts.net", 5, "tok", Instant.parse("2026-12-01T00:00:00Z")))
    private val guard = SessionGuard(store, { api.createSession(it) }, {})

    private fun TestScope.repo(scope: CoroutineScope = backgroundScope) = WatchlistRepository({ api }, guard, scope)

    @Test
    fun `the request still completes after the caller's job is cancelled`() = runTest {
        api.gated = true
        val repo = repo()
        val caller = launch { repo.write(WatchlistKind.FILM, 12, true).await() }
        runCurrent()
        caller.cancel()
        runCurrent()
        api.release()
        // runCurrent, not advanceUntilIdle: the latter stops once only backgroundScope (appScope) work is left.
        runCurrent()
        assertEquals(listOf("PUT /api/watchlist/films/12"), api.calls)
        assertFalse(repo.isWriting(WatchlistKind.FILM, 12))
        assertFalse(repo.writing.value)
    }

    @Test
    fun `presses made while a request is in flight send only the latest wish, once`() = runTest {
        api.gated = true
        val repo = repo()
        val done = repo.write(WatchlistKind.FILM, 12, true)
        runCurrent()
        repo.write(WatchlistKind.FILM, 12, false)
        repo.write(WatchlistKind.FILM, 12, true)
        repo.write(WatchlistKind.FILM, 12, false)
        runCurrent()
        assertEquals(1, api.calls.size)
        api.release(0)
        runCurrent()
        assertEquals(listOf("PUT /api/watchlist/films/12", "DELETE /api/watchlist/films/12"), api.calls)
        api.release(1)
        advanceUntilIdle()
        assertEquals(2, api.calls.size)
        assertEquals(false, done.await())
    }

    @Test
    fun `a latest wish equal to the acknowledged state sends nothing more`() = runTest {
        api.gated = true
        val repo = repo()
        val done = repo.write(WatchlistKind.SHOW, 4, true)
        runCurrent()
        repo.write(WatchlistKind.SHOW, 4, false)
        repo.write(WatchlistKind.SHOW, 4, true)
        api.release()
        advanceUntilIdle()
        assertEquals(listOf("PUT /api/watchlist/shows/4"), api.calls)
        assertEquals(true, done.await())
    }

    @Test
    fun `two titles write independently`() = runTest {
        api.gated = true
        val repo = repo()
        val film = repo.write(WatchlistKind.FILM, 12, true)
        val show = repo.write(WatchlistKind.SHOW, 12, false)
        runCurrent()
        assertEquals(listOf("PUT /api/watchlist/films/12", "DELETE /api/watchlist/shows/12"), api.calls)
        api.release(1)
        runCurrent()
        assertEquals(false, show.await())
        assertTrue(repo.isWriting(WatchlistKind.FILM, 12))
        assertFalse(repo.isWriting(WatchlistKind.SHOW, 12))
        assertTrue(repo.writing.value)
        api.release(0)
        advanceUntilIdle()
        assertEquals(true, film.await())
        assertFalse(repo.writing.value)
    }

    @Test
    fun `a failed first request fails with no acknowledged value and drops the pending wish`() = runTest {
        api.gated = true
        api.failures[1] = httpError(500, "internal_error")
        val repo = repo()
        val done = repo.write(WatchlistKind.FILM, 12, true)
        runCurrent()
        repo.write(WatchlistKind.FILM, 12, false)
        repo.write(WatchlistKind.FILM, 12, true)
        api.release()
        advanceUntilIdle()
        val e = assertThrows<WatchlistWriteException> { done.await() }
        assertNull(e.acknowledged)
        assertTrue(e.cause is ApiException.Http)
        assertEquals(listOf("PUT /api/watchlist/films/12"), api.calls, "nothing is retried")
        assertFalse(repo.writing.value)
    }

    @Test
    fun `a PUT that succeeded before a failing queued DELETE is the acknowledged value`() = runTest {
        api.gated = true
        api.failures[2] = IOException("connection reset")
        val repo = repo()
        val done = repo.write(WatchlistKind.FILM, 12, true)
        runCurrent()
        repo.write(WatchlistKind.FILM, 12, false)
        api.release(0)
        runCurrent()
        api.release(1)
        advanceUntilIdle()
        val e = assertThrows<WatchlistWriteException> { done.await() }
        assertEquals(true, e.acknowledged)
        assertTrue(e.cause is ApiException.Unreachable)
        assertEquals(listOf("PUT /api/watchlist/films/12", "DELETE /api/watchlist/films/12"), api.calls)
    }

    @Test
    fun `a write after the worker went idle starts with no acknowledged value`() = runTest {
        val repo = repo()
        assertEquals(true, repo.write(WatchlistKind.FILM, 12, true).await())
        // The server may have changed elsewhere (web, another TV) since: the same wish is sent again.
        assertEquals(true, repo.write(WatchlistKind.FILM, 12, true).await())
        assertEquals(listOf("PUT /api/watchlist/films/12", "PUT /api/watchlist/films/12"), api.calls)
    }

    @Test
    fun `writing and isWriting are true from the call until the worker is idle`() = runTest {
        api.gated = true
        val repo = repo()
        assertFalse(repo.writing.value)
        val done = repo.write(WatchlistKind.FILM, 12, true)
        assertTrue(repo.writing.value)
        assertTrue(repo.isWriting(WatchlistKind.FILM, 12))
        assertFalse(repo.isWriting(WatchlistKind.SHOW, 12))
        runCurrent()
        assertTrue(repo.isWriting(WatchlistKind.FILM, 12))
        api.release()
        advanceUntilIdle()
        done.await()
        assertFalse(repo.isWriting(WatchlistKind.FILM, 12))
        assertFalse(repo.writing.value)
    }

    @Test
    fun `call order is kept before any worker ran on a dispatcher with several workers`() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repo = repo(appScope)
        repo.write(WatchlistKind.FILM, 12, true)
        assertTrue(repo.isWriting(WatchlistKind.FILM, 12))
        repo.write(WatchlistKind.FILM, 12, false)
        assertTrue(repo.isWriting(WatchlistKind.FILM, 12))
        val done = repo.write(WatchlistKind.FILM, 12, true)
        assertTrue(repo.isWriting(WatchlistKind.FILM, 12))
        assertEquals(emptyList<String>(), api.calls, "no worker ran yet")
        advanceUntilIdle()
        assertEquals(listOf("PUT /api/watchlist/films/12"), api.calls)
        assertEquals(true, done.await())
        appScope.cancel()
    }

    @Test
    fun `call order is kept with workers on Dispatchers Default`() = runTest {
        api.gated = true
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = repo(appScope)
        val done = repo.write(WatchlistKind.FILM, 12, true)
        repo.write(WatchlistKind.FILM, 12, false)
        assertTrue(repo.isWriting(WatchlistKind.FILM, 12))
        repo.write(WatchlistKind.FILM, 12, true)
        assertTrue(repo.isWriting(WatchlistKind.FILM, 12))
        val result = withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                // Open every gate as it appears until the worker settles.
                var released = 0
                while (!done.isCompleted) {
                    if (api.gates.size > released) api.release(released++) else yield()
                }
                done.await()
            }
        }
        assertEquals(true, result)
        assertEquals("PUT /api/watchlist/films/12", api.calls.last())
        assertFalse(repo.writing.value)
        appScope.cancel()
    }

    @Test
    fun `awaitIdle returns at once when idle and resumes only after the last worker finished`() = runTest {
        api.gated = true
        api.failures[2] = httpError(500, "internal_error")
        val repo = repo()
        repo.awaitIdle()
        repo.write(WatchlistKind.FILM, 12, true)
        repo.write(WatchlistKind.SHOW, 4, true)
        var idle = false
        launch {
            repo.awaitIdle()
            idle = true
        }
        runCurrent()
        api.release(0)
        runCurrent()
        assertFalse(idle, "the show is still writing")
        api.release(1)
        runCurrent()
        assertTrue(idle, "a failed worker also ends the wait")
    }

    @Test
    fun `cancelling appScope mid-request leaves no title writing and cancels the result`() = runTest {
        api.gated = true
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repo = repo(appScope)
        val done = repo.write(WatchlistKind.FILM, 12, true)
        runCurrent()
        assertEquals(listOf("PUT /api/watchlist/films/12"), api.calls)
        appScope.cancel()
        runCurrent()
        assertFalse(repo.writing.value)
        assertFalse(repo.isWriting(WatchlistKind.FILM, 12))
        assertTrue(done.isCancelled)
    }

    @Test
    fun `a follow-up wish made under a profile that is no longer current is dropped`() = runTest {
        api.gated = true
        val repo = repo()
        val done = repo.write(WatchlistKind.FILM, 12, true)
        runCurrent()
        repo.write(WatchlistKind.FILM, 12, false)
        // The viewer switches profile while the first request is in flight.
        store.saveSession(6, "tok6", Instant.parse("2026-12-01T00:00:00Z"))
        api.release(0)
        advanceUntilIdle()
        assertEquals(listOf("PUT /api/watchlist/films/12"), api.calls, "the DELETE would hit profile 6's list")
        assertEquals(true, done.await(), "completes with the value profile 5's list has")
        assertFalse(repo.isWriting(WatchlistKind.FILM, 12))
        assertFalse(repo.writing.value)
    }

    @Test
    fun `a follow-up wish made under the new profile is still sent`() = runTest {
        api.gated = true
        val repo = repo()
        val done = repo.write(WatchlistKind.FILM, 12, true)
        runCurrent()
        store.saveSession(6, "tok6", Instant.parse("2026-12-01T00:00:00Z"))
        repo.write(WatchlistKind.FILM, 12, false)
        api.release(0)
        runCurrent()
        api.release(1)
        advanceUntilIdle()
        assertEquals(listOf("PUT /api/watchlist/films/12", "DELETE /api/watchlist/films/12"), api.calls)
        assertEquals(false, done.await())
    }
}
