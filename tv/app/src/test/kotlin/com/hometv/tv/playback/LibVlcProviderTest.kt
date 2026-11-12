package com.hometv.tv.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * AC-5.1 / AC-5.1b: availability never creates LibVLC; creation is lazy and a first failure is final.
 * prd3 T-AC-12 / T-AC-18b: one instance per option list, a rebuild releases the old one, a failed
 * rebuild keeps the old instance and is not retried for the same options.
 */
class LibVlcProviderTest {
    private class Instance(val options: List<String>)

    private val created = mutableListOf<List<String>>()
    private val events = mutableListOf<String>()
    private val logs = mutableListOf<Pair<String, Throwable?>>()
    private val failing = mutableSetOf<List<String>>()

    private val a = listOf("--freetype-rel-fontsize=19")
    private val b = listOf("--freetype-rel-fontsize=15")
    private val c = listOf("--freetype-rel-fontsize=24")

    private fun provider(abis: List<String> = listOf("arm64-v8a")) = LibVlcProvider<Instance>(
        abis,
        { opts ->
            created += opts
            if (opts in failing) throw UnsatisfiedLinkError("no libvlc.so")
            events += "create $opts"
            Instance(opts)
        },
        { events += "release ${it.options}" },
    ) { msg, e -> logs += msg to e }

    @Test
    fun `an x86 device is unavailable without creating anything`() {
        val p = provider(listOf("x86_64"))
        assertFalse(p.availability.isAvailable())
        assertTrue(created.isEmpty())
    }

    @Test
    fun `ARM is available before any get, the first get creates once and equal options reuse it`() {
        for (abis in listOf(listOf("arm64-v8a", "armeabi-v7a"), listOf("armeabi-v7a"), listOf("x86", "arm64-v8a"))) {
            created.clear()
            val p = provider(abis)
            assertTrue(p.availability.isAvailable(), "$abis")
            assertTrue(created.isEmpty())
            val first = p.get(a)
            assertSame(first, p.get(ArrayList(a)))
            assertEquals(1, created.size)
            assertTrue(p.availability.isAvailable())
        }
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `different options build a new instance, then release the old one`() {
        val p = provider()
        val first = p.get(a)
        val (second, applied) = p.getOrOld(b)
        assertTrue(applied)
        assertNotSame(first, second)
        assertEquals(b, second.options)
        assertEquals(listOf("create $a", "create $b", "release $a"), events)
        assertSame(second, p.get(b))
    }

    @Test
    fun `a failing first creation is logged once, turns availability off and is never called again`() {
        failing += a
        val p = provider()
        val e = assertThrows<VlcUnavailableException> { p.get(a) }
        assertTrue(e.cause is UnsatisfiedLinkError)
        assertEquals(1, logs.size)
        assertFalse(p.availability.isAvailable())
        assertThrows<VlcUnavailableException> { p.get(a) }
        assertThrows<VlcUnavailableException> { p.getOrOld(b) }
        assertEquals(1, created.size)
        assertEquals(1, logs.size)
    }

    @Test
    fun `a failed rebuild keeps the old instance, VLC stays available, the same options are not retried`() {
        val p = provider()
        val first = p.get(a)
        failing += b
        assertEquals(first to false, p.getOrOld(b))
        assertEquals(1, logs.size)
        assertTrue(p.availability.isAvailable())
        // Same failed list: the current instance at once, no second creation attempt.
        assertEquals(first to false, p.getOrOld(b))
        assertSame(first, p.get(b))
        assertEquals(listOf(a, b), created)
        // The old options still reuse the old instance.
        assertEquals(first to true, p.getOrOld(a))
        // Another list is tried; a success clears the memory, so b is tried again later.
        val (third, applied) = p.getOrOld(c)
        assertTrue(applied)
        assertEquals(listOf("create $a", "create $c", "release $a"), events)
        assertEquals(third to false, p.getOrOld(b))
        assertEquals(listOf(a, b, c, b), created)
    }
}
