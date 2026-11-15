package com.hometv.tv.data

import com.hometv.tv.core.api.RatingPrompt
import com.hometv.tv.core.data.InMemorySessionStore
import com.hometv.tv.core.data.Session
import java.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The app-scoped prompt store (once per title per process, profile-safe). */
class RatingPromptsTest {
    private val expires = Instant.parse("2026-12-20T00:00:00Z")
    private val store = InMemorySessionStore(Session("https://a.ts.net", 5, "tok", expires))
    private val dune = RatingPrompt("film", 12, "Dune")
    private val office = RatingPrompt("show", 7, "The Office")

    private fun TestScope.prompts() = RatingPrompts(store.session, backgroundScope)

    @Test
    fun `an offer is pending until taken, then handled for good`() = runTest(UnconfinedTestDispatcher()) {
        val prompts = prompts()
        prompts.offer(5, dune)
        assertEquals(PendingPrompt(5, dune), prompts.pending.value)
        assertEquals(PendingPrompt(5, dune), prompts.take(5))
        assertNull(prompts.pending.value)
        // A later report of the same title (same profile) never reopens it.
        prompts.offer(5, dune)
        assertNull(prompts.pending.value)
        assertNull(prompts.take(5))
        // Another profile has its own handled keys.
        prompts.offer(6, dune)
        assertEquals(PendingPrompt(6, dune), prompts.pending.value)
    }

    @Test
    fun `a newer offer for another title replaces an untaken one`() = runTest(UnconfinedTestDispatcher()) {
        val prompts = prompts()
        prompts.offer(5, dune)
        prompts.offer(5, office)
        assertEquals(PendingPrompt(5, office), prompts.take(5))
        // Dune was never shown: not handled.
        prompts.offer(5, dune)
        assertEquals(PendingPrompt(5, dune), prompts.pending.value)
    }

    @Test
    fun `take for another profile drops the prompt without marking it handled`() = runTest(UnconfinedTestDispatcher()) {
        val prompts = prompts()
        prompts.offer(5, dune)
        assertNull(prompts.take(6))
        assertNull(prompts.pending.value)
        assertNull(prompts.take(null))
        prompts.offer(5, dune)
        assertEquals(PendingPrompt(5, dune), prompts.take(5))
    }

    @Test
    fun `an offer without a profile is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val prompts = prompts()
        prompts.offer(null, dune)
        assertNull(prompts.pending.value)
    }

    @Test
    fun `a real profile or server change clears pending and handled`() = runTest(UnconfinedTestDispatcher()) {
        val prompts = prompts()
        prompts.offer(5, dune)
        prompts.take(5)
        prompts.offer(5, office)
        store.saveSession(6, "tok-6", expires)
        assertNull(prompts.pending.value)
        store.saveSession(5, "tok-5", expires)
        prompts.offer(5, dune)
        assertEquals(PendingPrompt(5, dune), prompts.pending.value, "handled keys were cleared")
        prompts.take(5)
        store.setServerUrl("https://b.ts.net")
        prompts.offer(5, dune)
        assertEquals(PendingPrompt(5, dune), prompts.pending.value)
    }

    @Test
    fun `a renewed token or the picker opened and closed changes nothing`() = runTest(UnconfinedTestDispatcher()) {
        val prompts = prompts()
        prompts.offer(5, dune)
        prompts.take(5)
        prompts.offer(5, office)
        // Silent renewal for the same profile; switchProfile() only navigates (no session change).
        store.saveSession(5, "tok-new", expires)
        assertEquals(PendingPrompt(5, office), prompts.pending.value)
        prompts.offer(5, dune)
        assertEquals(PendingPrompt(5, office), prompts.pending.value, "Dune stays handled")
    }

    @Test
    fun `clear drops everything`() = runTest(UnconfinedTestDispatcher()) {
        val prompts = prompts()
        prompts.offer(5, dune)
        prompts.take(5)
        prompts.offer(5, office)
        prompts.clear()
        assertNull(prompts.pending.value)
        prompts.offer(5, dune)
        assertEquals(PendingPrompt(5, dune), prompts.pending.value)
    }

    @Test
    fun `setHeld toggles held and clear leaves it`() = runTest(UnconfinedTestDispatcher()) {
        val prompts = prompts()
        assertFalse(prompts.held.value)
        prompts.setHeld(true)
        assertTrue(prompts.held.value)
        prompts.offer(5, dune)
        prompts.clear()
        assertTrue(prompts.held.value)
        assertNull(prompts.pending.value)
        prompts.setHeld(false)
        assertFalse(prompts.held.value)
    }
}
