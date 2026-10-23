package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.SeasonDetail
import com.hometv.tv.core.api.SeasonSummary
import com.hometv.tv.core.api.ShowDetail
import com.hometv.tv.core.playback.F
import com.hometv.tv.testing.UiTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** prd3 §6/§7: a detail screen refetches in place when the Player pops back (DetailRefresh), keeping focus on its buttons. */
@RunWith(AndroidJUnit4::class)
class DetailRefreshFocusTest : UiTestBase() {
    private fun text(value: String) = rule.onNodeWithText(value, useUnmergedTree = true)

    @Test
    fun backFromThePlayerAfterPart1EndedOffersPart2WithFocusOnIt() {
        useLibrary()
        var part1Position = 600.0
        api.onFilm = { id ->
            FilmDetail(
                id = id, title = "Alien", match_status = "matched", media_files = listOf(F.part(11, 1), F.part(21, 2)),
                progress = F.progress(11, position = part1Position, duration = 3000.0),
            )
        }
        api.onPlayback = { id, _ -> PlaybackResponse("direct", url = "/api/media-files/$id/stream", audio_stream_index = 1, duration_seconds = 3000.0) }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertFocused("card_recent:film:13")
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
        press(Key.DirectionCenter)
        node("player").assertIsDisplayed()
        onPlayer { ready() }
        assertTrue(api.calls.contains("POST /api/media-files/11/playback"))

        part1Position = 2990.0 // Part 1 finished while playing; Back on the part prompt leaves the Player.
        val fetches = api.calls.count { it == "GET /api/films/13" } // the detail's and the Player's item load
        back()
        assertEquals(fetches + 1, api.calls.count { it == "GET /api/films/13" })
        text("Reproducir parte 2").assertIsDisplayed()
        assertFocused("button_PLAY_PART")
        press(Key.DirectionCenter)
        onPlayer { ready() }
        assertTrue(api.calls.contains("POST /api/media-files/21/playback"))
    }

    @Test
    fun backFromThePlayerUpdatesTheShowNextEpisodeButton() {
        useLibrary()
        var nextUp: EpisodeSummary = olympics
        api.onShow = {
            ShowDetail(
                id = 7, title = "The Office", year = 2005, status = "Ended", unwatched_episode_count = 3, next_episode = nextUp,
                seasons = listOf(SeasonSummary(1, 1, null, unwatched_episode_count = 1), SeasonSummary(2, 2, null, unwatched_episode_count = 2)),
            )
        }
        api.onSeason = { _, n ->
            SeasonDetail(n.toLong(), 7, "The Office", n, null, episodes = (1..4).map { e ->
                if (n == 2 && e == 3) olympics else EpisodeSummary(id = n * 100L + e, show_id = 7, show_title = "The Office", season_number = n, episode_number = e, title = "Episode $e")
            })
        }
        api.onEpisode = { id -> com.hometv.tv.core.api.EpisodeDetail(id, 7, "The Office", season_number = 2, episode_number = 3, media_files = listOf(F.file(2, duration = 1320.0))) }
        api.onPlayback = { id, _ -> PlaybackResponse("direct", url = "/api/media-files/$id/stream", audio_stream_index = 1, duration_seconds = 1320.0) }
        launch()
        press(Key.DirectionDown)
        assertFocused("card_recent:show:7")
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
        text("Reanudar T2 · E3").assertIsDisplayed()
        press(Key.DirectionCenter)
        onPlayer { ready() }

        nextUp = EpisodeSummary(id = 204, show_id = 7, show_title = "The Office", season_number = 2, episode_number = 4, title = "Episode 4")
        back()
        assertEquals(2, api.calls.count { it == "GET /api/shows/7" })
        text("Reproducir T2 · E4").assertIsDisplayed()
        assertFocused("button_PLAY")
    }
}
