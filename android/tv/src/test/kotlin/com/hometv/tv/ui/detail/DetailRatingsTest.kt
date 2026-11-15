package com.hometv.tv.ui.detail

import com.hometv.tv.R
import com.hometv.tv.core.api.Avatar
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.HouseholdAverage
import com.hometv.tv.core.api.Image
import com.hometv.tv.core.api.OtherRating
import com.hometv.tv.core.api.ProfileRef
import com.hometv.tv.core.api.RatingPrompt
import com.hometv.tv.core.api.SeasonDetail
import com.hometv.tv.core.api.SeasonSummary
import com.hometv.tv.core.api.ShowDetail
import com.hometv.tv.core.api.WatchedResponse
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.data.RatingKind
import com.hometv.tv.core.playback.CapsForFile
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.VersionCaps
import com.hometv.tv.core.util.Stars
import com.hometv.tv.data.DetailRefresh
import com.hometv.tv.data.HomeRefresh
import com.hometv.tv.data.PendingPrompt
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.ratings.RatingDialogUi
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

/** View-model side of the detail ratings row, its dialogs and focus targets. */
class DetailRatingsTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    private val repos = TestRepos(appScope = CoroutineScope(SupervisorJob() + main.dispatcher))
    private val api = repos.api
    private val avatars = listOf(Avatar("avatar-01", "#E50914"), Avatar("avatar-02", "#1DB954"))

    private fun other(id: Long, name: String, stars: Double, avatar: String = "avatar-0$id") =
        OtherRating(ProfileRef(id, name, avatar), stars, "2026-10-3${id % 10}T10:00:00Z")

    private var film = FilmDetail(
        id = 12, title = "Dune", match_status = "matched", media_files = listOf(F.file(1)), poster = Image("/s.jpg", "/l.jpg"),
        my_rating = 4.0, ratings = listOf(other(1, "Ana", 4.5), other(2, "Luis", 2.0)), household_average = HouseholdAverage(3.5, 3),
    )

    private val nextUp = EpisodeSummary(id = 203, show_id = 7, show_title = "The Office", season_number = 2, episode_number = 3)
    private var show = ShowDetail(
        id = 7, title = "The Office", next_episode = nextUp, unwatched_episode_count = 3,
        seasons = listOf(SeasonSummary(1, 1, null, unwatched_episode_count = 1), SeasonSummary(2, 2, null, unwatched_episode_count = 2)),
    )

    init {
        api.onFilm = { film }
        api.onShow = { show }
        api.onSeason = { _, n -> SeasonDetail(n.toLong(), 7, "The Office", n, null, episodes = listOf(nextUp.copy(id = n * 100L + 1, season_number = n, episode_number = 1))) }
    }

    private fun filmVm() = FilmDetailViewModel(
        12, repos.catalog, repos.watch, repos.watchlist, repos.ratings, repos.ratingPrompts, repos.store, { CapsForFile.of(F.caps).let { c -> VersionCaps(c, c) } },
        repos.events, HomeRefresh(), DetailRefresh(), avatars = { avatars },
    )

    private fun showVm() = ShowDetailViewModel(
        7, repos.catalog, repos.watch, repos.watchlist, repos.ratings, repos.ratingPrompts, repos.store, repos.events, HomeRefresh(), DetailRefresh(),
        avatars = { avatars },
    )

    private fun FilmDetailViewModel.content() = state.value as FilmDetailUiState.Content

    private fun ShowDetailViewModel.content() = state.value as ShowDetailUiState.Content

    private fun gate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { g -> api.onRatingWrite = { _, _, _ -> g.await() } }

    // ---- Row ----------------------------------------------------------------------------------

    @Test
    fun `the row has my stars, the average and the others newest first`() = runTest(main.dispatcher) {
        film = film.copy(ratings = (1L..5L).map { other(it, "P$it", 3.0) }, household_average = HouseholdAverage(4.333, 6))
        val vm = filmVm()
        advanceUntilIdle()
        val row = vm.content().ratingsRow
        assertEquals(Stars(8), row.mine)
        assertTrue(row.mineEnabled)
        assertEquals(AverageUi(Stars(9), UiText.Res(R.string.rating_average_value, listOf("4,3", "6"))), row.average)
        assertEquals(listOf("P1", "P2", "P3", "P4", "P5"), row.others.map { it.name })
        assertEquals(OtherRatingUi("P1", "P", "#E50914", Stars(6)), row.others[0])
        assertEquals(null, row.others[2].color, "no such avatar: the fallback colour")
        assertEquals(3, row.moreCount)
        assertNull(row.error)
    }

    @Test
    fun `nothing for others, no average, unrated`() = runTest(main.dispatcher) {
        film = film.copy(my_rating = null, ratings = emptyList(), household_average = null)
        val vm = filmVm()
        advanceUntilIdle()
        val row = vm.content().ratingsRow
        assertEquals(RatingsRowUi(mine = null, mineEnabled = true, average = null, others = emptyList(), moreCount = 0, error = null), row)
        assertEquals(UiText.Res(R.string.rating_rate), row.mineDescription)
        film = film.copy(my_rating = 4.5, ratings = listOf(other(1, "Ana", 4.5), other(2, "Luis", 2.0)))
        val rated = filmVm()
        advanceUntilIdle()
        assertEquals(0, rated.content().ratingsRow.moreCount)
        assertEquals(
            UiText.Res(R.string.rating_mine_description, listOf(UiText.Plural(R.plurals.rating_stars, 2, listOf(Stars(9))), UiText.Res(R.string.rating_change))),
            rated.content().ratingsRow.mineDescription,
        )
    }

    @Test
    fun `the others list has every profile and closing it focuses rating_more without touching the version`() = runTest(main.dispatcher) {
        film = film.copy(ratings = (1L..3L).map { other(it, "P$it", 4.5) })
        val vm = filmVm()
        advanceUntilIdle()
        vm.onRatingMore()
        val dialog = vm.content().othersDialog!!
        assertEquals(UiText.Res(R.string.rating_others_title), dialog.title)
        assertEquals(-1, dialog.selected)
        assertEquals(ButtonId.RATING_MORE, dialog.opener)
        assertEquals(UiText.Res(R.string.pair_dot, listOf("P1", UiText.Plural(R.plurals.rating_stars, 2, listOf(Stars(9))))), dialog.items[0])
        assertEquals(3, dialog.items.size)
        val version = vm.content().focusVersion
        vm.onOthersDialogClose()
        assertNull(vm.content().othersDialog)
        assertEquals(ButtonId.RATING_MORE, vm.content().focus)
        assertEquals(version + 1, vm.content().focusVersion)
        assertNull(vm.content().dialog)
    }

    @Test
    fun `rating_more falls back to rating_mine when a refetch removed it`() = runTest(main.dispatcher) {
        film = film.copy(ratings = (1L..3L).map { other(it, "P$it", 4.5) })
        val vm = filmVm()
        advanceUntilIdle()
        vm.onRatingMore()
        vm.onOthersDialogClose()
        film = film.copy(ratings = emptyList())
        repos.ratings.set(RatingKind.FILM, 12, Stars(3)) // a change refetches the detail
        advanceUntilIdle()
        assertEquals(0, vm.content().ratingsRow.moreCount)
        assertEquals(ButtonId.RATING_MINE, vm.content().focus)
    }

    // ---- Manual dialog ---------------------------------------------------------------------

    @Test
    fun `Cambiar opens the manual dialog on my value, saving updates the row at once and closing focuses rating_mine`() = runTest(main.dispatcher) {
        val vm = filmVm()
        advanceUntilIdle()
        vm.onRatingMine()
        assertEquals(RatingDialogUi(RatingDialogUi.Mode.MANUAL, "Dune", "/s.jpg", Stars(8), canRemove = true, phase = RatingDialogUi.Phase.EDITING), vm.content().ratingDialog)
        val open = gate()
        vm.onRatingStep(1)
        vm.onRatingSave()
        runCurrent()
        assertEquals(Stars(9), vm.content().ratingsRow.mine, "optimistic")
        assertEquals(RatingDialogUi.Phase.SAVING, vm.content().ratingDialog?.phase)
        assertFalse(vm.content().ratingsRow.mineEnabled, "disabled while the write is in flight")
        film = film.copy(my_rating = 4.5, household_average = HouseholdAverage(3.7, 3))
        open.complete(Unit)
        runCurrent()
        assertEquals(RatingDialogUi.Phase.SAVED, vm.content().ratingDialog?.phase)
        advanceUntilIdle()
        assertNull(vm.content().ratingDialog)
        assertEquals(ButtonId.RATING_MINE, vm.content().focus)
        assertEquals(2, api.count("GET /api/films/12"), "a silent refetch after the change")
        assertEquals(UiText.Res(R.string.rating_average_value, listOf("3,7", "3")), vm.content().ratingsRow.average?.text)
        assertTrue(vm.content().ratingsRow.mineEnabled)
        assertEquals(listOf("GET /api/films/12", "PUT /api/ratings/films/12", "GET /api/films/12"), api.calls)
    }

    @Test
    fun `a failed save rolls the row back and the dialog shows FAILED`() = runTest(main.dispatcher) {
        api.onRatingWrite = { _, _, _ -> throw httpError(500, "internal_error") }
        val vm = filmVm()
        advanceUntilIdle()
        vm.onRatingMine()
        vm.onRatingStep(-1)
        vm.onRatingSave()
        advanceUntilIdle()
        assertEquals(Stars(8), vm.content().ratingsRow.mine)
        assertEquals(RatingDialogUi.Phase.FAILED, vm.content().ratingDialog?.phase)
        assertEquals(Stars(7), vm.content().ratingDialog?.stars)
        assertNull(vm.content().ratingsRow.error, "no inline line for a save")
    }

    @Test
    fun `removing closes at once, clears the row and rolls back with the inline line on failure`() = runTest(main.dispatcher) {
        val open = gate()
        val vm = filmVm()
        advanceUntilIdle()
        vm.onRatingMine()
        vm.onRatingRemove()
        runCurrent()
        assertNull(vm.content().ratingDialog)
        assertEquals(ButtonId.RATING_MINE, vm.content().focus)
        assertNull(vm.content().ratingsRow.mine)
        assertFalse(vm.content().ratingsRow.mineEnabled)
        vm.onRatingMine()
        assertNull(vm.content().ratingDialog, "the chip is disabled while the write is in flight")
        open.completeExceptionally(httpError(500, "internal_error"))
        advanceUntilIdle()
        assertEquals(Stars(8), vm.content().ratingsRow.mine)
        assertEquals(UiText.Res(R.string.rating_update_failed), vm.content().ratingsRow.error)
        assertTrue(vm.content().ratingsRow.mineEnabled)
        vm.onButton(ButtonId.WATCHLIST)
        assertNull(vm.content().ratingsRow.error, "cleared by the next action")
    }

    @Test
    fun `a refetch while a write is in flight keeps the shown value`() = runTest(main.dispatcher) {
        val open = gate()
        val detailRefresh = DetailRefresh()
        val vm = FilmDetailViewModel(12, repos.catalog, repos.watch, repos.watchlist, repos.ratings, repos.ratingPrompts, repos.store, { CapsForFile.of(F.caps).let { c -> VersionCaps(c, c) } }, repos.events, HomeRefresh(), detailRefresh)
        advanceUntilIdle()
        vm.onRatingMine()
        vm.onRatingStep(1)
        vm.onRatingSave()
        runCurrent()
        detailRefresh.markStale()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(Stars(9), vm.content().ratingsRow.mine)
        film = film.copy(my_rating = 4.5)
        open.complete(Unit)
        advanceUntilIdle()
        assertEquals(Stars(9), vm.content().ratingsRow.mine)
    }

    @Test
    fun `a 404 on a detail save is the screen's ItemGone`() = runTest(main.dispatcher) {
        api.onRatingWrite = { _, _, _ -> throw httpError(404, "not_found") }
        val vm = filmVm()
        advanceUntilIdle()
        vm.onRatingMine()
        vm.onRatingSave()
        advanceUntilIdle()
        assertEquals(FilmDetailUiState.Error(ScreenError.ItemGone), vm.state.value)
    }

    @Test
    fun `a save from anywhere else updates my stars and refetches silently`() = runTest(main.dispatcher) {
        val vm = filmVm()
        advanceUntilIdle()
        val focus = vm.content().focus
        film = film.copy(my_rating = 2.5)
        repos.ratings.set(RatingKind.FILM, 12, Stars(5))
        advanceUntilIdle()
        assertEquals(Stars(5), vm.content().ratingsRow.mine)
        assertEquals(2, api.count("GET /api/films/12"))
        assertEquals(focus, vm.content().focus)
        // Another title changes nothing here.
        repos.ratings.set(RatingKind.SHOW, 12, Stars(1))
        advanceUntilIdle()
        assertEquals(2, api.count("GET /api/films/12"))
    }

    @Test
    fun `a change during an in-flight film load refetches once more after it`() = runTest(main.dispatcher) {
        val refresh = DetailRefresh()
        val vm = FilmDetailViewModel(
            12, repos.catalog, repos.watch, repos.watchlist, repos.ratings, repos.ratingPrompts, repos.store, { CapsForFile.of(F.caps).let { c -> VersionCaps(c, c) } },
            repos.events, HomeRefresh(), refresh, avatars = { avatars },
        )
        advanceUntilIdle()
        // A resume refetch starts before the write commits and answers the old household values.
        val stale = film
        val open = CompletableDeferred<Unit>()
        api.onFilm = { open.await(); stale }
        refresh.markStale()
        vm.onVisible()
        runCurrent()
        film = film.copy(my_rating = 5.0, household_average = HouseholdAverage(4.2, 3))
        repos.ratings.set(RatingKind.FILM, 12, Stars(10))
        runCurrent()
        api.onFilm = { film }
        open.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, api.count("GET /api/films/12"), "one more fetch after the in-flight one")
        assertEquals(AverageUi(Stars(8), UiText.Res(R.string.rating_average_value, listOf("4,2", "3"))), vm.content().ratingsRow.average)
    }

    @Test
    fun `a change during an in-flight show load refetches once more after it`() = runTest(main.dispatcher) {
        val refresh = DetailRefresh()
        val vm = ShowDetailViewModel(
            7, repos.catalog, repos.watch, repos.watchlist, repos.ratings, repos.ratingPrompts, repos.store, repos.events, HomeRefresh(), refresh,
            avatars = { avatars },
        )
        advanceUntilIdle()
        val stale = show
        val open = CompletableDeferred<Unit>()
        api.onShow = { open.await(); stale }
        refresh.markStale()
        vm.onVisible()
        runCurrent()
        show = show.copy(my_rating = 5.0, household_average = HouseholdAverage(4.2, 3))
        repos.ratings.set(RatingKind.SHOW, 7, Stars(10))
        runCurrent()
        api.onShow = { show }
        open.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, api.count("GET /api/shows/7"), "one more fetch after the in-flight one")
        assertEquals(AverageUi(Stars(8), UiText.Res(R.string.rating_average_value, listOf("4,2", "3"))), vm.content().ratingsRow.average)
    }

    // ---- A write elsewhere never republishes over Error or Loading -------------------------------

    @Test
    fun `a write from the root prompt keeps the film's Error screen`() = runTest(main.dispatcher) {
        val refresh = DetailRefresh()
        val vm = FilmDetailViewModel(
            12, repos.catalog, repos.watch, repos.watchlist, repos.ratings, repos.ratingPrompts, repos.store, { CapsForFile.of(F.caps).let { c -> VersionCaps(c, c) } },
            repos.events, HomeRefresh(), refresh, avatars = { avatars },
        )
        advanceUntilIdle()
        // The after-playback refetch fails: Error with [Reintentar].
        api.onFilm = { throw httpError(500, "internal_error") }
        refresh.markStale()
        vm.onVisible()
        advanceUntilIdle()
        val error = vm.state.value
        assertTrue(error is FilmDetailUiState.Error)
        // The root prompt saves this title: the writing flip and the change keep the Error screen.
        val open = gate()
        val write = repos.appScope.async { repos.ratings.set(RatingKind.FILM, 12, Stars(9)) }
        runCurrent()
        assertTrue(repos.ratings.isWriting(RatingKind.FILM, 12))
        assertEquals(error, vm.state.value, "the writing flip")
        open.complete(Unit)
        write.await()
        advanceUntilIdle()
        assertEquals(error, vm.state.value, "the change")
        // [Reintentar] shows the fetched detail with the new value.
        api.onFilm = { film.copy(my_rating = 4.5) }
        vm.retry()
        advanceUntilIdle()
        assertEquals(Stars(9), vm.content().ratingsRow.mine)
    }

    @Test
    fun `a write during a film retry keeps Loading until the fetch answers`() = runTest(main.dispatcher) {
        val vm = filmVm()
        advanceUntilIdle()
        val answer = CompletableDeferred<Unit>()
        api.onFilm = { answer.await(); film.copy(my_rating = 4.5) }
        vm.retry()
        runCurrent()
        assertEquals(FilmDetailUiState.Loading, vm.state.value)
        repos.ratings.set(RatingKind.FILM, 12, Stars(9))
        runCurrent()
        assertEquals(FilmDetailUiState.Loading, vm.state.value)
        answer.complete(Unit)
        advanceUntilIdle()
        assertEquals(Stars(9), vm.content().ratingsRow.mine)
    }

    @Test
    fun `a write during a show retry keeps Loading until the fetch answers`() = runTest(main.dispatcher) {
        val vm = showVm()
        advanceUntilIdle()
        val answer = CompletableDeferred<Unit>()
        api.onShow = { answer.await(); show.copy(my_rating = 4.5) }
        vm.retry()
        runCurrent()
        assertEquals(ShowDetailUiState.Loading, vm.state.value)
        repos.ratings.set(RatingKind.SHOW, 7, Stars(9))
        runCurrent()
        assertEquals(ShowDetailUiState.Loading, vm.state.value)
        answer.complete(Unit)
        advanceUntilIdle()
        assertEquals(Stars(9), vm.content().ratingsRow.mine)
    }

    // ---- Mark watched offers the prompt -----------------------------------------------------

    @Test
    fun `marking a film watched offers its prompt, unmarking never does`() = runTest(main.dispatcher) {
        val prompt = RatingPrompt("film", 12, "Dune")
        api.onWatched = { body -> WatchedResponse(1, if (body.watched) prompt else null) }
        val vm = filmVm()
        advanceUntilIdle()
        film = film.copy(progress = com.hometv.tv.core.api.Progress(watched = true)) // the refetch after the mark
        vm.onButton(ButtonId.WATCHED)
        advanceUntilIdle()
        assertEquals(PendingPrompt(5, prompt), repos.ratingPrompts.pending.value)
        repos.ratingPrompts.clear()
        vm.onButton(ButtonId.WATCHED)
        advanceUntilIdle()
        assertNull(repos.ratingPrompts.pending.value)
    }

    @Test
    fun `marking the show, a season or an episode watched offers the show's prompt`() = runTest(main.dispatcher) {
        val prompt = RatingPrompt("show", 7, "The Office")
        api.onWatched = { WatchedResponse(1, prompt) }
        val vm = showVm()
        advanceUntilIdle()
        vm.onButton(ButtonId.WATCHED)
        advanceUntilIdle()
        assertEquals(PendingPrompt(5, prompt), repos.ratingPrompts.take(5))
        repos.ratingPrompts.clear()
        vm.onSeasonWatched()
        advanceUntilIdle()
        assertEquals(PendingPrompt(5, prompt), repos.ratingPrompts.take(5))
        repos.ratingPrompts.clear()
        val row = vm.content().season!!.episodes.first()
        vm.onEpisodeLongClick(row)
        vm.onEpisodeToggleWatched()
        advanceUntilIdle()
        assertEquals(PendingPrompt(5, prompt), repos.ratingPrompts.pending.value)
    }

    // ---- Show focus --------------------------------------------------------------------------

    @Test
    fun `Show focusButton is one-shot after a rating dialog closes`() = runTest(main.dispatcher) {
        show = show.copy(my_rating = 3.0, ratings = (1L..3L).map { other(it, "P$it", 4.5) })
        val vm = showVm()
        advanceUntilIdle()
        assertNull(vm.content().focusButton)
        vm.onRatingMine()
        vm.onRatingSecondary()
        assertEquals(ButtonId.RATING_MINE, vm.content().focusButton)
        val version = vm.content().focusVersion
        vm.onRatingMore()
        vm.onOthersDialogClose()
        assertEquals(ButtonId.RATING_MORE, vm.content().focusButton)
        assertEquals(version + 1, vm.content().focusVersion)
        vm.onButton(ButtonId.WATCHED)
        advanceUntilIdle()
        assertNull(vm.content().focusButton, "marking watched keeps today's focus behaviour")
        vm.onRatingMore()
        show = show.copy(ratings = emptyList())
        vm.onOthersDialogClose()
        repos.ratings.set(RatingKind.SHOW, 7, Stars(4))
        advanceUntilIdle()
        assertEquals(ButtonId.RATING_MINE, vm.content().focusButton, "rating_more fell back to rating_mine")
    }
}
