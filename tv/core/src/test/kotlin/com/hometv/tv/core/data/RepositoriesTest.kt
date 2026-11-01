package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.Fixtures
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.ProgressRequest
import com.hometv.tv.core.api.RatingPrompt
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.api.apiCall
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Request paths, queries and bodies match prd.md for every endpoint the TV uses. */
class RepositoriesTest {
    private val t = TestServer()
    private val events = mutableListOf<AppEvent>()
    private val guard = SessionGuard(t.store, { id -> apiCall { t.clients.api.createSession(id) } }, { events += it })
    private val profiles = ProfileRepository({ t.clients.api }, t.store, guard)
    private val catalog = CatalogRepository({ t.clients.api }, guard)
    private val watch = WatchRepository({ t.clients.api }, guard)

    @AfterEach
    fun tearDown() = t.shutdown()

    private fun body(index: Int = 0): JsonObject = Json.parseToJsonElement(t.requests[index].body.readUtf8()).jsonObject

    @Test
    fun `profiles list and info`() = runTest {
        t.route("GET", "/api/profiles") {
            t.json("""{"items": [{"id": 1, "name": "Esther", "avatar_id": "avatar-05", "is_admin": true, "created_at": "x"}, {"id": 2, "name": "Invitado", "avatar_id": "avatar-01", "is_admin": false}]}""")
        }
        t.route("GET", "/api/info") {
            t.json("""{"name": "HomeTV", "version": "0.1.0", "needs_bootstrap": false, "tmdb_configured": true, "tmdb_attribution": "Este producto utiliza la API de TMDB…", "avatars": [{"id": "avatar-01", "color": "#E50914"}]}""")
        }
        assertEquals(listOf("Esther", "Invitado"), profiles.list().map { it.name })
        val info = profiles.info()
        assertEquals("#E50914", info.avatars.single().color)
        assertEquals("Este producto utiliza la API de TMDB…", info.tmdb_attribution)
    }

    @Test
    fun `createSession sends no body and stores profile, token and expiry`() = runTest {
        t.route("POST", "/api/profiles/2/session") { t.json(t.sessionJson("tok-2", "2026-11-21T10:00:00Z", id = 2)) }
        profiles.createSession(2)
        assertEquals(0L, t.requests.single().bodySize)
        assertEquals(Session(t.url, 2, "tok-2", Instant.parse("2026-11-21T10:00:00Z")), t.store.session.value)
    }

    @Test
    fun `createSession 404 profile_not_found is an Http error and keeps the stored session`() = runTest {
        t.route("POST", "/api/profiles/9/session") { t.error(404, "profile_not_found") }
        val e = assertThrows<ApiException.Http> { profiles.createSession(9) }
        assertEquals("profile_not_found", e.code)
        assertEquals("old", t.store.session.value.token)
    }

    @Test
    fun `me renews on a 401`() = runTest {
        t.route("GET", "/api/me") { r ->
            if (r.getHeader("Authorization") == "Bearer old") t.error(401, "unauthorized")
            else t.json("""{"id": 5, "name": "Esther", "avatar_id": "avatar-05", "is_admin": true}""")
        }
        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }
        assertEquals("Esther", profiles.me().name)
    }

    @Test
    fun `verifyAdminPin posts the PIN with the profile token and discards the admin token`() = runTest {
        t.route("POST", "/api/admin/unlock") { t.json("""{"admin_token": "adm", "expires_at": "2026-10-23T18:00:00Z"}""") }
        assertEquals(PinCheck.Ok, profiles.verifyAdminPin("1234"))
        val request = t.requests.single()
        assertEquals("Bearer old", request.getHeader("Authorization"))
        assertEquals("1234", body()["pin"].toString().trim('"'))
        assertEquals("old", t.store.session.value.token)
    }

    @Test
    fun `verifyAdminPin 401 invalid_pin is a wrong PIN, never a session renewal`() = runTest {
        t.route("POST", "/api/admin/unlock") { t.error(401, "invalid_pin") }
        assertEquals(PinCheck.Wrong, profiles.verifyAdminPin("0000"))
        assertEquals(listOf("/api/admin/unlock"), t.requests.map { it.path })
    }

    @Test
    fun `verifyAdminPin renews an expired profile token once and retries the check`() = runTest {
        t.route("POST", "/api/admin/unlock") { r ->
            if (r.getHeader("Authorization") == "Bearer old") t.error(401, "unauthorized")
            else t.json("""{"admin_token": "adm", "expires_at": "2026-10-23T18:00:00Z"}""")
        }
        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }
        assertEquals(PinCheck.Ok, profiles.verifyAdminPin("1234"))
        assertEquals(listOf("/api/admin/unlock", "/api/profiles/5/session", "/api/admin/unlock"), t.requests.map { it.path })
        assertEquals("Bearer new", t.requests.last().getHeader("Authorization"))
        assertEquals("new", t.store.session.value.token)
    }

    @Test
    fun `verifyAdminPin after a renewal still maps invalid_pin to a wrong PIN`() = runTest {
        t.route("POST", "/api/admin/unlock") { r ->
            if (r.getHeader("Authorization") == "Bearer old") t.error(401, "unauthorized") else t.error(401, "invalid_pin")
        }
        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }
        assertEquals(PinCheck.Wrong, profiles.verifyAdminPin("0000"))
        assertEquals(1, t.requests.count { it.path == "/api/profiles/5/session" })
    }

    @Test
    fun `verifyAdminPin with a token rejected twice renews only once and fails the check`() = runTest {
        t.route("POST", "/api/admin/unlock") { t.error(401, "unauthorized") }
        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }
        assertEquals(PinCheck.Failed, profiles.verifyAdminPin("1234"))
        assertEquals(listOf("/api/admin/unlock", "/api/profiles/5/session", "/api/admin/unlock"), t.requests.map { it.path })
        assertTrue(events.isEmpty(), "no Connection Error / Picker event for a failed check: $events")
    }

    @Test
    fun `verifyAdminPin 429 is a lockout with its retry delay`() = runTest {
        t.route("POST", "/api/admin/unlock") { t.error(429, "too_many_attempts", """{"retry_after_seconds": 42}""") }
        assertEquals(PinCheck.Locked(42), profiles.verifyAdminPin("0000"))
    }

    @Test
    fun `verifyAdminPin maps any other answer to Failed`() = runTest {
        t.route("POST", "/api/admin/unlock") { t.error(403, "forbidden") }
        assertEquals(PinCheck.Failed, profiles.verifyAdminPin("1234"))
    }

    @Test
    fun `home drops summaries of unknown types`() = runTest {
        t.route("GET", "/api/home?limit=20") { t.json(Fixtures.home) }
        val home = catalog.home()
        assertEquals(listOf("show:7"), home.recently_added.map { it.key })
        assertEquals(listOf("film:12", "episode:345"), home.continue_watching.map { it.key })
    }

    @Test
    fun `films and shows pages map the sort option to sort and order`() = runTest {
        val page = """{"items": [], "page": 1, "page_size": 20, "total": 0}"""
        t.route("GET", "/api/films?page=1&page_size=20&sort=added&order=desc") { t.json(page) }
        t.route("GET", "/api/films?page=3&page_size=50&sort=title&order=asc") {
            t.json("""{"items": [{"type": "film", "id": 1, "title": "Dune"}], "page": 3, "page_size": 50, "total": 101}""")
        }
        t.route("GET", "/api/shows?page=2&page_size=50&sort=year&order=asc") { t.json(page) }
        catalog.films(page = 1, pageSize = 20, sort = BrowseSort.ADDED_DESC)
        val films = catalog.films(page = 3)
        assertEquals(101, films.total)
        assertEquals("Dune", films.items.single().title)
        catalog.shows(page = 2, sort = BrowseSort.YEAR_ASC)
        assertTrue(t.requests.all { it.getHeader("Authorization") == "Bearer old" })
        assertEquals(3, t.requests.size)
    }

    @Test
    fun `browse sort options are listed in the PRD order`() {
        assertEquals(
            listOf("title" to "asc", "title" to "desc", "year" to "desc", "year" to "asc", "added" to "desc"),
            BrowseSort.entries.map { it.sort to it.order },
        )
    }

    @Test
    fun `browse filters add genre, year bounds and watched, and are omitted when not set`() = runTest {
        val page = """{"items": [], "page": 1, "page_size": 50, "total": 0}"""
        t.route("GET", "/api/shows?page=1&page_size=50&sort=title&order=asc&genre=35&watched=false") { t.json(page) }
        t.route("GET", "/api/films?page=1&page_size=50&sort=title&order=asc&genre=18&year_from=1990&year_to=1999") { t.json(page) }
        t.route("GET", "/api/shows?page=1&page_size=50&sort=title&order=asc&year_to=1949") { t.json(page) }
        t.route("GET", "/api/films?page=1&page_size=50&sort=title&order=asc") { t.json(page) }
        catalog.films(filter = BrowseFilter(genreId = 18, yearFrom = 1990, yearTo = 1999))
        catalog.shows(filter = BrowseFilter(yearTo = 1949))
        catalog.films(filter = BrowseFilter())
        catalog.shows(filter = BrowseFilter(genreId = 35, watched = false))
        assertEquals(4, t.requests.size)
    }

    @Test
    fun `genres of a library, by name`() = runTest {
        t.route("GET", "/api/genres?type=shows") { t.json("""{"items": [{"id": 18, "name": "Drama", "count": 3}, {"id": 35, "name": "Comedia", "count": 1}]}""") }
        assertEquals(listOf(18L to "Drama", 35L to "Comedia"), catalog.genres("shows").map { it.id to it.name })
    }

    @Test
    fun `details, season and episode paths`() = runTest {
        t.route("GET", "/api/films/12") { t.json("""{"id": 12, "title": "Dune", "media_files": [${Fixtures.mediaFileJson(91, 1)}], "progress": ${Fixtures.progressJson}}""") }
        t.route("GET", "/api/shows/7") { t.json("""{"id": 7, "title": "The Office", "status": "Ended", "seasons": [{"id": 70, "season_number": 2, "name": null, "episode_count": 22, "unwatched_episode_count": 10}], "next_episode": null}""") }
        t.route("GET", "/api/shows/7/seasons/2") { t.json("""{"id": 70, "show_id": 7, "show_title": "The Office", "season_number": 2, "episodes": []}""") }
        t.route("GET", "/api/episodes/345") { t.json("""{"id": 345, "show_id": 7, "show_title": "The Office", "season_number": 2, "episode_number": 3, "media_files": [], "previous_episode_id": 344, "next_episode_id": null}""") }
        assertEquals(1, catalog.film(12).media_files.single().part_number)
        assertEquals(null, catalog.show(7).seasons.single().name)
        assertEquals(70L, catalog.season(7, 2).id)
        assertEquals(344L, catalog.episode(345).previous_episode_id)
    }

    @Test
    fun `person calls GET api people id and drops appearances of unknown types`() = runTest {
        t.route("GET", "/api/people/3") {
            t.json(
                """{"person_id": 3, "name": "Timothée Chalamet", "credits_complete": true, "appearances": [
                    {"kind": "film", "item": {"type": "film", "id": 12, "title": "Dune"}, "character": "Paul Atreides"},
                    {"kind": "game", "item": {"type": "game", "id": 1}},
                    {"kind": "show", "item": {"type": "show", "id": 7, "title": "Homeland"}},
                    {"kind": "episode", "item": {"type": "episode", "id": 345, "show_id": 7, "show_title": "Homeland", "season_number": 1, "episode_number": 1}}
                ]}""",
            )
        }
        val person = catalog.person(3)
        assertEquals("Timothée Chalamet", person.name)
        assertEquals(listOf("film:12", "show:7"), person.appearances.map { it.item.key })
        assertEquals(listOf("GET /api/people/3"), t.paths())
        assertEquals("Bearer old", t.requests.single().getHeader("Authorization"))
    }

    @Test
    fun `an unknown person is a 404 not_found`() = runTest {
        t.route("GET", "/api/people/999") { t.error(404, "not_found") }
        assertEquals("not_found", assertThrows<ApiException.Http> { catalog.person(999) }.code)
    }

    @Test
    fun `a removed title is a 404 not_found`() = runTest {
        t.route("GET", "/api/films/13") { t.error(404, "not_found") }
        assertEquals("not_found", assertThrows<ApiException.Http> { catalog.film(13) }.code)
    }

    @Test
    fun `search truncates to 100 characters and a blank query sends nothing`() = runTest {
        val long = "a".repeat(130)
        t.route("GET", "/api/search?q=${"a".repeat(100)}&limit=20") {
            t.json("""{"films": [{"type": "film", "id": 1, "title": "Dune"}], "shows": [{"type": "show", "id": 2, "title": "Dark"}]}""")
        }
        val result = catalog.search(long)
        assertEquals("Dune", (result.films.single() as FilmSummary).title)
        assertEquals("Dark", (result.shows.single() as ShowSummary).title)
        assertEquals(1, t.requests.size)

        assertTrue(catalog.search("   ").films.isEmpty())
        assertEquals(1, t.requests.size)
    }

    @Test
    fun `search encodes the query`() = runTest {
        t.route("GET", "/api/search?q=la%20casa%20%26%20el%20%C3%B1u&limit=20") { t.json("""{"films": [], "shows": []}""") }
        catalog.search("  la casa & el ñu ")
        assertEquals(1, t.requests.size)
        assertEquals("/api/search?q=la%20casa%20%26%20el%20%C3%B1u&limit=20", t.requests.single().path)
    }

    @Test
    fun `progress PUT body follows the contract and omits an unknown duration`() = runTest {
        t.route("PUT", "/api/progress") { t.json(Fixtures.progressJson) }
        watch.putProgress(ProgressRequest("episode", 345, 91, 600.5, 1320.4))
        watch.putProgress(ProgressRequest("film", 12, 91, 10.0, null))
        assertEquals(
            Json.parseToJsonElement("""{"item_type":"episode","item_id":345,"media_file_id":91,"position_seconds":600.5,"duration_seconds":1320.4}"""),
            body(0),
        )
        assertEquals(setOf("item_type", "item_id", "media_file_id", "position_seconds"), body(1).keys)
    }

    @Test
    fun `watched POST body for every target`() = runTest {
        t.route("POST", "/api/watched") { t.json("""{"updated": 22}""") }
        assertEquals(22, watch.setWatched(WatchTarget.SEASON, 70, true).updated)
        watch.setWatched(WatchTarget.SHOW, 7, false)
        watch.setWatched(WatchTarget.FILM, 12, true)
        watch.setWatched(WatchTarget.EPISODE, 345, false)
        assertEquals(Json.parseToJsonElement("""{"target":"season","id":70,"watched":true}"""), body(0))
        assertEquals(listOf("show", "film", "episode"), (1..3).map { body(it)["target"].toString().trim('"') })
    }

    @Test
    fun `watched and progress answers carry the rating prompt`() = runTest {
        val prompt = """{"kind": "show", "id": 7, "title": "The Office", "poster": null}"""
        t.route("POST", "/api/watched") { t.json("""{"updated": 1, "rating_prompt": $prompt}""") }
        t.route("PUT", "/api/progress") { t.json(Fixtures.progressJson.trimEnd('}') + """, "rating_prompt": $prompt}""") }
        val result = watch.setWatched(WatchTarget.EPISODE, 345, true)
        assertEquals(WatchedResult(1, RatingPrompt("show", 7, "The Office")), result)
        val report = watch.putProgress(ProgressRequest("episode", 345, 91, 1300.0, 1320.0))
        assertEquals(RatingPrompt("show", 7, "The Office"), report.rating_prompt)
        assertEquals(1234.5, report.position_seconds)
    }

    @Test
    fun `a background progress PUT whose renewal fails emits nothing`() = runTest {
        t.route("PUT", "/api/progress") { t.error(401, "unauthorized") }
        t.route("POST", "/api/profiles/5/session") { t.networkFailure() }
        assertThrows<ApiException.Unreachable> { watch.putProgress(ProgressRequest("film", 12, 91, 10.0, 100.0)) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a foreground load whose renewal fails emits ServerUnreachable`() = runTest {
        t.route("GET", "/api/films/12") { t.error(401, "unauthorized") }
        t.route("POST", "/api/profiles/5/session") { t.networkFailure() }
        assertThrows<ApiException.Unreachable> { catalog.film(12) }
        assertTrue(events.single() is AppEvent.ServerUnreachable)
    }

    // ---- Mi lista (watchlist prd3 FR-T2/FR-T3) -------------------------------------------

    @Test
    fun `the watchlist list sends type, sort, page and page_size and drops entries of unknown types`() = runTest {
        t.route("GET", "/api/watchlist?type=all&sort=added&page=1&page_size=50") {
            t.json(
                """{"items": [
                    {"kind": "film", "item": {"type": "film", "id": 12, "title": "Dune"}, "added_to_list_at": "2026-10-27T10:00:00Z"},
                    {"kind": "game", "item": {"type": "game", "id": 1}, "added_to_list_at": "2026-10-27T09:00:00Z"},
                    {"kind": "show", "item": {"type": "show", "id": 7, "title": "The Office"}, "added_to_list_at": "2026-10-26T10:00:00Z"}
                ], "page": 1, "page_size": 50, "total": 3}""",
            )
        }
        t.route("GET", "/api/watchlist?type=shows&sort=year&page=1&page_size=150") { t.json("""{"items": [], "page": 1, "page_size": 150, "total": 0}""") }
        val watchlist = WatchlistRepository({ t.clients.api }, guard, backgroundScope)
        val page = watchlist.list(WatchlistType.ALL, WatchlistSort.ADDED, 1)
        assertEquals(listOf("film:12", "show:7"), page.items.map { it.item.key })
        assertEquals(3, page.total)
        assertEquals(0, watchlist.list(WatchlistType.SHOWS, WatchlistSort.YEAR, 1, pageSize = 150).total)
        assertEquals(
            listOf("GET /api/watchlist?type=all&sort=added&page=1&page_size=50", "GET /api/watchlist?type=shows&sort=year&page=1&page_size=150"),
            t.paths(),
        )
        assertTrue(t.requests.all { it.getHeader("Authorization") == "Bearer old" })
    }

    @Test
    fun `the watchlist list sends watched only when set, as true or false`() = runTest {
        val empty = """{"items": [], "page": 1, "page_size": 50, "total": 0}"""
        t.route("GET", "/api/watchlist?type=films&sort=title&page=1&page_size=50&watched=true") { t.json(empty) }
        t.route("GET", "/api/watchlist?type=all&sort=added&page=2&page_size=50&watched=false") { t.json(empty) }
        t.route("GET", "/api/watchlist?type=all&sort=added&page=1&page_size=50") { t.json(empty) }
        val watchlist = WatchlistRepository({ t.clients.api }, guard, backgroundScope)
        watchlist.list(WatchlistType.FILMS, WatchlistSort.TITLE, 1, watched = true)
        watchlist.list(WatchlistType.ALL, WatchlistSort.ADDED, 2, watched = false)
        watchlist.list(WatchlistType.ALL, WatchlistSort.ADDED, 1, watched = null)
        assertEquals(
            listOf(
                "GET /api/watchlist?type=films&sort=title&page=1&page_size=50&watched=true",
                "GET /api/watchlist?type=all&sort=added&page=2&page_size=50&watched=false",
                "GET /api/watchlist?type=all&sort=added&page=1&page_size=50",
            ),
            t.paths(),
        )
    }

    @Test
    fun `the watchlist wire values of kind, type and sort`() {
        assertEquals(listOf("films", "shows"), WatchlistKind.entries.map { it.segment })
        assertEquals(listOf("all", "films", "shows"), WatchlistType.entries.map { it.wire })
        assertEquals(listOf("added", "title", "year"), WatchlistSort.entries.map { it.wire })
    }

    @Test
    fun `a watchlist write is a PUT to add and a DELETE to remove, and a 204 is accepted`() = runTest {
        t.route("PUT", "/api/watchlist/films/12") { MockResponse().setResponseCode(204) }
        t.route("DELETE", "/api/watchlist/shows/4") { MockResponse().setResponseCode(204) }
        val watchlist = WatchlistRepository({ t.clients.api }, guard, backgroundScope)
        assertEquals(true, watchlist.write(WatchlistKind.FILM, 12, true).await())
        assertEquals(false, watchlist.write(WatchlistKind.SHOW, 4, false).await())
        assertEquals(listOf("PUT /api/watchlist/films/12", "DELETE /api/watchlist/shows/4"), t.paths())
        assertEquals(0L, t.requests[0].bodySize)
    }

    @Test
    fun `a watchlist write on a removed title fails with the 404 not_found`() = runTest {
        t.route("PUT", "/api/watchlist/films/13") { t.error(404, "not_found") }
        val watchlist = WatchlistRepository({ t.clients.api }, guard, backgroundScope)
        val e = assertThrows<WatchlistWriteException> { watchlist.write(WatchlistKind.FILM, 13, true).await() }
        val cause = e.cause
        assertTrue(cause is ApiException.Http)
        assertEquals(404, (cause as ApiException.Http).status)
        assertEquals("not_found", cause.code)
        assertEquals(null, e.acknowledged)
    }

    @Test
    fun `watchlist calls renew the session once on a 401`() = runTest {
        t.route("PUT", "/api/watchlist/films/12") { r ->
            if (r.getHeader("Authorization") == "Bearer old") t.error(401, "unauthorized") else MockResponse().setResponseCode(204)
        }
        t.route("GET", "/api/watchlist?type=films&sort=title&page=2&page_size=50") { r ->
            if (r.getHeader("Authorization") == "Bearer new") t.json("""{"items": [], "page": 2, "page_size": 50, "total": 0}""") else t.error(401, "unauthorized")
        }
        t.route("POST", "/api/profiles/5/session") { t.json(t.sessionJson("new")) }
        val watchlist = WatchlistRepository({ t.clients.api }, guard, backgroundScope)
        assertEquals(true, watchlist.write(WatchlistKind.FILM, 12, true).await())
        watchlist.list(WatchlistType.FILMS, WatchlistSort.TITLE, 2)
        assertEquals(
            listOf(
                "PUT /api/watchlist/films/12", "POST /api/profiles/5/session", "PUT /api/watchlist/films/12",
                "GET /api/watchlist?type=films&sort=title&page=2&page_size=50",
            ),
            t.paths(),
        )
    }
}
