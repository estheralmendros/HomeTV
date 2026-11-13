package com.hometv.tv.ui.home

import androidx.lifecycle.ViewModelStore
import com.hometv.tv.R
import com.hometv.tv.core.api.ApiJson
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.Fixtures
import com.hometv.tv.core.api.HomeResponse
import com.hometv.tv.core.api.Image
import com.hometv.tv.core.api.LibraryCounts
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.util.EpisodeCode
import com.hometv.tv.core.util.EpisodeTitle
import com.hometv.tv.data.HomeCache
import com.hometv.tv.data.HomePayload
import com.hometv.tv.data.HomeRefresh
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.Library
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.navigation.PlayRequest
import com.hometv.tv.ui.text.UiText
import java.net.UnknownHostException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class HomeViewModelTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    private val repos = TestRepos()
    private val cacheDir = Files.createTempDirectory("home").toFile()
    private val cache = HomeCache(cacheDir, Dispatchers.Unconfined)
    private val refresh = HomeRefresh()
    private val home = ApiJson.decodeFromString(HomeResponse.serializer(), Fixtures.home)
    private val dune = FilmSummary(12, "Dune", 2021, poster = Image("/p/dune.jpg"), backdrop = Image("/b/dune.jpg"), overview = "Arrakis.")
    private val alien = FilmSummary(13, "Alien", 1979)
    private val office = ShowSummary(7, "The Office", 2005, unwatched_episode_count = 1234)

    init {
        repos.api.onHome = { home }
        repos.api.onFilms = { p, s, _, _ -> Page(listOf(dune, alien), p, s, 2) }
        repos.api.onShows = { p, s, _, _ -> Page(listOf(office), p, s, 1) }
    }

    /** `holdPrompts` calls, in order. */
    private val holds = mutableListOf<Boolean>()

    private fun vm(onFreshLoad: suspend () -> Unit = {}, signOut: suspend () -> Unit = {}) =
        HomeViewModel(repos.catalog, cache, repos.store, repos.events.events, refresh, onFreshLoad, signOut, holdPrompts = { holds += it })

    private fun HomeUiState.row(id: HomeRowId) = rows.first { it.id == id }

    private fun HomeUiState.keys() = rows.associate { row -> row.id to row.cards.map { it.key } }

    @Test
    fun `loads home, films and shows in parallel and shows the rows in order, hiding empty ones`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(listOf(HomeRowId.CONTINUE, HomeRowId.RECENT, HomeRowId.FILMS, HomeRowId.SHOWS), state.rows.map { it.id })
        assertTrue("GET /api/home?limit=20" in repos.api.calls)
        assertTrue("GET /api/films?page=1&page_size=20&sort=added&order=desc" in repos.api.calls)
        assertTrue("GET /api/shows?page=1&page_size=20&sort=added&order=desc" in repos.api.calls)
        assertEquals(listOf("continue:film:12", "continue:episode:345"), state.row(HomeRowId.CONTINUE).cards.map { it.key })
        assertEquals(listOf("films:film:12", "films:film:13", "see_all:films"), state.row(HomeRowId.FILMS).cards.map { it.key })
        assertEquals(SeeAllCard(Library.SHOWS), state.row(HomeRowId.SHOWS).cards.last())
        assertFalse(state.libraryEmpty)
        assertNull(state.error)
    }

    @Test
    fun `one Seguir viendo row holds the in-progress items and the Next Up episodes, OK on a Next Up card starts from the beginning`() = runTest(main.dispatcher) {
        val inProgress = home.continue_watching[0]
        val nextUp = (home.continue_watching[1] as EpisodeSummary).copy(id = 346, episode_number = 4, title = "The Fire", progress = null)
        repos.api.onHome = { home.copy(continue_watching = listOf(inProgress, nextUp)) }
        val vm = vm()
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(listOf("continue", "recent", "films", "shows"), HomeRowId.entries.map { it.prefix }, "no A continuación row")
        assertEquals(listOf(HomeRowId.CONTINUE, HomeRowId.RECENT, HomeRowId.FILMS, HomeRowId.SHOWS), state.rows.map { it.id })
        assertEquals(listOf("continue:film:12", "continue:episode:346"), state.row(HomeRowId.CONTINUE).cards.map { it.key })
        val card = state.row(HomeRowId.CONTINUE).cards[1] as ItemCard
        assertNull(card.progress, "no progress strip without progress")
        assertEquals(CardStyle.LANDSCAPE, card.style)
        assertEquals(UiText.Res(R.string.pair_space, listOf(EpisodeCode(2, 4), EpisodeTitle.Named("The Fire"))), card.subtitle)
        vm.onCardClick(card)
        val nav = vm.navigation.first() as NavRequest.Play
        assertEquals("episode", nav.request.itemType)
        assertEquals(346L, nav.request.itemId)
        assertNull(nav.request.payload!!.progress, "no progress: the Player starts from the beginning")
        assertEquals(listOf(91L, 92L), nav.request.payload!!.media_files.map { it.id })
        assertTrue(repos.api.calls.none { it.startsWith("GET /api/episodes") })
    }

    @Test
    fun `an old cached payload that still has next_up renders without an A continuación row`() = runTest(main.dispatcher) {
        val legacyEpisode = """
            { "type": "episode", "id": 346, "show_id": 7, "show_title": "The Office", "season_id": 70,
              "season_number": 2, "episode_number": 4, "title": "The Fire", "progress": null, "media_files": [] }
        """.trimIndent()
        val legacyHome = Fixtures.home.replace("\"recently_added\": [", "\"next_up\": [ $legacyEpisode ], \"recently_added\": [")
        java.io.File(cacheDir, "5.json").writeText("""{ "home": $legacyHome, "films": [], "shows": [] }""")
        repos.api.onHome = { delay(5_000); home }
        val vm = vm()
        runCurrent()
        assertTrue(vm.state.value.fromCache)
        assertEquals(listOf(HomeRowId.CONTINUE, HomeRowId.RECENT), vm.state.value.rows.map { it.id })
    }

    @Test
    fun `cards carry images, progress, episode subtitles and unwatched badges`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val state = vm.state.value
        val (film, episode) = state.row(HomeRowId.CONTINUE).cards.map { it as ItemCard }
        assertEquals(CardStyle.LANDSCAPE, film.style)
        assertEquals("/api/artwork/w780/b.jpg", film.image)
        assertEquals(1234.5 / 9360.0, film.progress!!.toDouble(), 1e-6)
        assertEquals("The Office", episode.title)
        assertEquals("/api/artwork/w780/ob.jpg", episode.image, "still is null: falls back to show_backdrop.small")
        assertEquals(UiText.Res(R.string.pair_space, listOf(EpisodeCode(2, 3), EpisodeTitle.Named("Office Olympics"))), episode.subtitle)
        val show = state.row(HomeRowId.SHOWS).cards.first() as ItemCard
        assertEquals(CardStyle.POSTER, show.style)
        assertEquals("1.234", show.badge)
    }

    @Test
    fun `an empty library shows the empty message`() = runTest(main.dispatcher) {
        repos.api.onHome = { HomeResponse(library = LibraryCounts(empty = true)) }
        repos.api.onFilms = { p, s, _, _ -> Page(emptyList(), p, s, 0) }
        repos.api.onShows = { p, s, _, _ -> Page(emptyList(), p, s, 0) }
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value.libraryEmpty)
        assertEquals(emptyList<HomeRow>(), vm.state.value.rows)
    }

    @Test
    fun `cached rows render before the network answers, then fresh data replaces them and is cached`() = runTest(main.dispatcher) {
        cache.write(5, HomePayload(HomeResponse(recently_added = listOf(alien)), films = listOf(alien)))
        repos.api.onHome = { delay(2_000); home }
        val vm = vm()
        runCurrent()
        advanceTimeBy(100)
        assertEquals(listOf("recent:film:13"), vm.state.value.row(HomeRowId.RECENT).cards.map { it.key })
        assertTrue(vm.state.value.fromCache)
        advanceUntilIdle()
        assertFalse(vm.state.value.fromCache)
        assertEquals(listOf("recent:show:7"), vm.state.value.row(HomeRowId.RECENT).cards.map { it.key })
        assertEquals(listOf("show:7"), cache.read(5)!!.home.recently_added.map { it.key })
    }

    // ---- Focus on entry (decision B of specs/tv-improvements/idea.md) ----------------------

    /** A cached Home whose newest item is Alien, and a server that answers [fresh] after 2 s. */
    private suspend fun cachedAlienThen(fresh: HomeResponse): HomeViewModel {
        cache.write(5, HomePayload(HomeResponse(recently_added = listOf(alien)), films = listOf(dune, alien)))
        repos.api.onHome = { delay(2_000); fresh }
        return vm()
    }

    /** The UI lands focus on the target it was given. */
    private fun HomeViewModel.landOnFocus() {
        val target = state.value.focus!!
        onCardFocused(state.value.rows[target.rowIndex].cards[target.cardIndex] as ItemCard)
    }

    @Test
    fun `a cold start focuses the newest card when the fresh list puts a new one in front of the cached first card`() = runTest(main.dispatcher) {
        val vm = cachedAlienThen(HomeResponse(recently_added = listOf(dune, alien)))
        runCurrent()
        assertEquals("recent:film:13", vm.state.value.focus!!.key)
        vm.landOnFocus()
        advanceUntilIdle()
        val focus = vm.state.value.focus!!
        assertEquals("recent:film:12", focus.key, "the newest item, not the one the cache had first")
        assertEquals(0 to 0, focus.rowIndex to focus.cardIndex)
    }

    @Test
    fun `a cold start leaves the focus where the user moved it before the fresh list arrived`() = runTest(main.dispatcher) {
        val vm = cachedAlienThen(HomeResponse(recently_added = listOf(dune, alien)))
        runCurrent()
        vm.landOnFocus()
        vm.onCardFocused(vm.state.value.row(HomeRowId.FILMS).cards[0] as ItemCard)
        advanceUntilIdle()
        assertEquals("films:film:12", vm.state.value.focus!!.key)
    }

    @Test
    fun `a card opened from the entry focus is restored by key after fresh data`() = runTest(main.dispatcher) {
        val vm = cachedAlienThen(HomeResponse(recently_added = listOf(alien)))
        advanceUntilIdle()
        vm.landOnFocus()
        val alienCard = vm.state.value.row(HomeRowId.RECENT).cards[0]
        vm.onCardClick(alienCard)
        repos.api.onHome = { HomeResponse(recently_added = listOf(dune, alien)) }
        refresh.markStale()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals("recent:film:13", vm.state.value.focus!!.key, "Back from the detail: the card that opened it")
        assertEquals("recent:film:13", vm.restoreTarget()!!.key)
    }

    @Test
    fun `coming back to the app focuses the first card at once, reloads, then focuses the newest card`() = runTest(main.dispatcher) {
        repos.api.onHome = { HomeResponse(recently_added = listOf(alien)) }
        val vm = vm()
        advanceUntilIdle()
        vm.landOnFocus()
        vm.onCardFocused(vm.state.value.row(HomeRowId.FILMS).cards[1] as ItemCard)
        val before = vm.state.value.focus!!.version
        repos.api.onHome = { delay(2_000); HomeResponse(recently_added = listOf(dune, alien)) }

        vm.onAppLeft()
        vm.onVisible()
        runCurrent()
        assertEquals("recent:film:13", vm.state.value.focus!!.key, "the first card of what is on screen")
        assertTrue(vm.state.value.focus!!.version > before)
        vm.landOnFocus()
        advanceUntilIdle()
        assertEquals(2, repos.api.count("GET /api/home?limit=20"))
        assertEquals("recent:film:12", vm.state.value.focus!!.key)
        assertEquals("recent:film:12", vm.restoreTarget()!!.key)
    }

    @Test
    fun `coming back to the app keeps a move made while the reload runs`() = runTest(main.dispatcher) {
        repos.api.onHome = { HomeResponse(recently_added = listOf(alien)) }
        val vm = vm()
        advanceUntilIdle()
        vm.landOnFocus()
        repos.api.onHome = { delay(2_000); HomeResponse(recently_added = listOf(dune, alien)) }
        vm.onAppLeft()
        vm.onVisible()
        runCurrent()
        vm.landOnFocus()
        vm.onCardFocused(vm.state.value.row(HomeRowId.FILMS).cards[1] as ItemCard)
        advanceUntilIdle()
        assertEquals("films:film:13", vm.state.value.focus!!.key)
    }

    @Test
    fun `a 401 on home renews the session silently and shows the content`() = runTest(main.dispatcher) {
        repos.recordEvents(backgroundScope)
        var first = true
        repos.api.onHome = { if (first) { first = false; throw httpError(401, "unauthorized") } else home }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(1, repos.api.count("POST /api/profiles/5/session"))
        assertEquals("tok-5", repos.store.session.value.token)
        assertTrue(vm.state.value.rows.isNotEmpty())
        assertNull(vm.state.value.error)
        assertTrue(repos.received.none { it is AppEvent.AuthExpired })
    }

    @Test
    fun `an unreachable server shows the Connection Error and Retry reloads`() = runTest(main.dispatcher) {
        repos.api.onHome = { throw UnknownHostException("hometv") }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(ScreenError.Connection("hometv.tail1234.ts.net", false), vm.state.value.error)
        repos.api.onHome = { home }
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.rows.isNotEmpty())
    }

    @Test
    fun `episode cards play from the home payload's media files without fetching the episode`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val episode = vm.state.value.row(HomeRowId.CONTINUE).cards[1] as ItemCard
        vm.onCardClick(episode)
        val nav = vm.navigation.first() as NavRequest.Play
        assertEquals("episode", nav.request.itemType)
        assertEquals(345L, nav.request.itemId)
        assertEquals(listOf(91L, 92L), nav.request.payload!!.media_files.map { it.id })
        assertFalse(nav.request.fromStart)
        assertTrue(repos.api.calls.none { it.startsWith("GET /api/episodes") })
    }

    @Test
    fun `film cards of Continue Watching open the player without media files, the player fetches the film`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onCardClick(vm.state.value.row(HomeRowId.CONTINUE).cards[0])
        assertEquals(NavRequest.Play(PlayRequest("film", 12)), vm.navigation.first())
        assertTrue(repos.api.calls.none { it.startsWith("GET /api/films/") })
    }

    @Test
    fun `poster cards open the detail, See all opens Browse, long press opens the detail`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onCardClick(vm.state.value.row(HomeRowId.RECENT).cards[0])
        assertEquals(NavRequest.Detail("show", 7), vm.navigation.first())
        vm.onCardClick(vm.state.value.row(HomeRowId.FILMS).cards.last())
        assertEquals(NavRequest.Browse(Library.FILMS), vm.navigation.first())
        vm.onCardLongClick(vm.state.value.row(HomeRowId.CONTINUE).cards[1])
        assertEquals(NavRequest.Detail("show", 7), vm.navigation.first())
        vm.onCardLongClick(vm.state.value.row(HomeRowId.CONTINUE).cards[0])
        assertEquals(NavRequest.Detail("film", 12), vm.navigation.first())
    }

    @Test
    fun `the hero follows the focused card 300 ms after focus settles`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val cards = vm.state.value.row(HomeRowId.CONTINUE).cards.map { it as ItemCard }
        vm.onCardFocused(cards[0])
        advanceTimeBy(100)
        vm.onCardFocused(cards[1])
        advanceTimeBy(299)
        assertNull(vm.hero.value)
        advanceTimeBy(2)
        val hero = vm.hero.value!!
        // The full-bleed hero uses the large backdrop.
        assertEquals("/api/artwork/w1280/ob.jpg", hero.backdrop)
        assertEquals("The Office", hero.title)
        assertNull(hero.year)
        assertEquals(UiText.Res(R.string.pair_space, listOf(EpisodeCode(2, 3), EpisodeTitle.Named("Office Olympics"))), hero.episodeLine)
        assertEquals("Michael...", hero.overview)

        vm.onCardFocused(vm.state.value.row(HomeRowId.FILMS).cards[0] as ItemCard)
        advanceTimeBy(301)
        assertEquals(HeroUi("/b/dune.jpg", "Dune", "2021", null, "Arrakis."), vm.hero.value)
        vm.onCardFocused(vm.state.value.row(HomeRowId.FILMS).cards[1] as ItemCard)
        advanceTimeBy(301)
        assertEquals(HeroUi(null, "Alien", "1979", null, null), vm.hero.value, "null backdrop/overview degrade")
    }

    @Test
    fun `fresh data keeps the focused item by key, else the same index of its row`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onCardFocused(vm.state.value.row(HomeRowId.FILMS).cards[1] as ItemCard)
        repos.api.onFilms = { p, s, _, _ -> Page(listOf(FilmSummary(20, "Arrival", 2016), alien, dune), p, s, 3) }
        vm.retry()
        advanceUntilIdle()
        val focus = vm.state.value.focus!!
        assertEquals("films:film:13", focus.key)
        assertEquals(1, focus.cardIndex)

        repos.api.onFilms = { p, s, _, _ -> Page(listOf(FilmSummary(21, "Amélie", 2001), dune), p, s, 2) }
        vm.retry()
        advanceUntilIdle()
        assertEquals("films:film:12", vm.state.value.focus!!.key, "item gone: same index of the row")
    }

    @Test
    fun `ItemGone removes the card at once and reloads Home`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val homeCalls = repos.api.count("GET /api/home?limit=20")
        repos.api.onHome = { delay(1_000); home.copy(continue_watching = home.continue_watching.drop(1)) }
        repos.events.emit(AppEvent.ItemGone("film", 12))
        runCurrent()
        assertTrue(vm.state.value.keys().values.flatten().none { it.endsWith(":film:12") })
        advanceUntilIdle()
        assertEquals(homeCalls + 1, repos.api.count("GET /api/home?limit=20"))
    }

    @Test
    fun `Home reloads when it becomes visible again only after something marked it stale`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(1, repos.api.count("GET /api/home?limit=20"))
        refresh.markStale()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(2, repos.api.count("GET /api/home?limit=20"))
    }

    @Test
    fun `Back from a deep card returns to the first card, then opens the exit dialog`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onCardFocused(vm.state.value.row(HomeRowId.SHOWS).cards[0] as ItemCard)
        vm.onBack()
        assertEquals("continue:film:12", vm.state.value.focus!!.key)
        assertNull(vm.state.value.exitDialog)
        assertEquals(emptyList<Boolean>(), holds)
        vm.onCardFocused(vm.state.value.row(HomeRowId.CONTINUE).cards[0] as ItemCard)
        vm.onBack()
        assertEquals(ExitDialogUi(), vm.state.value.exitDialog)
        assertEquals(listOf(true), holds)
    }

    @Test
    fun `Back on the top nav returns to the first card`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onNavFocused()
        val before = vm.state.value.focus!!.version
        vm.onBack()
        assertEquals("continue:film:12", vm.state.value.focus!!.key)
        assertTrue(vm.state.value.focus!!.version > before)
        assertNull(vm.state.value.exitDialog)
    }

    @Test
    fun `Back while loading opens the exit dialog`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<HomeResponse>()
        repos.api.onHome = { gate.await() }
        val vm = vm()
        runCurrent()
        assertTrue(vm.state.value.loading)
        vm.onBack()
        assertEquals(ExitDialogUi(), vm.state.value.exitDialog)
        gate.complete(home)
    }

    @Test
    fun `Back with an empty library opens the exit dialog`() = runTest(main.dispatcher) {
        repos.api.onHome = { HomeResponse(library = LibraryCounts(empty = true)) }
        repos.api.onFilms = { p, s, _, _ -> Page(emptyList(), p, s, 0) }
        repos.api.onShows = { p, s, _, _ -> Page(emptyList(), p, s, 0) }
        val vm = vm()
        advanceUntilIdle()
        vm.onBack()
        assertEquals(ExitDialogUi(), vm.state.value.exitDialog)
    }

    @Test
    fun `Back on the error screen opens the exit dialog, also with cached rows behind it`() = runTest(main.dispatcher) {
        repos.api.onHome = { throw UnknownHostException("hometv") }
        val plain = vm()
        advanceUntilIdle()
        assertTrue(plain.state.value.error != null)
        plain.onBack()
        assertEquals(ExitDialogUi(), plain.state.value.exitDialog)

        cache.write(5, HomePayload(home))
        val cached = vm()
        advanceUntilIdle()
        assertTrue(cached.state.value.error != null && cached.state.value.rows.isNotEmpty())
        val focus = cached.state.value.focus
        cached.onBack()
        assertEquals(ExitDialogUi(), cached.state.value.exitDialog)
        assertEquals(focus, cached.state.value.focus, "no move to an invisible card")
    }

    @Test
    fun `Back with the dialog open does nothing and Cancelar closes it and releases the hold`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onCardFocused(vm.state.value.row(HomeRowId.CONTINUE).cards[0] as ItemCard)
        vm.onBack()
        val state = vm.state.value
        vm.onBack()
        assertEquals(state, vm.state.value)
        assertEquals(listOf(true), holds)
        vm.onExitDismissed()
        assertNull(vm.state.value.exitDialog)
        assertEquals(listOf(true, false), holds)
        assertEquals(state.rows, vm.state.value.rows)
    }

    @Test
    fun `Salir awaits the sign-out, then exits once, ignoring presses meanwhile`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var signOuts = 0
        val vm = vm(signOut = { signOuts++; gate.await() })
        val nav = mutableListOf<NavRequest>()
        backgroundScope.launch { vm.navigation.collect { nav += it } }
        advanceUntilIdle()
        vm.onCardFocused(vm.state.value.row(HomeRowId.CONTINUE).cards[0] as ItemCard)
        vm.onBack()

        vm.onExitConfirmed()
        runCurrent()
        assertEquals(ExitDialogUi(signingOut = true), vm.state.value.exitDialog)
        assertEquals(emptyList<NavRequest>(), nav)
        vm.onExitConfirmed()
        vm.onExitDismissed()
        vm.onBack()
        runCurrent()
        assertEquals(1, signOuts)
        assertEquals(ExitDialogUi(signingOut = true), vm.state.value.exitDialog)
        assertEquals(emptyList<NavRequest>(), nav)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf<NavRequest>(NavRequest.Exit), nav)
        assertEquals(1, signOuts)
    }

    @Test
    fun `Salir still exits once when the sign-out fails with something other than an IOException`() = runTest(main.dispatcher) {
        var signOuts = 0
        val vm = vm(signOut = { signOuts++; throw IllegalStateException("broken store") })
        val nav = mutableListOf<NavRequest>()
        backgroundScope.launch { vm.navigation.collect { nav += it } }
        advanceUntilIdle()
        vm.onCardFocused(vm.state.value.row(HomeRowId.CONTINUE).cards[0] as ItemCard)
        vm.onBack()

        vm.onExitConfirmed()
        advanceUntilIdle()
        assertEquals(listOf<NavRequest>(NavRequest.Exit), nav)
        assertEquals(1, signOuts)
        vm.onExitConfirmed()
        advanceUntilIdle()
        assertEquals(1, signOuts)
    }

    @Test
    fun `onExitConfirmed without the dialog does nothing`() = runTest(main.dispatcher) {
        var signOuts = 0
        val vm = vm(signOut = { signOuts++ })
        advanceUntilIdle()
        vm.onExitConfirmed()
        advanceUntilIdle()
        assertEquals(0, signOuts)
    }

    @Test
    fun `clearing the ViewModel releases the hold`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onCardFocused(vm.state.value.row(HomeRowId.CONTINUE).cards[0] as ItemCard)
        vm.onBack()
        ViewModelStore().apply { put("home", vm) }.clear()
        assertEquals(false, holds.last())
    }

    @Test
    fun `a load in flight at Salir is cancelled`() = runTest(main.dispatcher) {
        var freshLoads = 0
        repos.api.onHome = { delay(5_000); home }
        val vm = vm(onFreshLoad = { freshLoads++ })
        runCurrent()
        vm.onBack()
        assertEquals(ExitDialogUi(), vm.state.value.exitDialog)
        vm.onExitConfirmed()
        advanceUntilIdle()
        assertEquals(0, freshLoads)
        assertNull(cache.read(5))
        val homeCalls = repos.api.count("GET /api/home?limit=20")
        vm.retry()
        refresh.markStale()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(homeCalls, repos.api.count("GET /api/home?limit=20"))
        assertEquals(0, freshLoads)
        assertNull(cache.read(5))
    }

    @Test
    fun `cached episode payloads still contain their media files`() = runTest(main.dispatcher) {
        cache.write(5, HomePayload(home))
        repos.api.onHome = { delay(5_000); home }
        val vm = vm()
        runCurrent()
        val episode = vm.state.value.row(HomeRowId.CONTINUE).cards[1] as ItemCard
        assertEquals(2, (episode.summary as EpisodeSummary).media_files.size)
    }
}
