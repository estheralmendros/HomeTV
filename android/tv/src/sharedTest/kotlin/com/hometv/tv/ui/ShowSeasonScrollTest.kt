package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.SeasonDetail
import com.hometv.tv.core.api.SeasonSummary
import com.hometv.tv.core.api.ShowDetail
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.testing.UiTestPlatform
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Show detail, the episode list's scroll (decision A of `specs/tv-improvements/idea.md`): another
 * season's list shows the episode Right lands on (its next episode to watch, else E1); going back
 * to the season of the episode that had focus keeps the scroll.
 */
@RunWith(AndroidJUnit4::class)
class ShowSeasonScrollTest : UiTestBase() {
    private fun episode(season: Int, number: Int, progress: Progress? = null) = EpisodeSummary(
        id = season * 100L + number, show_id = 7, show_title = "The Office", season_number = season, episode_number = number,
        title = "Episode $number", progress = progress,
    )

    /** Three seasons of 10 episodes; the next episode to watch is T2 E8. */
    private fun openShow() {
        useLibrary()
        val next = episode(2, 8, Progress(position_seconds = 600.0, duration_seconds = 1320.0))
        api.onShow = {
            ShowDetail(
                id = 7, title = "The Office", year = 2005, status = "Ended", unwatched_episode_count = 23, next_episode = next,
                seasons = (1..3).map { SeasonSummary(it.toLong(), it, null, unwatched_episode_count = 10) },
            )
        }
        api.onSeason = { _, n ->
            SeasonDetail(n.toLong(), 7, "The Office", n, null, episodes = (1..10).map { e -> if (n == 2 && e == 8) next else episode(n, e) })
        }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        press(Key.DirectionDown, times = 2)
        settleSeason()
        assertFocused("season_2")
    }

    /** The 250 ms season debounce, then the fetch. */
    private fun settleSeason() {
        UiTestPlatform.advance(300)
        settle()
    }

    private fun shown(tag: String) = !absent(tag) && runCatching { node(tag).assertIsDisplayed() }.isSuccess

    @Test
    fun theFirstSeasonShownStartsAtItsEntryEpisode() {
        openShow()
        node("episode_208").assertIsDisplayed()
        press(Key.DirectionRight)
        assertFocused("episode_208")
    }

    @Test
    fun anotherSeasonStartsAtItsFirstEpisodeAndRightLandsThere() {
        openShow()
        press(Key.DirectionRight)
        assertFocused("episode_208")
        press(Key.DirectionLeft)
        press(Key.DirectionUp)
        settleSeason()
        assertFocused("season_1")
        assertTrue("T1 E1 is not on screen", shown("episode_101"))
        press(Key.DirectionDown, times = 2)
        settleSeason()
        assertFocused("season_3")
        assertTrue("T3 E1 is not on screen", shown("episode_301"))
        press(Key.DirectionRight)
        assertFocused("episode_301")
    }

    @Test
    fun backToTheEntrySeasonShowsItsNextEpisode() {
        openShow()
        press(Key.DirectionRight)
        assertFocused("episode_208")
        press(Key.DirectionLeft)
        press(Key.DirectionUp)
        settleSeason()
        press(Key.DirectionDown)
        settleSeason()
        assertFocused("season_2")
        assertTrue("T2 E8 is not on screen", shown("episode_208"))
        press(Key.DirectionRight)
        assertFocused("episode_208")
    }

    @Test
    fun leavingAnEpisodeForItsOwnSeasonKeepsTheScroll() {
        openShow()
        press(Key.DirectionRight)
        press(Key.DirectionDown, times = 2)
        assertFocused("episode_210")
        press(Key.DirectionLeft)
        settleSeason()
        assertFocused("season_2")
        assertTrue("T2 E10 left the screen", shown("episode_210"))
        assertTrue("the list went back to T2 E1", !shown("episode_201"))
    }

    // ---- Framing (decision C): the panes run to the screen's bottom edge ---------------------

    private fun walkToTheLastEpisode() {
        openShow()
        assertReachesBottomEdge("season_list")
        assertReachesBottomEdge("episode_list")
        press(Key.DirectionRight)
        assertFocused("episode_208")
        press(Key.DirectionDown, times = 2)
        assertFocused("episode_210")
        assertFullyVisible("episode_210")
        assertReachesBottomEdge("episode_list")
    }

    @Test
    fun thePanesRunToTheBottomEdgeAndTheLastEpisodeStaysInsideTheSafeArea() = walkToTheLastEpisode()

    @Test
    fun onATvTheLastEpisodeStaysInsideTheSafeAreaToo() {
        UiTestPlatform.declareTelevision()
        walkToTheLastEpisode()
    }
}
