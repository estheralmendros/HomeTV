package com.hometv.tv.ui.detail

import com.hometv.tv.R
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.Genre
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.SeasonDetail
import com.hometv.tv.core.api.SeasonSummary
import com.hometv.tv.core.api.ShowDetail
import com.hometv.tv.core.api.WatchedRequest
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.util.EpisodeCode
import com.hometv.tv.core.util.EpisodeTitle
import com.hometv.tv.core.util.Runtime
import com.hometv.tv.core.util.SeasonTitle
import com.hometv.tv.data.HomeRefresh
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.navigation.PlayRequest
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class ShowDetailViewModelTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    private val repos = TestRepos()
    private val refresh = HomeRefresh()

    private fun episode(id: Long, season: Int, number: Int, title: String? = "Episode $number", progress: Progress? = null) = EpisodeSummary(
        id = id, show_id = 7, show_title = "The Office", season_number = season, episode_number = number, title = title,
        air_date = "2005-10-04", runtime_minutes = 22, overview = "Overview $number", progress = progress,
    )

    private val nextUp = episode(203, 2, 3, "Office Olympics", Progress(position_seconds = 600.0, duration_seconds = 1320.0))
    private var show = ShowDetail(
        id = 7, title = "The Office", year = 2005, status = "Ended", vote_average = 8.6, genres = listOf(Genre(1, "Comedia")),
        seasons = listOf(
            SeasonSummary(1, 1, "Temporada 1", unwatched_episode_count = 1), SeasonSummary(2, 2, null, unwatched_episode_count = 3),
            SeasonSummary(3, 3, "Temporada 3", unwatched_episode_count = 1), SeasonSummary(0, 0, null, unwatched_episode_count = 1),
        ),
        next_episode = nextUp, unwatched_episode_count = 150,
    )
    private val seasons = mutableMapOf(
        1 to SeasonDetail(1, 7, "The Office", 1, "Temporada 1", episodes = listOf(episode(101, 1, 1))),
        2 to SeasonDetail(2, 7, "The Office", 2, null, episodes = listOf(episode(201, 2, 1), episode(202, 2, 2, title = null), nextUp)),
        3 to SeasonDetail(3, 7, "The Office", 3, "Temporada 3", episodes = listOf(episode(301, 3, 1))),
        0 to SeasonDetail(0, 7, "The Office", 0, null, episodes = listOf(episode(1, 0, 1))),
    )

    init {
        repos.api.onShow = { show }
        repos.api.onSeason = { _, n -> seasons[n] ?: throw httpError(404, "not_found") }
    }

    private fun vm() = ShowDetailViewModel(7, repos.catalog, repos.watch, repos.store, repos.events, refresh)

    private fun ShowDetailViewModel.content() = state.value as ShowDetailUiState.Content

    private fun seasonCalls() = repos.api.calls.filter { it.contains("/seasons/") }

    @Test
    fun `preselects the season of next_episode and labels the button Resume T2 E3`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("GET /api/shows/7", "GET /api/shows/7/seasons/2"), repos.api.calls)
        val content = vm.content()
        assertEquals(2, content.selectedSeason)
        assertEquals(listOf(ButtonId.RESUME, ButtonId.WATCHED), content.buttons.map { it.id })
        assertEquals(UiText.Res(R.string.show_resume_episode, listOf(EpisodeCode(2, 3))), content.buttons[0].text)
        assertEquals(UiText.Res(R.string.mark_show_watched), content.buttons[1].text)
        assertEquals(2, content.season!!.entryIndex, "Right from the season list lands on T2 E3")
        vm.onButton(ButtonId.RESUME)
        assertEquals(NavRequest.Play(PlayRequest("episode", 203)), vm.navigation.first())
    }

    @Test
    fun `next episode without a resumable position says Play, none at all shows mark unwatched first`() = runTest(main.dispatcher) {
        show = show.copy(next_episode = nextUp.copy(progress = Progress(position_seconds = 10.0)))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.show_play_episode, listOf(EpisodeCode(2, 3))), vm.content().buttons[0].text)
        assertEquals(ButtonId.PLAY, vm.content().buttons[0].id)

        show = show.copy(next_episode = null, unwatched_episode_count = 0)
        val done = vm()
        advanceUntilIdle()
        assertEquals(listOf(DetailButton(ButtonId.WATCHED, UiText.Res(R.string.mark_show_unwatched))), done.content().buttons)
        assertEquals(1, done.content().selectedSeason, "no next episode: the first season")
    }

    @Test
    fun `meta line maps the TMDB status and shows the unwatched count`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val expected = UiText.Joined(listOf(
            UiText.Data("2005"),
            UiText.Res(R.string.show_status_ended),
            UiText.Joined(listOf(UiText.Data("Comedia")), R.string.separator_comma),
            UiText.Res(R.string.rating, listOf("8,6")),
            UiText.Plural(R.plurals.unwatched_count, 150, listOf("150")),
        ))
        assertEquals(expected, vm.content().meta)
        show = show.copy(status = "Rumored")
        val other = vm()
        advanceUntilIdle()
        assertTrue((other.content().meta as UiText.Joined).parts.none { it == UiText.Res(R.string.show_status_ended) })
    }

    @Test
    fun `seasons and episodes use the structured fallbacks`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val content = vm.content()
        assertEquals(
            listOf<Any>(SeasonTitle.Named("Temporada 1"), SeasonTitle.Numbered(2), SeasonTitle.Named("Temporada 3"), SeasonTitle.Specials),
            content.seasons.map { (it.title as UiText.Value).value },
        )
        val pane = content.season!!
        assertEquals(UiText.Value(SeasonTitle.Numbered(2)), pane.title)
        assertEquals(UiText.Plural(R.plurals.episode_count, 3, listOf("3")), pane.count)
        assertEquals(UiText.Res(R.string.mark_season_watched), pane.watchedButton)
        val rows = pane.episodes
        assertEquals(UiText.Res(R.string.episode_row_title, listOf("1", "Episode 1")), rows[0].title)
        assertEquals(UiText.Value(EpisodeTitle.Numbered(2)), rows[1].title)
        assertEquals(UiText.Joined(listOf(UiText.Data("4 oct 2005"), UiText.Value(Runtime(0, 22)))), rows[2].meta)
        assertEquals(600f / 1320f, rows[2].progress!!, 1e-4f)
    }

    @Test
    fun `focusing a season selects it after 250 ms and fetched seasons are cached`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onSeasonFocused(1)
        advanceTimeBy(100)
        vm.onSeasonFocused(3)
        advanceTimeBy(249)
        assertEquals(listOf("GET /api/shows/7/seasons/2"), seasonCalls())
        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(listOf("GET /api/shows/7/seasons/2", "GET /api/shows/7/seasons/3"), seasonCalls())
        assertEquals(3, vm.content().selectedSeason)
        assertEquals(0, vm.content().season!!.entryIndex)
        vm.onSeasonFocused(2)
        advanceUntilIdle()
        assertEquals(2, vm.content().selectedSeason)
        assertEquals(2, seasonCalls().size, "season 2 comes from the cache")
    }

    @Test
    fun `episodes play on OK and long press opens the actions dialog`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val row = vm.content().season!!.episodes[0]
        vm.onEpisodeClick(row)
        assertEquals(NavRequest.Play(PlayRequest("episode", 201)), vm.navigation.first())
        vm.onEpisodeLongClick(row)
        assertEquals(EpisodeActionsUi(201, UiText.Res(R.string.mark_watched)), vm.content().episodeActions)
        vm.onEpisodePlayFromStart()
        assertEquals(NavRequest.Play(PlayRequest("episode", 201, fromStart = true)), vm.navigation.first())
        assertNull(vm.content().episodeActions)
    }

    @Test
    fun `watched changes refetch the show and the current season and keep the row`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onEpisodeLongClick(vm.content().season!!.episodes[1])
        vm.onEpisodeToggleWatched()
        advanceUntilIdle()
        assertEquals(WatchedRequest("episode", 202, true), repos.api.watchedBodies.last())
        assertEquals(2, repos.api.count("GET /api/shows/7"))
        assertEquals(2, repos.api.count("GET /api/shows/7/seasons/2"))
        assertEquals(1, vm.content().focusEpisodeIndex)
        assertTrue(refresh.consumeStale())

        vm.onSeasonWatched()
        advanceUntilIdle()
        assertEquals(WatchedRequest("season", 2, true), repos.api.watchedBodies.last())
        vm.onButton(ButtonId.WATCHED)
        advanceUntilIdle()
        assertEquals(WatchedRequest("show", 7, true), repos.api.watchedBodies.last())
        assertEquals(4, repos.api.count("GET /api/shows/7"))
    }

    @Test
    fun `a removed show is gone, a removed season shows the gone screen and Back refetches the show`() = runTest(main.dispatcher) {
        repos.recordEvents(backgroundScope)
        seasons.remove(2)
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.content().seasonGone)
        vm.onSeasonGoneBack()
        advanceUntilIdle()
        assertEquals(2, repos.api.count("GET /api/shows/7"))

        repos.api.onShow = { throw httpError(404, "not_found") }
        val gone = vm()
        advanceUntilIdle()
        assertEquals(ShowDetailUiState.Error(ScreenError.ItemGone), gone.state.value)
        assertEquals(AppEvent.ItemGone("show", 7), repos.received.last())
    }
}
