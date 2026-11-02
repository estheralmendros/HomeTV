package com.hometv.tv.di

import com.hometv.tv.core.api.HomeResponse
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.data.Session
import com.hometv.tv.core.playback.F
import com.hometv.tv.data.HomePayload
import com.hometv.tv.testing.ScriptedApi
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.testing.testContainer
import com.hometv.tv.ui.navigation.Route
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppContainerTest {
    /** Virtual time: the appScope and the Home cache I/O run on the test scheduler (no real sleeps). */
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val api = ScriptedApi()
    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `the start destination follows the stored session`() {
        assertEquals(Route.Setup(initial = true), testContainer(api, scope, Session()).startDestination())
        assertEquals(Route.Profiles(), testContainer(api, scope, Session(serverUrl = TestRepos.SERVER, profileId = 5)).startDestination())
        assertEquals(Route.Home, testContainer(api, scope).startDestination())
    }

    @Test
    fun `an expired token is silently replaced at startup for the remembered profile`() = runTest(dispatcher) {
        val expired = Session(serverUrl = TestRepos.SERVER, profileId = 5, token = "old", expiresAt = now.minusSeconds(60))
        val container = testContainer(api, scope, expired, clock)
        assertEquals(Route.Home, container.startDestination())
        container.onActivityStart().join()
        assertEquals(listOf("POST /api/profiles/5/session"), api.calls)
        assertEquals("tok-5", container.store.session.value.token)
    }

    @Test
    fun `ON_START renews only within 24 h of expiry`() = runTest(dispatcher) {
        val fresh = testContainer(api, scope, Session(TestRepos.SERVER, 5, "tok", now.plusSeconds(3 * 86_400)), clock)
        fresh.onActivityStart().join()
        assertEquals(emptyList<String>(), api.calls)
        val soon = testContainer(api, scope, Session(TestRepos.SERVER, 5, "tok", now.plusSeconds(3_600)), clock)
        soon.onActivityStart().join()
        assertEquals(listOf("POST /api/profiles/5/session"), api.calls)
    }

    @Test
    fun `API clients are rebuilt when the server URL changes`() = runTest(dispatcher) {
        val container = testContainer(api, scope)
        val first = container.clients()
        assertSame(first, container.clients())
        container.store.setServerUrl("https://other.ts.net")
        assertNotSame(first, container.clients())
        assertEquals("https://other.ts.net", container.clients().serverUrl)
    }

    @Test
    fun `ItemGone also drops the card from the Home cache`() = runTest(dispatcher) {
        val container = testContainer(api, scope, cacheIo = dispatcher)
        container.homeCache.write(5, HomePayload(HomeResponse(), films = listOf(com.hometv.tv.core.api.FilmSummary(12, "Dune"))))
        container.events.emit(AppEvent.ItemGone("film", 12))
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), container.homeCache.read(5)!!.films.map { it.id })
    }

    @Test
    fun `AuthExpired is latched until the UI consumes it`() = runTest(dispatcher) {
        val container = testContainer(api, scope)
        container.events.emit(AppEvent.AuthExpired)
        advanceUntilIdle()
        assertTrue(container.sessionExpired.value)
        container.consumeSessionExpired()
        assertTrue(!container.sessionExpired.value)
    }

    @Test
    fun `artwork paths resolve against the server URL with no token in the URL`() {
        val container = testContainer(api, scope)
        assertEquals("https://hometv.tail1234.ts.net/api/artwork/w342/p.jpg", container.artworkUrl("/api/artwork/w342/p.jpg"))
        assertNull(container.artworkUrl(null))
        assertTrue("token" !in container.artworkUrl("/api/artwork/w342/p.jpg")!!)
    }

    @Test
    fun `caps come from the codec source, refined per file`() = runTest(dispatcher) {
        val container = testContainer(api, scope)
        val caps = container.capsForFile()
        assertTrue("hevc" in caps.capsFor(F.file(1, video = F.H264)).video_codecs)
        assertTrue("hevc" !in caps.capsFor(F.hevc(2)).video_codecs, "4K HEVC on a 1080p decoder")
    }

    @Test
    fun `player launches are registered in memory and unknown ids are not active`() {
        val container = testContainer(api, scope)
        val id = container.playerLaunches.register(null)
        assertTrue(container.playerLaunches.isActive(id))
        assertTrue(!container.playerLaunches.isActive("restored-after-process-death"))
        container.playerLaunches.finish(id)
        assertTrue(!container.playerLaunches.isActive(id))
    }

    /** A rating saved anywhere makes Home reload its card badges on the next visit. */
    @Test
    fun `a rating change marks Home stale`() = runTest(dispatcher) {
        val container = testContainer(api, scope)
        advanceUntilIdle()
        container.homeRefresh.consumeStale()
        container.ratings.set(com.hometv.tv.core.data.RatingKind.FILM, 12, com.hometv.tv.core.util.Stars(8))
        advanceUntilIdle()
        assertTrue(container.homeRefresh.consumeStale())
    }
}
