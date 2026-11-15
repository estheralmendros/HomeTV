package com.hometv.tv.core.data

import java.io.IOException
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The shared latest-wins queue: per key, one write in flight, wishes bound to their profile, one cancellation rule. */
class LatestWinsWriterTest {
    private val store = InMemorySessionStore(Session("https://hometv.ts.net", 5, "tok", Instant.parse("2026-12-01T00:00:00Z")))
    private val written: MutableList<Pair<String, Int>> = Collections.synchronizedList(mutableListOf())
    private val gates: MutableList<CompletableDeferred<Unit>> = Collections.synchronizedList(mutableListOf())
    private val logged = mutableListOf<Throwable>()
    private var failWith: Exception? = null

    private fun TestScope.writer(scope: CoroutineScope = backgroundScope) = LatestWinsWriter<String, Int>(store, scope, { logged += it }) { key, value ->
        written += key to value
        CompletableDeferred<Unit>().also { gates += it }.await()
        failWith?.let { throw it }
    }

    private suspend fun switchProfile(id: Long) = store.saveSession(id, "tok-$id", Instant.parse("2026-12-01T00:00:00Z"))

    @Test
    fun `a queued wish of the same profile is replaced and its waiters get the newer result`() = runTest {
        val w = writer()
        val first = w.submit("a", 1)
        runCurrent()
        val second = w.submit("a", 2)
        val third = w.submit("a", 3)
        gates[0].complete(Unit)
        runCurrent()
        gates[1].complete(Unit)
        runCurrent()
        assertEquals(listOf("a" to 1, "a" to 3), written)
        assertTrue(first.await() && second.await() && third.await())
        w.awaitIdle()
    }

    @Test
    fun `a wish replaced by another profile's wish completes false at once and is never written`() = runTest {
        val w = writer()
        w.submit("a", 1)
        runCurrent()
        val old = w.submit("a", 2)
        switchProfile(9)
        val new = w.submit("a", 3)
        assertTrue(old.isCompleted)
        assertFalse(old.await())
        gates[0].complete(Unit)
        runCurrent()
        gates[1].complete(Unit)
        runCurrent()
        assertEquals(listOf("a" to 1, "a" to 3), written)
        assertTrue(new.await())
    }

    @Test
    fun `a wish whose profile is gone when reached is dropped`() = runTest {
        val w = writer()
        w.submit("a", 1)
        runCurrent()
        val queued = w.submit("a", 2)
        switchProfile(9)
        gates[0].complete(Unit)
        runCurrent()
        assertEquals(listOf("a" to 1), written)
        assertFalse(queued.await())
        w.awaitIdle()
    }

    @Test
    fun `a failed write is logged and resolves false`() = runTest {
        failWith = IOException("down")
        val w = writer()
        val done = w.submit("a", 1)
        runCurrent()
        gates[0].complete(Unit)
        runCurrent()
        assertFalse(done.await())
        assertEquals(1, logged.size)
    }

    @Test
    fun `cancellation completes every pending wish false and frees the key`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val w = writer(scope)
        val inFlight = w.submit("a", 1)
        runCurrent()
        val queued = w.submit("a", 2)
        scope.cancel()
        runCurrent()
        assertFalse(inFlight.await())
        assertFalse(queued.await())
        assertTrue(logged.isEmpty())
        w.awaitIdle()
        // A cancelled scope never runs a new worker: the wish still completes.
        assertFalse(w.submit("a", 3).await())
    }
}
