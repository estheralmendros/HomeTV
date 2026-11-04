package com.hometv.tv.core.data

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [InMemorySessionStore]: sign-out and the writes conditional on the stored profile (FR-6). */
class SessionStoreTest {
    private val url = "https://hometv.tail1234.ts.net"
    private val t1 = Instant.parse("2026-11-20T00:00:00Z")
    private val t2 = Instant.parse("2026-12-20T00:00:00Z")

    private fun signedIn(profileId: Long = 5, token: String = "tok") =
        InMemorySessionStore(Session(serverUrl = url, profileId = profileId, token = token, expiresAt = t1))

    @Test
    fun `signOut keeps only the server URL`() = runTest {
        val store = InMemorySessionStore(Session(serverUrl = url))
        store.saveSession(5, "tok", t1)

        store.signOut()

        assertEquals(Session(serverUrl = url), store.session.value)
    }

    @Test
    fun `saveRenewedSession replaces token and expiry for the same profile`() = runTest {
        val store = signedIn()

        assertTrue(store.saveRenewedSession(5, "new", t2))

        assertEquals(Session(url, 5, "new", t2), store.session.value)
    }

    @Test
    fun `saveRenewedSession refuses another profile and a signed-out store`() = runTest {
        val other = signedIn(profileId = 7, token = "tok7")
        assertFalse(other.saveRenewedSession(5, "new", t2))
        assertEquals(Session(url, 7, "tok7", t1), other.session.value)

        val signedOut = signedIn().apply { signOut() }
        assertFalse(signedOut.saveRenewedSession(5, "new", t2))
        assertEquals(Session(serverUrl = url), signedOut.session.value)
    }

    @Test
    fun `clearToken removes token and expiry and keeps the server and profile for the same profile`() = runTest {
        val store = signedIn()

        assertTrue(store.clearToken(5))

        assertEquals(Session(url, 5, null, null), store.session.value)
    }

    @Test
    fun `clearToken refuses another profile and a signed-out store`() = runTest {
        val other = signedIn(profileId = 7, token = "tok7")
        assertFalse(other.clearToken(5))
        assertEquals(Session(url, 7, "tok7", t1), other.session.value)

        val signedOut = signedIn().apply { signOut() }
        assertFalse(signedOut.clearToken(5))
        assertEquals(Session(serverUrl = url), signedOut.session.value)
    }
}
