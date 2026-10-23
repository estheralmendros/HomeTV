package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.Fixtures
import com.hometv.tv.core.api.apiCall
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SessionGuardTest {
    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val events = mutableListOf<AppEvent>()
    private lateinit var t: TestServer
    private lateinit var guard: SessionGuard

    private fun setUp(expiresAt: Instant = now.plusSeconds(20 * 86_400L), token: String? = "old") {
        t = TestServer(initialToken = token, expiresAt = expiresAt)
        guard = SessionGuard(t.store, { id -> apiCall { t.clients.api.createSession(id) } }, { events += it }, clock)
    }

    @AfterEach
    fun tearDown() = t.shutdown()

    /** /api/home answers 401 to the old token, 200 to any other token. */
    private fun routeHomeByToken() = t.route("GET", "/api/home?limit=20") { r ->
        if (r.getHeader("Authorization") == "Bearer old") t.error(401, "unauthorized") else t.json(Fixtures.home)
    }

    private suspend fun loadHome(origin: Origin = Origin.Foreground) = guard.withRenewal(origin) { apiCall { t.clients.api.home(20) } }

    @Test
    fun `a 401 renews once and retries the call with the new token`() = runTest {
        setUp()
        routeHomeByToken()
        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }

        val home = loadHome()

        assertEquals(1234, home.library.films)
        assertEquals(listOf("GET /api/home?limit=20", "POST /api/profiles/5/session", "GET /api/home?limit=20"), t.paths())
        assertEquals("Bearer new", t.requests.last().getHeader("Authorization"))
        assertEquals(0L, t.requests[1].bodySize)
        assertEquals("new", t.store.session.value.token)
        assertEquals(Instant.parse("2026-12-20T00:00:00Z"), t.store.session.value.expiresAt)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `two concurrent 401s cause a single session request`() = runTest {
        setUp()
        routeHomeByToken()
        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")).setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS) }

        val results = listOf(async { loadHome() }, async { loadHome() }, async { loadHome() }).awaitAll()

        assertEquals(3, results.size)
        assertEquals(1, t.count("POST", "/api/profiles/5/session"))
    }

    @Test
    fun `renewal 404 profile_not_found clears the token and emits AuthExpired`() = runTest {
        setUp()
        routeHomeByToken()
        t.route("POST", "/api/profiles/5/session") { t.error(404, "profile_not_found") }

        assertThrows<ApiException.ProfileGone> { loadHome() }

        assertEquals(listOf<AppEvent>(AppEvent.AuthExpired), events)
        assertNull(t.store.session.value.token)
        assertEquals(1, t.count("GET", "/api/home?limit=20"))
    }

    @Test
    fun `renewal IOException in the foreground emits ServerUnreachable`() = runTest {
        setUp()
        routeHomeByToken()
        t.route("POST", "/api/profiles/5/session") { t.networkFailure() }

        assertThrows<ApiException.Unreachable> { loadHome(Origin.Foreground) }

        assertEquals(1, events.size)
        assertTrue(events.single() is AppEvent.ServerUnreachable)
        assertEquals("old", t.store.session.value.token)
    }

    @Test
    fun `renewal IOException in the background emits nothing and the next background call renews again`() = runTest {
        setUp()
        routeHomeByToken()
        t.route("POST", "/api/profiles/5/session") { t.networkFailure() }

        val outcome = assertThrows<ApiException.Unreachable> { loadHome(Origin.Background) }
        assertTrue(events.isEmpty(), "$outcome")

        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }
        loadHome(Origin.Background)

        assertEquals(2, t.count("POST", "/api/profiles/5/session"))
        assertTrue(events.isEmpty())
        assertEquals("new", t.store.session.value.token)
    }

    @Test
    fun `renew returns its outcome and never navigates`() = runTest {
        setUp()
        t.route("POST", "/api/profiles/5/session") { t.networkFailure() }
        assertTrue(guard.renew("old") is RenewOutcome.Unreachable)
        t.route("POST", "/api/profiles/5/session") { t.error(404, "profile_not_found") }
        assertEquals(RenewOutcome.ProfileGone, guard.renew(t.store.session.value.token))
        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }
        assertEquals(RenewOutcome.Renewed, guard.renew(null))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a 401 on the retried call is an error, never a second renewal`() = runTest {
        setUp()
        t.route("GET", "/api/home?limit=20") { t.error(401, "unauthorized") }
        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }

        assertThrows<ApiException.Unauthorized> { loadHome() }

        assertEquals(1, t.count("POST", "/api/profiles/5/session"))
        assertEquals(2, t.count("GET", "/api/home?limit=20"))
    }

    @Test
    fun `ensureFresh renews only when expiresAt is within 24 h`() = runTest {
        setUp(expiresAt = now.plusSeconds(48 * 3600L))
        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }
        assertNull(guard.ensureFresh())
        assertEquals(0, t.count("POST", "/api/profiles/5/session"))
        t.shutdown()

        setUp(expiresAt = now.plusSeconds(23 * 3600L))
        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }
        assertEquals(RenewOutcome.Renewed, guard.ensureFresh())
        assertEquals(1, t.count("POST", "/api/profiles/5/session"))
        assertEquals("new", t.store.session.value.token)
    }

    @Test
    fun `ensureFresh at startup replaces an expired token silently`() = runTest {
        setUp(expiresAt = now.minusSeconds(60))
        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }
        assertEquals(RenewOutcome.Renewed, guard.ensureFresh())
        assertEquals("new", t.store.session.value.token)
    }

    @Test
    fun `ensureFresh ignores a network failure while the token is still valid`() = runTest {
        setUp(expiresAt = now.plusSeconds(3600))
        t.route("POST", "/api/profiles/5/session") { t.networkFailure() }
        assertNull(guard.ensureFresh())
        assertEquals("old", t.store.session.value.token)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `ensureFresh reports an unreachable server when the token already expired`() = runTest {
        setUp(expiresAt = now.minusSeconds(3600))
        t.route("POST", "/api/profiles/5/session") { t.networkFailure() }
        assertTrue(guard.ensureFresh() is RenewOutcome.Unreachable)
    }

    @Test
    fun `ensureFresh with a gone profile emits AuthExpired`() = runTest {
        setUp(expiresAt = now.plusSeconds(3600))
        t.route("POST", "/api/profiles/5/session") { t.error(404, "profile_not_found") }
        assertEquals(RenewOutcome.ProfileGone, guard.ensureFresh())
        assertEquals(listOf<AppEvent>(AppEvent.AuthExpired), events)
    }

    @Test
    fun `ensureFresh does nothing without a session`() = runTest {
        setUp(token = null)
        assertNull(guard.ensureFresh())
        assertTrue(t.requests.isEmpty())
    }
}
