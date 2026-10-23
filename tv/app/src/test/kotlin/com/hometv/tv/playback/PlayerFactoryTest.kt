package com.hometv.tv.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** prd3 Playback Flow rule 8: byte target `memoryClass × 1 MiB × 0.5`, clamped 48–128 MiB; buffers > one 30 s segment. */
class PlayerFactoryTest {
    private val mib = 1024 * 1024

    @Test
    fun `the byte target follows the memory class within 48 to 128 MiB`() {
        assertEquals(48 * mib, PlayerFactory.targetBufferBytes(64))
        assertEquals(96 * mib, PlayerFactory.targetBufferBytes(192))
        assertEquals(128 * mib, PlayerFactory.targetBufferBytes(512))
    }

    @Test
    fun `buffer durations exceed one 30 s segment`() {
        assertEquals(50_000, PlayerFactory.MIN_BUFFER_MS)
        assertEquals(50_000, PlayerFactory.MAX_BUFFER_MS)
        assertEquals(1_500, PlayerFactory.BUFFER_FOR_PLAYBACK_MS)
        assertEquals(3_000, PlayerFactory.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
    }
}
