package com.hometv.tv.core.data

import com.hometv.tv.core.api.AudioChoice
import com.hometv.tv.core.api.SessionResponse
import com.hometv.tv.core.api.SubtitleChoice
import com.hometv.tv.core.api.TrackChoice
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import java.io.IOException
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Track-choice writes: one PUT per (profile, title) in flight, latest wins, Background renewal, failures logged. */
class TrackChoiceRepositoryTest {
    private class Api : FakeHomeTvApi() {
        val calls: MutableList<Triple<String, Long, TrackChoice>> = Collections.synchronizedList(mutableListOf())
        val tokens: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val gates: MutableList<CompletableDeferred<Unit>> = Collections.synchronizedList(mutableListOf())
        var gated = false
        var tokenOf: () -> String? = { null }
        val failures = mutableMapOf<Int, Exception>()
        var sessions = 0

        override suspend fun putTrackChoice(segment: String, id: Long, body: TrackChoice) {
            calls += Triple(segment, id, body)
            tokens += tokenOf()
            val number = calls.size
            if (gated) CompletableDeferred<Unit>().also { gates += it }.await()
            failures[number]?.let { throw it }
        }

        override suspend fun createSession(profileId: Long): SessionResponse {
            sessions++
            return session("new", profileId)
        }

        fun release(index: Int) = gates[index].complete(Unit)
    }

    private val api = Api()
    private val store = InMemorySessionStore(Session("https://hometv.ts.net", 5, "tok", Instant.parse("2026-12-01T00:00:00Z")))
    private val events = mutableListOf<AppEvent>()
    private val guard = SessionGuard(store, { api.createSession(it) }, { events += it })
    private val logged = mutableListOf<Throwable>()

    init {
        api.tokenOf = { store.session.value.token }
    }

    private fun TestScope.repo(scope: CoroutineScope = backgroundScope) = TrackChoiceRepository({ api }, guard, store, scope) { logged += it }

    private val eng = TrackChoice(AudioChoice("eng", null, 6), null)
    private val spa = TrackChoice(AudioChoice("spa", null, 2), null)
    private val off = TrackChoice(AudioChoice("spa", null, 2), SubtitleChoice("off"))

    @Test
    fun `save PUTs the whole choice to the title's segment and resolves true`() = runTest {
        val repo = repo()
        val done = repo.save(RatingKind.SHOW, 7, off)
        runCurrent()
        assertEquals(listOf(Triple("shows", 7L, off)), api.calls)
        assertTrue(done.await())
        assertTrue(repo.save(RatingKind.FILM, 3, eng).also { runCurrent() }.await())
        assertEquals("films", api.calls.last().first)
    }

    @Test
    fun `quick saves send one PUT at a time, the queued wish replaced by the latest`() = runTest {
        api.gated = true
        val repo = repo()
        val first = repo.save(RatingKind.SHOW, 7, eng)
        runCurrent()
        val second = repo.save(RatingKind.SHOW, 7, spa)
        val third = repo.save(RatingKind.SHOW, 7, off)
        runCurrent()
        assertEquals(1, api.calls.size)
        api.release(0)
        runCurrent()
        assertEquals(listOf(eng, off), api.calls.map { it.third })
        api.release(1)
        runCurrent()
        assertEquals(2, api.calls.size)
        assertTrue(first.await())
        assertTrue(second.await())
        assertTrue(third.await())
        repo.awaitIdle()
    }

    @Test
    fun `two titles write independently`() = runTest {
        api.gated = true
        val repo = repo()
        val show = repo.save(RatingKind.SHOW, 7, eng)
        val film = repo.save(RatingKind.FILM, 7, spa)
        runCurrent()
        assertEquals(setOf(Triple("shows", 7L, eng), Triple("films", 7L, spa)), api.calls.toSet())
        api.release(0)
        api.release(1)
        runCurrent()
        assertTrue(show.await())
        assertTrue(film.await())
        repo.awaitIdle()
    }

    @Test
    fun `a save while signed out resolves false and sends nothing`() = runTest {
        store.signOut()
        val repo = repo()
        val done = repo.save(RatingKind.FILM, 3, eng)
        runCurrent()
        assertFalse(done.await())
        assertTrue(api.calls.isEmpty())
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `a queued wish made under another profile than the stored one is dropped`() = runTest {
        api.gated = true
        val repo = repo()
        val first = repo.save(RatingKind.SHOW, 7, eng)
        runCurrent()
        val queued = repo.save(RatingKind.SHOW, 7, spa)
        store.saveSession(9, "tok-9", Instant.parse("2026-12-01T00:00:00Z"))
        api.release(0)
        runCurrent()
        assertEquals(listOf(eng), api.calls.map { it.third })
        assertTrue(first.await())
        assertFalse(queued.await())
    }

    @Test
    fun `a wish replaced by another profile's wish resolves false and is never sent`() = runTest {
        api.gated = true
        val repo = repo()
        val first = repo.save(RatingKind.SHOW, 7, eng)
        runCurrent()
        val oldProfile = repo.save(RatingKind.SHOW, 7, spa)
        store.saveSession(9, "tok-9", Instant.parse("2026-12-01T00:00:00Z"))
        val newProfile = repo.save(RatingKind.SHOW, 7, off)
        assertTrue(oldProfile.isCompleted)
        assertFalse(oldProfile.await())
        api.release(0)
        runCurrent()
        api.release(1)
        runCurrent()
        assertEquals(listOf(eng, off), api.calls.map { it.third })
        assertTrue(first.await())
        assertTrue(newProfile.await())
        repo.awaitIdle()
    }

    @Test
    fun `a 401 renews once as a background request and retries`() = runTest {
        api.failures[1] = httpError(401, "unauthorized")
        val repo = repo()
        val done = repo.save(RatingKind.FILM, 3, eng)
        runCurrent()
        assertTrue(done.await())
        assertEquals(1, api.sessions)
        assertEquals(listOf("tok", "new"), api.tokens)
    }

    @Test
    fun `a failure is logged and resolves false, never throws, the next wish is still sent`() = runTest {
        api.gated = true
        api.failures[1] = IOException("down")
        val repo = repo()
        val failed = repo.save(RatingKind.FILM, 3, eng)
        runCurrent()
        val next = repo.save(RatingKind.FILM, 3, spa)
        api.release(0)
        runCurrent()
        api.release(1)
        runCurrent()
        assertEquals(listOf(eng, spa), api.calls.map { it.third })
        assertFalse(failed.await())
        assertTrue(next.await())
        assertEquals(1, logged.size)
        // Origin.Background: an unreachable server never shows the Connection Error screen.
        assertTrue(events.none { it is AppEvent.ServerUnreachable })
    }

    @Test
    fun `cancellation of appScope resolves pending saves false, is not logged, and frees the title`() = runTest {
        api.gated = true
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val repo = repo(scope)
        val done = repo.save(RatingKind.FILM, 3, eng)
        runCurrent()
        scope.cancel()
        runCurrent()
        assertTrue(done.isCompleted)
        assertFalse(done.await())
        assertTrue(logged.isEmpty())
        repo.awaitIdle()
    }
}
