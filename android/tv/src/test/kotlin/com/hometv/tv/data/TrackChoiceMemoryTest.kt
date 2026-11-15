package com.hometv.tv.data

import com.hometv.tv.core.api.AudioChoice
import com.hometv.tv.core.api.SubtitleChoice
import com.hometv.tv.core.api.TrackChoice
import com.hometv.tv.core.data.InMemorySessionStore
import com.hometv.tv.core.data.RatingKind
import com.hometv.tv.core.data.Session
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The process-lifetime memory of this device's picks (prd3 FR-T4 Session memory). */
class TrackChoiceMemoryTest {
    private val expires = Instant.parse("2026-12-20T00:00:00Z")
    private val store = InMemorySessionStore(Session("https://a.ts.net", 5, "tok", expires))
    private val eng = TrackChoice(AudioChoice("eng", null, 6), null)
    private val off = TrackChoice(null, SubtitleChoice("off"))
    private val show = RatingKind.SHOW

    private fun TestScope.memory() = TrackChoiceMemory(store.session, backgroundScope)

    @Test
    fun `put and get per profile and title, seqs and tickets strictly increasing`() = runTest(UnconfinedTestDispatcher()) {
        val m = memory()
        val s1 = m.put(5, show, 7, eng)
        assertEquals(eng, m.get(5, show, 7))
        assertNull(m.get(6, show, 7))
        assertNull(m.get(5, RatingKind.FILM, 7))
        val t = m.ticket()
        val s2 = m.put(5, show, 7, off)
        assertTrue(s1 < t && t < s2)
        assertEquals(off, m.get(5, show, 7))
    }

    @Test
    fun `fetched never drops an unconfirmed entry`() = runTest(UnconfinedTestDispatcher()) {
        val m = memory()
        m.put(5, show, 7, eng)
        m.fetched(5, show, 7, m.ticket())
        assertEquals(eng, m.get(5, show, 7))
    }

    @Test
    fun `a confirmed entry is dropped only by a fetch requested after the confirmation`() = runTest(UnconfinedTestDispatcher()) {
        val m = memory()
        val seq = m.put(5, show, 7, eng)
        val before = m.ticket()
        m.markConfirmed(5, show, 7, seq)
        m.fetched(5, show, 7, before)
        assertEquals(eng, m.get(5, show, 7))
        m.fetched(5, show, 7, m.ticket())
        assertNull(m.get(5, show, 7))
    }

    @Test
    fun `markConfirmed of an older seq is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val m = memory()
        val first = m.put(5, show, 7, eng)
        m.put(5, show, 7, off)
        m.markConfirmed(5, show, 7, first)
        m.fetched(5, show, 7, m.ticket())
        assertEquals(off, m.get(5, show, 7))
    }

    @Test
    fun `clear and a real server or profile change empty it`() = runTest(UnconfinedTestDispatcher()) {
        val m = memory()
        m.put(5, show, 7, eng)
        m.clear()
        assertNull(m.get(5, show, 7))
        m.put(5, show, 7, eng)
        store.saveSession(6, "tok-6", expires)
        assertNull(m.get(5, show, 7))
        m.put(6, show, 7, eng)
        store.setServerUrl("https://b.ts.net")
        assertNull(m.get(6, show, 7))
    }

    @Test
    fun `a confirmation of seq 1 racing a put of seq 2 never confirms the newer pick`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repeat(500) {
            val m = TrackChoiceMemory(store.session, scope)
            val seq1 = m.put(5, show, 7, eng)
            val start = CountDownLatch(1)
            val other = thread {
                start.await()
                m.markConfirmed(5, show, 7, seq1)
            }
            start.countDown()
            m.put(5, show, 7, off)
            other.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse(other.isAlive, "the confirming thread finished")
            m.fetched(5, show, 7, m.ticket())
            assertEquals(off, m.get(5, show, 7))
        }
    }

    @Test
    fun `clear racing puts never throws and leaves only entries put after it`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repeat(200) {
            val m = TrackChoiceMemory(store.session, scope)
            repeat(20) { i -> m.put(5, show, i.toLong(), eng) }
            val start = CountDownLatch(1)
            val errors = mutableListOf<Throwable>()
            val cleared = CountDownLatch(1)
            val other = thread {
                start.await()
                try {
                    m.clear()
                } catch (e: Throwable) {
                    synchronized(errors) { errors += e }
                }
                cleared.countDown()
            }
            start.countDown()
            repeat(20) { i -> m.put(5, show, 100L + i, off) }
            cleared.await(5, TimeUnit.SECONDS)
            other.join()
            assertTrue(errors.isEmpty(), "$errors")
            // Entries put before the race are gone; any survivor is one of the racing puts.
            repeat(20) { i -> assertNull(m.get(5, show, i.toLong())) }
        }
    }
}
