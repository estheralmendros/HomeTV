package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.Me
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.data.Session
import com.hometv.tv.testing.TEST_SERVER
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.testing.UiTestPlatform
import com.hometv.tv.testing.performLongClickCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Compose UI tests: initial focus, D-pad moves, Back table, focus restoration. */
@RunWith(AndroidJUnit4::class)
class NavigationFocusTest : UiTestBase() {
    @Test
    fun setupStartsWithFocusOnTheUrlField() {
        launch(Session())
        assertFocused("setup_url")
        rule.onNodeWithText("https://").assertIsDisplayed()
        back()
        assertTrue("Back on the first-launch Setup exits the app", exited)
    }

    @Test
    fun profilePickerFocusesTheLastProfileAndOkGoesStraightToHome() {
        useLibrary()
        launch(Session(serverUrl = TEST_SERVER, profileId = 5))
        assertFocused("profile_5")
        press(Key.DirectionRight)
        assertFocused("profile_8")
        press(Key.DirectionCenter)
        assertEquals(1, api.count("POST /api/profiles/8/session"))
        assertTrue(api.calls.none { it.contains("unlock") })
        node("card_continue:film:12").assertIsDisplayed()
        assertFocused("card_continue:film:12")
    }

    @Test
    fun homeRightMovesAlongARowAndUpFromTheFirstRowReachesTheNavBar() {
        useLibrary()
        launch()
        assertFocused("card_continue:film:12")
        press(Key.DirectionRight)
        assertFocused("card_continue:episode:203")
        press(Key.DirectionLeft)
        press(Key.DirectionUp)
        assertFocused("tab_HOME")
        press(Key.DirectionDown)
        assertFocused("card_continue:film:12")
    }

    @Test
    fun homeBackFromADeepRowReturnsToTheFirstCardThenAsksToExit() {
        useLibrary()
        launch()
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionRight)
        back()
        assertFocused("card_continue:film:12")
        assertTrue(!exited)
        back()
        node("confirm_dialog").assertExists()
        assertTrue(!exited)
        press(Key.DirectionCenter)
        assertTrue(exited)
    }

    @Test
    fun backFromFilmDetailRestoresTheHomeCard() {
        useLibrary()
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertFocused("card_recent:film:13")
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
        back()
        assertFocused("card_recent:film:13")
    }

    @Test
    fun browseBackFromDetailRestoresTheCardAndBackReturnsToTheFilmsTab() {
        useLibrary()
        launch()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("grid_0")
        press(Key.DirectionRight, times = 2)
        assertFocused("grid_2")
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
        back()
        assertFocused("grid_2")
        back()
        assertFocused("tab_FILMS")
    }

    @Test
    fun aFailedBrowsePageShowsAnInlineRetryReachableWithDown() {
        useLibrary()
        var failPage2 = true
        api.onFilms = { p, s, _, _ ->
            if (p == 2 && failPage2) throw com.hometv.tv.core.data.FakeHomeTvApi.httpError(500, "internal_error")
            val all = (1L..120L).map { com.hometv.tv.core.api.FilmSummary(it, "Film $it", 2000) }
            com.hometv.tv.core.api.Page(all.drop((p - 1) * s).take(s), p, s, all.size)
        }
        launch()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("grid_0")
        press(Key.DirectionDown, times = 7)
        assertFocused("grid_42")
        val pages = api.calls.count { it.startsWith("GET /api/films?page=2") }
        press(Key.DirectionDown, times = 2)
        assertFocused("browse_retry")
        rule.onNodeWithText("No se han podido cargar más títulos.", useUnmergedTree = true).assertExists()
        assertEquals("no automatic retry on focus moves", pages, api.calls.count { it.startsWith("GET /api/films?page=2") })
        failPage2 = false
        press(Key.DirectionCenter)
        assertEquals(pages + 1, api.calls.count { it.startsWith("GET /api/films?page=2") })
        assertFocused("grid_50")
    }

    @Test
    fun searchFocusesTheFieldAndBackFromDetailRestoresTheResult() {
        useLibrary()
        launch()
        press(Key.DirectionUp)
        // Search is the lens at the left end of the bar, before Inicio.
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertFocused("search_field")
        node("search_field").performTextInput("dune")
        UiTestPlatform.advance(400)
        settle()
        assertEquals(1, api.calls.count { it.startsWith("GET /api/search?q=dune") })
        press(Key.DirectionDown)
        assertFocused("result_film:12")
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
        back()
        assertFocused("result_film:13")
        back()
        assertFocused("tab_SEARCH")
    }

    @Test
    fun showDetailRightEntersEpisodesAtNextEpisodeAndLeftReturnsToTheSeason() {
        useLibrary()
        launch()
        press(Key.DirectionDown)
        assertFocused("card_recent:show:7")
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
        // Down goes through `rating_mine` to the selected season (next_episode's, 2).
        press(Key.DirectionDown)
        assertFocused("rating_mine")
        press(Key.DirectionDown)
        assertFocused("season_2")
        UiTestPlatform.advance(300)
        settle()
        press(Key.DirectionRight)
        assertFocused("episode_203")
        press(Key.DirectionLeft)
        assertFocused("season_2")
        back()
        assertFocused("card_recent:show:7")
    }

    @Test
    fun episodeLongPressDialogReturnsFocusToTheEpisode() {
        useLibrary()
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        press(Key.DirectionDown, times = 2)
        UiTestPlatform.advance(300)
        settle()
        press(Key.DirectionRight)
        assertFocused("episode_203")
        rule.runOnIdle { }
        node("episode_203").performLongClickCompat(rule)
        settle()
        assertFocused("episode_play_from_start")
        back()
        assertFocused("episode_203")
    }

    @Test
    fun settingsOpensFromTheNavBarAndBackFocusesItsTab() {
        useLibrary()
        api.onMe = { Me(5, "Esther", "avatar-05", is_admin = true) }
        launch()
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 5)
        assertFocused("tab_SETTINGS")
        press(Key.DirectionCenter)
        // An admin's Settings starts on [Cambiar servidor].
        assertFocused("settings_change_server")
        back()
        assertFocused("tab_SETTINGS")
    }

    @Test
    fun aProfileThatFailedAtColdStartIsFetchedAgainOnceHomeLoads() {
        useLibrary()
        val home = api.onHome
        api.onHome = { throw java.net.ConnectException("refused") }
        api.onMe = { throw java.net.ConnectException("refused") }
        launch()
        assertTrue(rule.onAllNodes(hasTestTag("tab_SETTINGS"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // Tailscale is up now: [Reintentar] loads Home, and the admin's profile follows.
        api.onHome = home
        api.onMe = { Me(5, "Esther", "avatar-05", is_admin = true) }
        press(Key.DirectionCenter)
        node("card_continue:film:12").assertIsDisplayed()
        node("tab_SETTINGS").assertExists()
        node("nav_avatar").assertExists()
    }

    @Test
    fun aViewerHasNoSettingsTabAndRightFromActividadGoesToTheAvatar() {
        useLibrary() // the default test profile is a viewer
        launch()
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 2)
        assertFocused("tab_SHOWS")
        assertTrue("no Ajustes tab for a viewer", rule.onAllNodes(hasTestTag("tab_SETTINGS"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        rule.onAllNodesWithText("Ajustes", useUnmergedTree = true).assertCountEquals(0)
        press(Key.DirectionRight)
        assertFocused("tab_WATCHLIST")
        press(Key.DirectionRight)
        assertFocused("tab_ACTIVITY")
        press(Key.DirectionRight)
        assertFocused("nav_avatar")
    }
}
