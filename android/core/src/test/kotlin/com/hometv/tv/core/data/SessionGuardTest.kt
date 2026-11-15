package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.Fixtures
import com.hometv.tv.core.api.apiCall
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
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

    private val latches = mutableListOf<CountDownLatch>()

    private fun setUp(
        expiresAt: Instant = now.plusSeconds(20 * 86_400L),
        token: String? = "old",
        profileId: Long? = 5,
        store: (SessionStore) -> SessionStore = { it },
    ) {
        t = TestServer(initialToken = token, profileId = profileId, expiresAt = expiresAt)
        guard = SessionGuard(store(t.store), { id -> apiCall { t.clients.api.createSession(id) } }, { events += it }, clock)
    }

    @AfterEach
    fun tearDown() {
        // Never leave MockWebServer's dispatch thread blocked: shutdown() would hang.
        latches.forEach { it.countDown() }
        t.shutdown()
    }

    /**
     * A route held on MockWebServer's (blocking) dispatch thread until [open]; [arrived] completes
     * when the request is in, so the test can change the store while the answer is pending.
     */
    private inner class Gate {
        val arrived = CompletableDeferred<Unit>()
        private val latch = CountDownLatch(1).also { latches += it }
        fun open() = latch.countDown()
        fun hold(respond: (RecordedRequest) -> MockResponse): (RecordedRequest) -> MockResponse = { r ->
            arrived.complete(Unit)
            latch.await(10, TimeUnit.SECONDS)
            respond(r)
        }
    }

    private fun gate(method: String, path: String, respond: (RecordedRequest) -> MockResponse) = Gate().also { t.route(method, path, it.hold(respond)) }

    private val sessionPath = "/api/profiles/5/session"
    private val signedOut get() = Session(serverUrl = t.url)
    private val t1 = Instant.parse("2026-11-30T00:00:00Z")

    /** Delegates every member to [inner]; [onRenewedSave] replaces `saveRenewedSession`. */
    private class DelegatingStore(
        private val inner: SessionStore,
        private val onRenewedSave: suspend (Long, String, Instant) -> Boolean,
    ) : SessionStore {
        override val session get() = inner.session
        override suspend fun setServerUrl(url: String) = inner.setServerUrl(url)
        override suspend fun saveSession(profileId: Long, token: String, expiresAt: Instant) = inner.saveSession(profileId, token, expiresAt)
        override suspend fun signOut() = inner.signOut()
        override suspend fun saveRenewedSession(profileId: Long, token: String, expiresAt: Instant) = onRenewedSave(profileId, token, expiresAt)
        override suspend fun clearToken(profileId: Long) = inner.clearToken(profileId)
    }

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
        assertTrue(guard.renew("old", 5) is RenewOutcome.Unreachable)
        t.route("POST", "/api/profiles/5/session") { t.error(404, "profile_not_found") }
        assertEquals(RenewOutcome.ProfileGone, guard.renew(t.store.session.value.token, 5))
        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }
        assertEquals(RenewOutcome.Renewed, guard.renew(null, 5))
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

    // ---- FR-5: nothing renews a signed-out session ------------------------------------------

    @Test
    fun `a 401 without a token is rethrown with no renewal and no event`() = runTest {
        setUp(token = null)
        t.route("GET", "/api/home?limit=20") { t.error(401, "unauthorized") }
        t.route("POST", sessionPath) { t.json(t.sessionJson("new")) }

        for (origin in Origin.entries) assertThrows<ApiException.Unauthorized> { loadHome(origin) }

        assertEquals(0, t.count("POST", sessionPath))
        assertEquals(2, t.count("GET", "/api/home?limit=20"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a 401 after a sign-out is not renewed`() = runTest {
        setUp()
        val home = gate("GET", "/api/home?limit=20") { t.error(401, "unauthorized") }
        t.route("POST", sessionPath) { t.json(t.sessionJson("new")) }

        val call = async { runCatching { loadHome() } }
        home.arrived.await()
        t.store.signOut()
        home.open()

        assertTrue(call.await().exceptionOrNull() is ApiException.Unauthorized)
        assertEquals(0, t.count("POST", sessionPath))
        assertEquals(signedOut, t.store.session.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a renewal answered after a sign-out stores nothing`() = runTest {
        setUp()
        routeHomeByToken()
        val session = gate("POST", sessionPath) { t.json(t.sessionJson("new")) }

        val call = async { runCatching { loadHome() } }
        session.arrived.await()
        t.store.signOut()
        session.open()

        assertTrue(call.await().exceptionOrNull() is ApiException.Unauthorized)
        assertEquals(signedOut, t.store.session.value)
        assertEquals(1, t.count("GET", "/api/home?limit=20"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a renewal by ensureFresh answered after a sign-out stores nothing`() = runTest {
        setUp(expiresAt = now.plusSeconds(23 * 3600L))
        val session = gate("POST", sessionPath) { t.json(t.sessionJson("new")) }

        val fresh = async { guard.ensureFresh() }
        session.arrived.await()
        t.store.signOut()
        session.open()

        assertNull(fresh.await())
        assertEquals(signedOut, t.store.session.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `ensureFresh waiting on the mutex at sign-out emits nothing`() = runTest {
        setUp(expiresAt = now.plusSeconds(23 * 3600L))
        routeHomeByToken()
        val session = gate("POST", sessionPath) { t.json(t.sessionJson("new")) }

        val call = async { runCatching { loadHome() } }
        session.arrived.await()
        val fresh = async(start = CoroutineStart.UNDISPATCHED) { guard.ensureFresh() }
        t.store.signOut()
        session.open()

        assertTrue(call.await().exceptionOrNull() is ApiException.Unauthorized)
        assertNull(fresh.await())
        assertEquals(1, t.count("POST", sessionPath))
        assertEquals(signedOut, t.store.session.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a renewal answered after a profile switch keeps the new profile and does not retry`() = runTest {
        setUp()
        routeHomeByToken()
        val session = gate("POST", sessionPath) { t.json(t.sessionJson("new")) }

        val call = async { runCatching { loadHome() } }
        session.arrived.await()
        t.store.saveSession(7, "tok7", t1)
        session.open()

        assertTrue(call.await().exceptionOrNull() is ApiException.Unauthorized)
        assertEquals(Session(t.url, 7, "tok7", t1), t.store.session.value)
        assertEquals(1, t.count("GET", "/api/home?limit=20"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `renew of a session replaced by another profile is NoSession`() = runTest {
        setUp()
        val session = gate("POST", sessionPath) { t.json(t.sessionJson("new")) }

        val renewal = async { guard.renew("old", 5) }
        session.arrived.await()
        t.store.saveSession(7, "tok7", t1)
        session.open()

        assertEquals(RenewOutcome.NoSession, renewal.await())
        assertEquals(Session(t.url, 7, "tok7", t1), t.store.session.value)
    }

    @Test
    fun `a 401 answered after a profile switch is not renewed nor retried`() = runTest {
        setUp()
        val home = gate("GET", "/api/home?limit=20") { r ->
            if (r.getHeader("Authorization") == "Bearer old") t.error(401, "unauthorized") else t.json(Fixtures.home)
        }
        t.route("POST", sessionPath) { t.json(t.sessionJson("new")) }

        val call = async { runCatching { loadHome() } }
        home.arrived.await()
        t.store.saveSession(7, "tok7", t1)
        home.open()

        assertTrue(call.await().exceptionOrNull() is ApiException.Unauthorized)
        assertEquals(0, t.count("POST", sessionPath))
        assertEquals(1, t.count("GET", "/api/home?limit=20"))
        assertEquals(Session(t.url, 7, "tok7", t1), t.store.session.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a 401 after the same profile was renewed retries with the new token`() = runTest {
        setUp()
        val home = gate("GET", "/api/home?limit=20") { r ->
            if (r.getHeader("Authorization") == "Bearer old") t.error(401, "unauthorized") else t.json(Fixtures.home)
        }
        t.route("POST", sessionPath) { t.json(t.sessionJson("new")) }

        val call = async { runCatching { loadHome() } }
        home.arrived.await()
        t.store.saveSession(5, "renewed", t1)
        home.open()

        assertEquals(1234, call.await().getOrThrow().library.films)
        assertEquals(0, t.count("POST", sessionPath))
        assertEquals(2, t.count("GET", "/api/home?limit=20"))
        assertEquals("Bearer renewed", t.requests.last().getHeader("Authorization"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `renew without arguments renews the stored session`() = runTest {
        setUp()
        t.route("POST", sessionPath) { t.json(t.sessionJson("new")) }

        assertEquals(RenewOutcome.Renewed, guard.renew())

        assertEquals("new", t.store.session.value.token)
        assertEquals(5L, t.store.session.value.profileId)
        assertEquals(1, t.requests.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `renew with no remembered profile is NoSession and leaves the store`() = runTest {
        setUp(profileId = null)
        val before = t.store.session.value

        assertEquals(RenewOutcome.NoSession, guard.renew())

        assertEquals(before, t.store.session.value)
        assertTrue(t.requests.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a refused save is NoSession and the call is not retried`() = runTest {
        setUp(expiresAt = now.plusSeconds(23 * 3600L), store = { DelegatingStore(it) { _, _, _ -> false } })
        routeHomeByToken()
        t.route("POST", sessionPath) { t.json(t.sessionJson("new")) }

        assertThrows<ApiException.Unauthorized> { loadHome() }
        assertEquals(1, t.count("GET", "/api/home?limit=20"))
        assertNull(guard.ensureFresh())

        assertEquals(2, t.count("POST", sessionPath))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a 404 answered after a sign-out clears nothing and emits nothing`() = runTest {
        setUp(expiresAt = now.plusSeconds(23 * 3600L))
        routeHomeByToken()
        val session = gate("POST", sessionPath) { t.error(404, "profile_not_found") }

        val call = async { runCatching { loadHome() } }
        session.arrived.await()
        t.store.signOut()
        session.open()

        assertTrue(call.await().exceptionOrNull() is ApiException.Unauthorized)
        assertEquals(signedOut, t.store.session.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a 404 answered to ensureFresh after a sign-out emits nothing`() = runTest {
        setUp(expiresAt = now.plusSeconds(23 * 3600L))
        val session = gate("POST", sessionPath) { t.error(404, "profile_not_found") }

        val fresh = async { guard.ensureFresh() }
        session.arrived.await()
        t.store.signOut()
        session.open()

        assertNull(fresh.await())
        assertEquals(signedOut, t.store.session.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a 404 answered after a profile switch keeps the new token`() = runTest {
        setUp(expiresAt = now.plusSeconds(23 * 3600L))
        routeHomeByToken()
        val session = gate("POST", sessionPath) { t.error(404, "profile_not_found") }

        val call = async { runCatching { loadHome() } }
        session.arrived.await()
        t.store.saveSession(7, "tok7", t1)
        session.open()
        assertTrue(call.await().exceptionOrNull() is ApiException.Unauthorized)
        assertEquals(Session(t.url, 7, "tok7", t1), t.store.session.value)

        t.store.saveSession(5, "old", now.plusSeconds(23 * 3600L))
        val second = gate("POST", sessionPath) { t.error(404, "profile_not_found") }
        val fresh = async { guard.ensureFresh() }
        second.arrived.await()
        t.store.saveSession(7, "tok7", t1)
        second.open()
        assertNull(fresh.await())
        assertEquals(Session(t.url, 7, "tok7", t1), t.store.session.value)
        assertTrue(events.isEmpty())
    }

    /** AC-5.11: the save applies, then another profile is stored before `withRenewal` retries. */
    private suspend fun switchBeforeTheRetry(origin: Origin) {
        var inner: SessionStore? = null
        setUp(store = { s ->
            inner = s
            DelegatingStore(s) { id, token, at -> s.saveRenewedSession(id, token, at).also { s.saveSession(7, "tok7", t1) } }
        })
        routeHomeByToken()
        t.route("POST", sessionPath) { t.json(t.sessionJson("new")) }

        assertThrows<ApiException.Unauthorized> { loadHome(origin) }

        assertEquals(1, t.count("GET", "/api/home?limit=20"))
        assertEquals(1, t.count("POST", sessionPath))
        assertEquals(Session(t.url, 7, "tok7", t1), inner!!.session.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a profile switch between a successful renewal and the retry is not retried in the foreground`() = runTest {
        switchBeforeTheRetry(Origin.Foreground)
    }

    @Test
    fun `a profile switch between a successful renewal and the retry is not retried in the background`() = runTest {
        switchBeforeTheRetry(Origin.Background)
    }
}
