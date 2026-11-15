package com.hometv.tv.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A profile's rating in ½ steps (1..10 halves). */
class StarsTest {
    @Test
    fun `of rounds to the nearest half and is null outside one half to five`() {
        assertEquals(Stars(9), Stars.of(4.5))
        assertEquals(Stars(8), Stars.of(4.0))
        assertEquals(Stars(1), Stars.of(0.5))
        assertEquals(Stars(10), Stars.of(5.0))
        // Averages: 4.3 → 4½, 3.2 → 3, 3.25 → 3½ (half up).
        assertEquals(Stars(9), Stars.of(4.3))
        assertEquals(Stars(6), Stars.of(3.2))
        assertEquals(Stars(7), Stars.of(3.25))
        assertNull(Stars.of(null))
        assertNull(Stars.of(0.0))
        assertNull(Stars.of(0.4))
        assertNull(Stars.of(5.1))
        assertNull(Stars.of(Double.NaN))
    }

    @Test
    fun `value is the number of stars`() {
        assertEquals(4.5, Stars(9).value)
        assertEquals(0.5, Stars(1).value)
        assertEquals(5.0, Stars(10).value)
    }

    @Test
    fun `out of range halves are rejected`() {
        assertThrows<IllegalArgumentException> { Stars(0) }
        assertThrows<IllegalArgumentException> { Stars(11) }
    }

    @Test
    fun `fills are always five`() {
        val f = StarFill.FULL
        val h = StarFill.HALF
        val e = StarFill.EMPTY
        assertEquals(listOf(f, f, f, f, h), Stars(9).fills())
        assertEquals(listOf(f, f, f, f, e), Stars(8).fills())
        assertEquals(listOf(h, e, e, e, e), Stars(1).fills())
        assertEquals(listOf(f, f, f, f, f), Stars(10).fills())
        assertEquals(listOf(e, e, e, e, e), Stars.fillsOf(null))
    }

    @Test
    fun `step is clamped to one half and five`() {
        assertEquals(Stars(10), Stars(9).step(1))
        assertEquals(Stars(10), Stars(10).step(1))
        assertEquals(Stars(1), Stars(1).step(-1))
        assertEquals(Stars(7), Stars(8).step(-1))
        assertEquals(Stars(1), Stars.first())
    }
}
