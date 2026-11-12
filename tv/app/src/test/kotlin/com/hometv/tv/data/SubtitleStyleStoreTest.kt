package com.hometv.tv.data

import com.hometv.tv.core.api.SubtitleStyle
import com.hometv.tv.core.playback.SubtitleLook
import com.hometv.tv.core.playback.SubtitlePosition
import com.hometv.tv.core.playback.SubtitleSize
import com.hometv.tv.testing.TestRepos
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The style writes: applied at once, one PUT in flight, latest wins, thread-safe. */
class SubtitleStyleStoreTest {
    private val large = SubtitleLook.DEFAULT.copy(size = SubtitleSize.LARGE)
    private val high = SubtitleLook.DEFAULT.copy(position = SubtitlePosition.HIGH)
    private val small = SubtitleLook.DEFAULT.copy(size = SubtitleSize.SMALL)

    private class Fixture(scope: CoroutineScope) {
        val repos = TestRepos(appScope = scope)
        val account = AccountState(repos.profiles, repos.store, scope)
        val logged: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val styles = SubtitleStyleStore(account, repos.profiles, repos.store, scope) { logged += it }
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        var gated = false

        init {
            repos.api.onSubtitleStyleWrite = { if (gated) CompletableDeferred<Unit>().also { synchronized(gates) { gates += it } }.await() }
        }
    }

    private fun TestScope.fixture() = Fixture(backgroundScope)

    @Test
    fun `save applies the look at once and PUTs every key`() = runTest {
        val f = fixture()
        val done = f.styles.save(large)
        assertEquals(large, f.styles.style.value)
        runCurrent()
        assertEquals(listOf(large.toWire()), f.repos.api.subtitleStyleBodies)
        assertTrue(done.await())
    }

    @Test
    fun `saves made while one is in flight send only the latest, after it settles`() = runTest {
        val f = fixture()
        f.gated = true
        val first = f.styles.save(large)
        runCurrent()
        val second = f.styles.save(high)
        val third = f.styles.save(small)
        assertEquals(small, f.styles.style.value)
        runCurrent()
        assertEquals(1, f.repos.api.subtitleStyleBodies.size)
        f.gates[0].complete(Unit)
        runCurrent()
        assertEquals(listOf(large.toWire(), small.toWire()), f.repos.api.subtitleStyleBodies)
        f.gates[1].complete(Unit)
        runCurrent()
        assertTrue(first.await())
        assertTrue(second.await())
        assertTrue(third.await())
    }

    @Test
    fun `a failed PUT is logged, resolves false and keeps the applied look`() = runTest {
        val f = fixture()
        f.repos.api.onSubtitleStyleWrite = { throw IOException("down") }
        val done = f.styles.save(large)
        runCurrent()
        assertFalse(done.await())
        assertEquals(large, f.styles.style.value)
        assertEquals(1, f.logged.size)
    }

    @Test
    fun `a queued look of a profile no longer stored is dropped`() = runTest {
        val f = fixture()
        f.gated = true
        f.styles.save(large)
        runCurrent()
        val queued = f.styles.save(high)
        f.repos.store.saveSession(6, "tok-6", Instant.parse("2026-12-20T00:00:00Z"))
        f.gates[0].complete(Unit)
        runCurrent()
        assertEquals(listOf(large.toWire()), f.repos.api.subtitleStyleBodies)
        assertFalse(queued.await())
    }

    @Test
    fun `a look replaced by another profile's look resolves false and is never sent`() = runTest {
        val f = fixture()
        f.gated = true
        val first = f.styles.save(large)
        runCurrent()
        val oldProfile = f.styles.save(high)
        f.repos.store.saveSession(6, "tok-6", Instant.parse("2026-12-20T00:00:00Z"))
        val newProfile = f.styles.save(small)
        assertTrue(oldProfile.isCompleted)
        assertFalse(oldProfile.await())
        f.gates[0].complete(Unit)
        runCurrent()
        f.gates[1].complete(Unit)
        runCurrent()
        assertEquals(listOf(large.toWire(), small.toWire()), f.repos.api.subtitleStyleBodies)
        assertTrue(first.await())
        assertTrue(newProfile.await())
    }

    @Test
    fun `a save racing the completion of the in-flight PUT is never lost and every Deferred completes`() {
        repeat(200) { iteration ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val f = Fixture(scope)
                val release = CountDownLatch(1)
                val putStarted = CountDownLatch(1)
                f.repos.api.onSubtitleStyleWrite = { body ->
                    if (body == large.toWire()) {
                        putStarted.countDown()
                        runInterruptible { release.await(5, TimeUnit.SECONDS) }
                    }
                }
                val first = f.styles.save(large)
                assertTrue(putStarted.await(5, TimeUnit.SECONDS))
                val start = CountDownLatch(1)
                var second: Deferred<Boolean>? = null
                val other = thread {
                    start.await()
                    second = f.styles.save(high)
                }
                start.countDown()
                release.countDown()
                other.join()
                runBlocking {
                    withTimeout(5_000) {
                        assertTrue(first.await(), "iteration $iteration")
                        assertTrue(second!!.await(), "iteration $iteration")
                    }
                }
                val bodies: List<SubtitleStyle> = f.repos.api.subtitleStyleBodies.toList()
                assertEquals(high.toWire(), bodies.last(), "iteration $iteration: $bodies")
            } finally {
                scope.cancel()
            }
        }
    }
}
