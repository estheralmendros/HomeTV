package com.hometv.tv.core.playback

import com.hometv.tv.core.api.ProgressRequest
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProgressReporterTest {
    private class Rig(val scope: TestScope, var sendDelayMs: Long = 0, var fail: Boolean = false) {
        var position = 100.0
        var duration: Double? = 1320.0
        var fileId = 91L
        val sent = mutableListOf<Pair<Long, ProgressRequest>>()
        val errors = mutableListOf<Throwable>()

        fun reporter(reportScope: CoroutineScope = scope.backgroundScope) = ProgressReporter(
            scope = reportScope,
            send = { r ->
                if (sendDelayMs > 0) delay(sendDelayMs)
                if (fail) throw IOException("down")
                sent += scope.testScheduler.currentTime to r
            },
            snapshot = { ProgressSnapshot("episode", 345, fileId, position, duration) },
            log = { errors += it },
        )

        fun times() = sent.map { it.first }
    }

    @Test
    fun `reports every 10 s only while playing`() = runTest {
        val rig = Rig(this)
        val r = rig.reporter()
        r.onPlayingChanged(true)
        advanceTimeBy(35_000)
        assertEquals(listOf(10_000L, 20_000L, 30_000L), rig.times())
        r.onPlayingChanged(false)
        advanceTimeBy(60_000)
        assertEquals(3, rig.sent.size)
        r.onPlayingChanged(true)
        advanceTimeBy(10_001)
        assertEquals(105_000L, rig.times().last())
    }

    @Test
    fun `reports immediately on pause and on end with position = duration`() = runTest {
        val rig = Rig(this)
        val r = rig.reporter()
        rig.position = 600.0
        r.onPaused()
        runCurrent()
        assertEquals(ProgressRequest("episode", 345, 91, 600.0, 1320.0), rig.sent.single().second)
        r.onEnded()
        runCurrent()
        assertEquals(1320.0, rig.sent.last().second.position_seconds)
    }

    @Test
    fun `a committed seek is reported after a 2 s debounce`() = runTest {
        val rig = Rig(this)
        val r = rig.reporter()
        r.onSeekCommitted()
        advanceTimeBy(1_000)
        r.onSeekCommitted()
        advanceTimeBy(1_999)
        assertTrue(rig.sent.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf(3_000L), rig.times())
    }

    @Test
    fun `single request in flight - a newer report replaces a queued one`() = runTest {
        val rig = Rig(this, sendDelayMs = 5_000)
        val r = rig.reporter()
        rig.position = 1.0
        r.report()
        runCurrent()
        rig.position = 2.0
        r.report()
        rig.position = 3.0
        r.report()
        advanceTimeBy(20_000)
        assertEquals(listOf(1.0, 3.0), rig.sent.map { it.second.position_seconds })
        assertEquals(listOf(5_000L, 10_000L), rig.times())
    }

    @Test
    fun `failures are logged and ignored`() = runTest {
        val rig = Rig(this, fail = true)
        val r = rig.reporter()
        r.report()
        runCurrent()
        rig.fail = false
        r.report()
        runCurrent()
        assertEquals(1, rig.errors.size)
        assertEquals(1, rig.sent.size)
    }

    @Test
    fun `no report while the duration is unknown, then reports include it`() = runTest {
        val rig = Rig(this)
        rig.duration = null
        val r = rig.reporter()
        r.onPlayingChanged(true)
        r.onPaused()
        advanceTimeBy(25_000)
        assertTrue(rig.sent.isEmpty())
        assertNull(r.finalReport())
        rig.duration = 5000.0
        advanceTimeBy(10_000)
        assertEquals(5000.0, rig.sent.single().second.duration_seconds)
    }

    @Test
    fun `media_file_id follows the current part`() = runTest {
        val rig = Rig(this)
        val r = rig.reporter()
        r.report()
        runCurrent()
        rig.fileId = 92
        r.report()
        runCurrent()
        assertEquals(listOf(91L, 92L), rig.sent.map { it.second.media_file_id })
    }

    @Test
    fun `the final report runs in the final scope, survives the reporter's scope and stops the ticker`() = runTest {
        val rig = Rig(this, sendDelayMs = 3_000)
        val vmScope = CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job())
        val r = rig.reporter(vmScope)
        r.onPlayingChanged(true)
        rig.position = 777.0
        r.finish(backgroundScope)
        vmScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        advanceTimeBy(60_000)
        assertEquals(listOf(3_000L to ProgressRequest("episode", 345, 91, 777.0, 1320.0)), rig.sent)
    }

    @Test
    fun `the final report at the end carries the duration as position`() = runTest {
        val rig = Rig(this)
        val r = rig.reporter()
        assertEquals(1320.0, r.finalReport(atEnd = true)?.position_seconds)
        assertEquals(100.0, r.finalReport()?.position_seconds)
    }

    @Test
    fun `a final report that never answers is abandoned after 10 s`() = runTest {
        val rig = Rig(this, sendDelayMs = 60_000)
        val r = rig.reporter()
        r.finish(backgroundScope)
        advanceTimeBy(120_000)
        assertTrue(rig.sent.isEmpty())
    }
}
