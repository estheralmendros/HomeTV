package com.hometv.tv.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** AC-5.1 / AC-5.1b: availability never creates LibVLC; creation is lazy, once, and a failure is final. */
class LibVlcProviderTest {
    private var created = 0
    private val logs = mutableListOf<Pair<String, Throwable?>>()

    private fun provider(abis: List<String>, factory: () -> Any = { Any().also { created++ } }) =
        LibVlcProvider(abis, factory) { msg, e -> logs += msg to e }

    @Test
    fun `an x86 device is unavailable without creating anything`() {
        val p = provider(listOf("x86_64"))
        assertFalse(p.availability.isAvailable())
        assertEquals(0, created)
    }

    @Test
    fun `ARM is available before any get, the first get creates once`() {
        for (abis in listOf(listOf("arm64-v8a", "armeabi-v7a"), listOf("armeabi-v7a"), listOf("x86", "arm64-v8a"))) {
            created = 0
            val p = provider(abis)
            assertTrue(p.availability.isAvailable(), "$abis")
            assertEquals(0, created)
            val first = p.get()
            assertSame(first, p.get())
            assertEquals(1, created)
            assertTrue(p.availability.isAvailable())
        }
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `a failing factory is logged once, turns availability off and is never called again`() {
        var calls = 0
        val p = provider(listOf("arm64-v8a")) {
            calls++
            throw UnsatisfiedLinkError("no libvlc.so")
        }
        val e = assertThrows<VlcUnavailableException> { p.get() }
        assertTrue(e.cause is UnsatisfiedLinkError)
        assertEquals(1, logs.size)
        assertTrue(logs.single().second is UnsatisfiedLinkError)
        assertFalse(p.availability.isAvailable())
        assertThrows<VlcUnavailableException> { p.get() }
        assertEquals(1, calls)
        assertEquals(1, logs.size)
    }
}
