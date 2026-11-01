package com.hometv.tv.ui.activity

import com.hometv.tv.core.api.ActivityEntry
import com.hometv.tv.core.api.Avatar
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.Image
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.api.ProfileRef
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.data.RatingKind
import com.hometv.tv.core.util.RelativeTime
import com.hometv.tv.core.util.Stars
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.NavRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** ratings prd3 FR-T15: the Actividad list (paging, dedupe, own rows, relative times, reload when shown). */
class ActivityViewModelTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    private val repos = TestRepos(appScope = CoroutineScope(SupervisorJob() + main.dispatcher))
    private val api = repos.api

    /** A clock the test moves (UTC). */
    private class MovingClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MovingClock(Instant.parse("2026-10-31T12:00:00Z"))
    private val avatars = listOf(Avatar("avatar-02", "#1DB954"))

    private fun entry(profile: Long, name: String, filmId: Long, stars: Double = 4.5, ratedAt: String? = "2026-10-31T11:55:00Z", show: Boolean = false) = ActivityEntry(
        profile = ProfileRef(profile, name, "avatar-0$profile"),
        kind = if (show) "show" else "film",
        item = if (show) ShowSummary(filmId, "Show $filmId", poster = Image("/p$filmId.jpg")) else FilmSummary(filmId, "Film $filmId", poster = Image("/p$filmId.jpg")),
        stars = stars,
        rated_at = ratedAt,
    )

    /** [count] entries by Luis (profile 2) on films 1..count, newest first. */
    private fun library(count: Int) {
        val all = (1..count).map { entry(2, "Luis", it.toLong()) }
        api.onActivity = { p, s -> Page(all.drop((p - 1) * s).take(s), p, s, all.size) }
    }

    private fun vm() = ActivityViewModel(repos.ratings, repos.store, clock, avatars = { avatars })

    @Test
    fun `rows carry the sentence parts, own rows, stars, a relative time and the poster`() = runTest(main.dispatcher) {
        api.onActivity = { p, s ->
            Page(
                listOf(
                    entry(2, "Luis", 12, stars = 2.0),
                    entry(5, "Esther", 7, stars = 5.0, ratedAt = "2026-10-30T09:00:00Z", show = true),
                    entry(3, "Ana", 13, ratedAt = "nonsense"),
                ),
                p, s, 3,
            )
        }
        val vm = vm()
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(ActivityPhase.CONTENT, state.phase)
        assertEquals(
            ActivityRowUi("2:film:12", own = false, name = "Luis", initial = "L", color = "#1DB954", title = "Film 12", stars = Stars(4), time = RelativeTime.Minutes(5), poster = "/p12.jpg", kind = "film", id = 12),
            state.items[0],
        )
        assertTrue(state.items[1].own)
        assertEquals(RelativeTime.Yesterday, state.items[1].time)
        assertEquals("show", state.items[1].kind)
        assertNull(state.items[2].time, "unparseable rated_at: no time")
        assertNull(state.items[2].color)
        assertEquals(listOf("GET /api/activity?page=1&page_size=50"), api.calls)
    }

    @Test
    fun `an empty household shows the empty state, a page-1 failure the error and retry`() = runTest(main.dispatcher) {
        val empty = vm()
        advanceUntilIdle()
        assertEquals(ActivityPhase.EMPTY, empty.state.value.phase)

        api.onActivity = { _, _ -> throw httpError(500, "internal_error") }
        val failing = vm()
        advanceUntilIdle()
        assertEquals(ActivityPhase.ERROR, failing.state.value.phase)
        assertEquals(ScreenError.LoadFailed, failing.state.value.error)
        library(3)
        failing.retry()
        advanceUntilIdle()
        assertEquals(ActivityPhase.CONTENT, failing.state.value.phase)
        assertEquals(3, failing.state.value.items.size)
    }

    @Test
    fun `focus on the last five rows loads the next page once, deduped by key`() = runTest(main.dispatcher) {
        library(120)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(50, vm.state.value.items.size)
        vm.onFocused(40)
        advanceUntilIdle()
        assertEquals(1, api.calls.size, "not near the end yet")
        vm.onFocused(45)
        vm.onFocused(46)
        advanceUntilIdle()
        assertEquals(listOf("GET /api/activity?page=1&page_size=50", "GET /api/activity?page=2&page_size=50"), api.calls)
        assertEquals(100, vm.state.value.items.size)
        assertEquals(vm.state.value.items.size, vm.state.value.items.map { it.key }.toSet().size)
        vm.onFocused(99)
        advanceUntilIdle()
        assertEquals(120, vm.state.value.items.size)
        assertTrue(vm.state.value.endReached)
        vm.onFocused(119)
        advanceUntilIdle()
        assertEquals(3, api.calls.size)
    }

    @Test
    fun `a failed next page shows the retry row and Reintentar loads it`() = runTest(main.dispatcher) {
        library(60)
        val vm = vm()
        advanceUntilIdle()
        val ok = api.onActivity
        api.onActivity = { _, _ -> throw httpError(500, "internal_error") }
        vm.onFocused(49)
        advanceUntilIdle()
        assertTrue(vm.state.value.pageFailed)
        assertEquals(50, vm.state.value.items.size)
        vm.onFocused(49)
        advanceUntilIdle()
        assertEquals(2, api.calls.size, "a failed page waits for Reintentar")
        api.onActivity = ok
        vm.retry()
        advanceUntilIdle()
        assertFalse(vm.state.value.pageFailed)
        assertEquals(60, vm.state.value.items.size)
    }

    @Test
    fun `back after being hidden reloads every loaded page in one request, keeps the focused row and recomputes times`() = runTest(main.dispatcher) {
        library(120)
        val vm = vm()
        advanceUntilIdle()
        vm.onFocused(47)
        advanceUntilIdle()
        vm.onRowClick(vm.state.value.items[47])
        assertEquals(NavRequest.Detail("film", 48), vm.navigation.first())
        vm.onHidden()
        clock.now = Instant.parse("2026-10-31T14:00:00Z")
        // A new rating on top shifts every row by one.
        val all = listOf(entry(3, "Ana", 500)) + (1..120).map { entry(2, "Luis", it.toLong()) }
        api.onActivity = { p, s -> Page(all.drop((p - 1) * s).take(s), p, s, all.size) }
        api.calls.clear()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(listOf("GET /api/activity?page=1&page_size=100"), api.calls)
        val state = vm.state.value
        assertEquals("3:film:500", state.items[0].key)
        assertEquals(RelativeTime.Hours(2), state.items[1].time)
        assertEquals(48, state.focus?.index, "the row that opened the detail, by key")
        assertEquals("2:film:48", state.items[state.focus!!.index].key)
    }

    @Test
    fun `the first resume does not reload, more than four pages reload page by page`() = runTest(main.dispatcher) {
        library(300)
        val vm = vm()
        advanceUntilIdle()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(1, api.calls.size)
        listOf(45, 95, 145, 195).forEach {
            vm.onFocused(it)
            advanceUntilIdle()
        }
        assertEquals(250, vm.state.value.items.size)
        api.calls.clear()
        vm.onHidden()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals((1..5).map { "GET /api/activity?page=$it&page_size=50" }, api.calls)
        assertEquals(250, vm.state.value.items.size)
    }

    @Test
    fun `a rating change reloads while the screen is visible and waits for the next resume when hidden`() = runTest(main.dispatcher) {
        library(3)
        val vm = vm()
        advanceUntilIdle()
        vm.onVisible()
        api.calls.clear()
        repos.ratings.set(RatingKind.FILM, 1, Stars(3))
        advanceUntilIdle()
        assertEquals(listOf("PUT /api/ratings/films/1", "GET /api/activity?page=1&page_size=50"), api.calls)
        vm.onHidden()
        api.calls.clear()
        repos.ratings.set(RatingKind.FILM, 2, Stars(3))
        advanceUntilIdle()
        assertEquals(listOf("PUT /api/ratings/films/2"), api.calls)
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(listOf("PUT /api/ratings/films/2", "GET /api/activity?page=1&page_size=50"), api.calls)
    }

    @Test
    fun `a rating change during a gated reload or page 1 reloads once more after it`() = runTest(main.dispatcher) {
        var all = (1..3).map { entry(2, "Luis", it.toLong()) }
        var gate: CompletableDeferred<Unit>? = null
        api.onActivity = { p, s ->
            val snapshot = all // read when the request leaves: a later save is not in it
            gate?.await()
            Page(snapshot.drop((p - 1) * s).take(s), p, s, snapshot.size)
        }
        // Page 1 in flight when the save lands.
        gate = CompletableDeferred()
        val vm = vm()
        vm.onVisible()
        runCurrent()
        all = listOf(entry(5, "Esther", 9)) + all
        repos.ratings.set(RatingKind.FILM, 9, Stars(8))
        runCurrent()
        gate!!.complete(Unit)
        gate = null
        advanceUntilIdle()
        assertEquals("5:film:9", vm.state.value.items.first().key, "the save made during page 1 is shown")
        // A resume reload in flight when another save lands.
        vm.onHidden()
        gate = CompletableDeferred()
        vm.onVisible()
        runCurrent()
        all = listOf(entry(5, "Esther", 10)) + all
        repos.ratings.set(RatingKind.FILM, 10, Stars(8))
        runCurrent()
        gate!!.complete(Unit)
        gate = null
        advanceUntilIdle()
        assertEquals("5:film:10", vm.state.value.items.first().key, "the save made during the reload is shown")
        assertEquals(4, api.calls.count { it.startsWith("GET /api/activity") })
    }
}
