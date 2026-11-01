package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.ActivityEntry
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.api.RatingRequest
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.api.apiCall
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.util.Stars
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** ratings prd3 FR-T4: rating writes in `appScope`, one per title in flight, `changes` on success. */
class RatingsRepositoryTest {
    /**
     * The wire contract against a MockWebServer (real Retrofit, real guard). `appScope` is the test's
     * `backgroundScope`: fire-and-forget dismissals are awaited as its children, never with real sleeps.
     */
    @Nested
    inner class Wire {
        private val t = TestServer()
        private val events = mutableListOf<AppEvent>()
        private val guard = SessionGuard(t.store, { id -> apiCall { t.clients.api.createSession(id) } }, { events += it })

        private fun TestScope.repo(log: (Throwable) -> Unit = {}) = RatingsRepository({ t.clients.api }, guard, backgroundScope, log)

        /** The launched dismissals (the only `appScope` children here) have finished. */
        private suspend fun TestScope.awaitAppScope() = backgroundScope.coroutineContext.job.children.toList().joinAll()

        @AfterEach
        fun tearDown() = t.shutdown()

        private fun body(index: Int) = Json.parseToJsonElement(t.requests[index].body.readUtf8())

        @Test
        fun `set, remove and dismiss use the kind's segment`() = runTest {
            t.route("PUT", "/api/ratings/films/12") { MockResponse204() }
            t.route("DELETE", "/api/ratings/shows/7") { MockResponse204() }
            t.route("POST", "/api/ratings/shows/7/dismiss-prompt") { MockResponse204() }
            val repo = repo()
            repo.set(RatingKind.FILM, 12, Stars(9))
            assertEquals(Json.parseToJsonElement("""{"stars":4.5}"""), body(0))
            repo.remove(RatingKind.SHOW, 7)
            repo.dismiss(RatingKind.SHOW, 7)
            awaitAppScope()
            assertEquals(listOf("PUT /api/ratings/films/12", "DELETE /api/ratings/shows/7", "POST /api/ratings/shows/7/dismiss-prompt"), t.paths())
            assertEquals(0L, t.requests[2].bodySize)
        }

        @Test
        fun `a 401 renews once and retries`() = runTest {
            var first = true
            t.route("PUT", "/api/ratings/films/12") { if (first) { first = false; t.error(401, "unauthorized") } else MockResponse204() }
            t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }
            val repo = repo()
            repo.set(RatingKind.FILM, 12, Stars(8))
            assertEquals(listOf("PUT /api/ratings/films/12", "POST /api/profiles/5/session", "PUT /api/ratings/films/12"), t.paths())
            assertEquals("Bearer new", t.requests[2].getHeader("Authorization"))
        }

        @Test
        fun `a write failure reaches the awaiting caller`() = runTest {
            t.route("PUT", "/api/ratings/films/12") { t.error(404, "not_found") }
            val repo = repo()
            val e = assertThrows<ApiException.Http> { repo.set(RatingKind.FILM, 12, Stars(8)) }
            assertEquals(404, e.status)
            assertFalse(repo.isWriting(RatingKind.FILM, 12))
        }

        @Test
        fun `a failed dismissal is logged and swallowed`() = runTest {
            t.route("POST", "/api/ratings/films/12/dismiss-prompt") { t.error(500, "internal") }
            val logged = CompletableDeferred<Throwable>()
            repo { logged.complete(it) }.dismiss(RatingKind.FILM, 12)
            assertTrue(logged.await() is ApiException.Http)
            awaitAppScope()
            assertEquals(listOf("POST /api/ratings/films/12/dismiss-prompt"), t.paths())
            assertTrue(events.isEmpty())
        }

        @Test
        fun `activity sends page and page_size and drops entries that are not films or shows`() = runTest {
            t.route("GET", "/api/activity?page=2&page_size=50") {
                t.json(
                    """{"items": [
                        {"profile": {"id": 2, "name": "Luis", "avatar_id": "avatar-02"}, "kind": "film", "item": {"type": "film", "id": 12, "title": "Dune"}, "stars": 2.0, "rated_at": "2026-10-31T10:00:00Z"},
                        {"profile": {"id": 2, "name": "Luis", "avatar_id": "avatar-02"}, "kind": "game", "item": {"type": "game", "id": 1}, "stars": 2.0, "rated_at": "2026-10-31T10:00:00Z"},
                        {"profile": {"id": 1, "name": "Ana", "avatar_id": "avatar-01"}, "kind": "show", "item": {"type": "show", "id": 7, "title": "The Office"}, "stars": 4.5, "rated_at": "2026-10-30T10:00:00Z"}
                       ], "page": 2, "page_size": 50, "total": 53}""",
                )
            }
            val page = repo().activity(2)
            assertEquals(listOf("film:12", "show:7"), page.items.map { it.item.key })
            assertTrue(page.items[0].item is FilmSummary && page.items[1].item is ShowSummary)
            assertEquals(53, page.total)
        }

        @Suppress("TestFunctionName")
        private fun MockResponse204() = okhttp3.mockwebserver.MockResponse().setResponseCode(204)
    }

    /** In-flight rules with a gated fake API and virtual time. */
    @Nested
    inner class InFlight {
        private inner class Api : FakeHomeTvApi() {
            val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
            val gates: MutableList<CompletableDeferred<Unit>> = Collections.synchronizedList(mutableListOf())
            var gated = false
            var failure: Exception? = null

            override suspend fun setRating(segment: String, id: Long, body: RatingRequest) = answer("PUT /api/ratings/$segment/$id ${body.stars}")

            override suspend fun removeRating(segment: String, id: Long) = answer("DELETE /api/ratings/$segment/$id")

            override suspend fun dismissRatingPrompt(segment: String, id: Long) = answer("POST /api/ratings/$segment/$id/dismiss-prompt")

            override suspend fun activity(page: Int, pageSize: Int): Page<ActivityEntry> = Page(emptyList(), page, pageSize, 0)

            private suspend fun answer(call: String) {
                calls += call
                if (gated) CompletableDeferred<Unit>().also { gates += it }.await()
                failure?.let { throw it }
            }
        }

        private val api = Api()
        private val store = InMemorySessionStore(Session("https://hometv.ts.net", 5, "tok", Instant.parse("2026-12-01T00:00:00Z")))
        private val guard = SessionGuard(store, { api.createSession(it) }, {})

        private fun TestScope.repo() = RatingsRepository({ api }, guard, backgroundScope)

        @Test
        fun `writing holds the key while the request is in flight`() = runTest {
            api.gated = true
            val repo = repo()
            val call = async { repo.set(RatingKind.FILM, 12, Stars(9)) }
            runCurrent()
            assertTrue(repo.isWriting(RatingKind.FILM, 12))
            assertFalse(repo.isWriting(RatingKind.SHOW, 12))
            assertEquals(setOf(RatingKey(RatingKind.FILM, 12)), repo.writing.value)
            api.gates[0].complete(Unit)
            call.await()
            assertFalse(repo.isWriting(RatingKind.FILM, 12))
            assertEquals(emptySet<RatingKey>(), repo.writing.value)
        }

        @Test
        fun `changes are emitted only after a successful set or remove`() = runTest(UnconfinedTestDispatcher()) {
            val repo = repo()
            val seen = mutableListOf<RatingChange>()
            backgroundScope.launch { repo.changes.toList(seen) }
            repo.set(RatingKind.FILM, 12, Stars(9))
            repo.remove(RatingKind.SHOW, 7)
            api.failure = httpError(500, "internal")
            assertThrows<ApiException.Http> { repo.set(RatingKind.FILM, 13, Stars(2)) }
            assertThrows<ApiException.Http> { repo.remove(RatingKind.FILM, 13) }
            repo.dismiss(RatingKind.FILM, 14)
            runCurrent()
            assertEquals(listOf(RatingChange(RatingKind.FILM, 12, Stars(9)), RatingChange(RatingKind.SHOW, 7, null)), seen)
        }

        @Test
        fun `a write survives the caller's cancellation`() = runTest {
            api.gated = true
            val repo = repo()
            val seen = mutableListOf<RatingChange>()
            backgroundScope.launch { repo.changes.collect { seen += it } }
            val caller = launch { repo.remove(RatingKind.FILM, 12) }
            runCurrent()
            caller.cancel()
            runCurrent()
            assertTrue(repo.isWriting(RatingKind.FILM, 12))
            api.gates[0].complete(Unit)
            runCurrent()
            assertEquals(listOf("DELETE /api/ratings/films/12"), api.calls)
            assertFalse(repo.isWriting(RatingKind.FILM, 12))
            assertEquals(listOf(RatingChange(RatingKind.FILM, 12, null)), seen)
        }

        @Test
        fun `a second write for the same title joins the one in flight`() = runTest {
            api.gated = true
            val repo = repo()
            val first = async { repo.set(RatingKind.FILM, 12, Stars(9)) }
            runCurrent()
            val second = async { repo.set(RatingKind.FILM, 12, Stars(3)) }
            val other = async { repo.remove(RatingKind.FILM, 12) }
            runCurrent()
            assertEquals(listOf("PUT /api/ratings/films/12 4.5"), api.calls)
            api.gates[0].complete(Unit)
            first.await()
            second.await()
            other.await()
            assertEquals(1, api.calls.size)
        }

        @Test
        fun `a joined write gets the first one's failure`() = runTest {
            api.gated = true
            api.failure = httpError(404, "not_found")
            val repo = repo()
            val first = async { runCatching { repo.set(RatingKind.FILM, 12, Stars(9)) } }
            runCurrent()
            val second = async { runCatching { repo.set(RatingKind.FILM, 12, Stars(9)) } }
            runCurrent()
            api.gates[0].complete(Unit)
            val e1 = first.await().exceptionOrNull()
            val e2 = second.await().exceptionOrNull()
            assertTrue(e1 is ApiException.Http)
            assertSame(e1, e2)
            assertEquals(1, api.calls.size)
        }

        @Test
        fun `dismiss runs in the app scope and never throws`() = runTest {
            api.failure = httpError(500, "internal")
            val repo = repo()
            repo.dismiss(RatingKind.SHOW, 7)
            runCurrent()
            assertEquals(listOf("POST /api/ratings/shows/7/dismiss-prompt"), api.calls)
        }

        @Test
        fun `kinds map their wire values and segments`() {
            assertEquals(RatingKind.FILM, RatingKind.fromWire("film"))
            assertEquals(RatingKind.SHOW, RatingKind.fromWire("show"))
            assertEquals(null, RatingKind.fromWire("episode"))
            assertEquals("films", RatingKind.FILM.segment)
            assertEquals("shows", RatingKind.SHOW.segment)
        }
    }
}
