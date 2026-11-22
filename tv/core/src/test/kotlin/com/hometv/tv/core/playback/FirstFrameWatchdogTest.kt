package com.hometv.tv.core.playback

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FirstFrameWatchdogTest {
    private val dog = FirstFrameWatchdog()

    /** Ticks every second from [from] for [seconds], in one state; true if any tick fired. */
    private fun run(from: Long, seconds: Int, state: PlayerState = PlayerState.READY, playing: Boolean = true): Boolean {
        var fired = false
        for (i in 0..seconds) fired = dog.tick(from + i * 1000L, state, playing) || fired
        return fired
    }

    @Test
    fun `fires after 5 s of READY and playing`() {
        assertFalse(run(0, 4))
        assertTrue(dog.tick(5_000, PlayerState.READY, true))
    }

    @Test
    fun `buffering and pause time do not count`() {
        assertFalse(run(0, 60, PlayerState.BUFFERING))
        assertFalse(run(60_000, 60, PlayerState.READY, playing = false))
        assertFalse(run(120_000, 4))
        assertTrue(dog.tick(125_000, PlayerState.READY, true))
    }

    @Test
    fun `a first frame before 5 s never fires`() {
        run(0, 3)
        dog.onFirstFrame()
        assertFalse(run(4_000, 60))
    }

    @Test
    fun `fires once per arm and reset re-arms`() {
        assertTrue(run(0, 6))
        assertFalse(run(7_000, 30))
        dog.reset()
        assertFalse(run(100_000, 4))
        assertTrue(dog.tick(105_000, PlayerState.READY, true))
    }

    @Test
    fun `counted time is kept across pauses and cleared by reset`() {
        run(0, 3)
        run(4_000, 30, playing = false)
        assertTrue(dog.tick(40_000, PlayerState.READY, true).not() && run(41_000, 3))
        dog.reset()
        assertFalse(run(200_000, 4))
    }

    @Test
    fun `a slow start then a frame never fires`() {
        run(0, 20, PlayerState.BUFFERING)
        run(20_000, 1)
        dog.onFirstFrame()
        assertFalse(run(22_000, 30))
    }
}
