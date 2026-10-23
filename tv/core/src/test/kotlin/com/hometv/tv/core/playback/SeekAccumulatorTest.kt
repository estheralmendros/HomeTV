package com.hometv.tv.core.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Times are a fake clock in milliseconds. */
class SeekAccumulatorTest {
    private val duration = 3_600_000L

    @Test
    fun `a short press seeks 10 s and commits once, 600 ms after the key`() {
        val seek = SeekAccumulator()
        assertEquals(70_000L, seek.onKeyDown(forward = true, repeatCount = 0, nowMs = 1_000, positionMs = 60_000, durationMs = duration))
        assertEquals(70_000L, seek.pendingTargetMs)
        assertNull(seek.onKeyUp(nowMs = 1_100))
        assertEquals(1_600L, seek.commitDueAtMs())
        assertNull(seek.poll(1_599))
        assertEquals(70_000L, seek.poll(1_600))
        assertNull(seek.pendingTargetMs)
        assertNull(seek.poll(5_000))
    }

    @Test
    fun `rewind by 10 s, and quick presses accumulate into one commit`() {
        val seek = SeekAccumulator()
        seek.onKeyDown(false, 0, 0, 60_000, duration)
        seek.onKeyUp(80)
        seek.onKeyDown(false, 0, 300, 60_000, duration)
        seek.onKeyUp(380)
        assertNull(seek.poll(850))
        assertEquals(40_000L, seek.poll(900))
    }

    @Test
    fun `steps escalate with the repeat count`() {
        assertEquals(10_000L, SeekAccumulator.stepMs(0))
        assertEquals(10_000L, SeekAccumulator.stepMs(4))
        assertEquals(30_000L, SeekAccumulator.stepMs(5))
        assertEquals(30_000L, SeekAccumulator.stepMs(14))
        assertEquals(60_000L, SeekAccumulator.stepMs(15))
        assertEquals(60_000L, SeekAccumulator.stepMs(100))
    }

    @Test
    fun `holding the key accumulates and commits a single seek on release`() {
        val seek = SeekAccumulator()
        var target = 0L
        for (repeat in 0..16) target = seek.onKeyDown(true, repeat, repeat * 50L, positionMs = 0, durationMs = duration)
        // 5 × 10 s + 10 × 30 s + 2 × 60 s
        assertEquals(470_000L, target)
        assertNull(seek.poll(16 * 50L + 100))
        assertEquals(470_000L, seek.onKeyUp(16 * 50L + 200))
        assertNull(seek.pendingTargetMs)
    }

    @Test
    fun `clamps to 0 and to duration minus 1 s`() {
        val back = SeekAccumulator()
        assertEquals(0L, back.onKeyDown(false, 0, 0, 4_000, duration))
        val forward = SeekAccumulator()
        assertEquals(duration - 1_000, forward.onKeyDown(true, 0, 0, duration - 3_000, duration))
        assertEquals(duration - 1_000, forward.onKeyDown(true, 0, 100, duration - 3_000, duration))
    }

    @Test
    fun `no upper clamp while the duration is unknown`() {
        val seek = SeekAccumulator()
        assertEquals(10_000_000L + 10_000, seek.onKeyDown(true, 0, 0, 10_000_000, null))
        assertEquals(0L, SeekAccumulator().onKeyDown(false, 0, 0, 3_000, null))
    }

    @Test
    fun `a new gesture after a commit starts from the current position`() {
        val seek = SeekAccumulator()
        seek.onKeyDown(true, 0, 0, 60_000, duration)
        assertEquals(70_000L, seek.poll(600))
        assertEquals(85_000L, seek.onKeyDown(true, 0, 2_000, 75_000, duration))
    }

    @Test
    fun `cancel drops the pending target`() {
        val seek = SeekAccumulator()
        seek.onKeyDown(true, 0, 0, 60_000, duration)
        seek.cancel()
        assertNull(seek.pendingTargetMs)
        assertNull(seek.commitDueAtMs())
        assertNull(seek.poll(10_000))
    }
}
