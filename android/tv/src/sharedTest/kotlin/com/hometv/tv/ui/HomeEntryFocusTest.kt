package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.HomeResponse
import com.hometv.tv.testing.UiTestBase
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home focus on entry (decision B of `specs/tv-improvements/idea.md`): coming back to the app focuses
 * the first card of the fresh rows; coming back from a detail screen restores the card that opened it.
 */
@RunWith(AndroidJUnit4::class)
class HomeEntryFocusTest : UiTestBase() {
    private val arrival = FilmSummary(40, "Arrival", 2016)

    /** The user leaves the app (TV Home button) and comes back to it later. */
    private fun leaveAndReturn() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        settle()
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
    }

    @Test
    fun comingBackToTheAppFocusesTheNewestCard() {
        api.onHome = { HomeResponse(recently_added = listOf(office, alien)) }
        useLibraryRows()
        launch()
        assertFocused("card_recent:show:7")
        press(Key.DirectionRight)
        assertFocused("card_recent:film:13")
        // A film was added while the app was in the background.
        api.onHome = { HomeResponse(recently_added = listOf(arrival, office, alien)) }
        leaveAndReturn()
        assertFocused("card_recent:film:40")
    }

    @Test
    fun leavingTheAppFromADetailStillRestoresTheCardOnBack() {
        api.onHome = { HomeResponse(recently_added = listOf(office, alien)) }
        useLibraryRows()
        launch()
        press(Key.DirectionRight)
        assertFocused("card_recent:film:13")
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
        leaveAndReturn()
        back()
        assertFocused("card_recent:film:13")
    }

    /** [useLibrary]'s films, shows and details, keeping this test's Home. */
    private fun useLibraryRows() {
        val home = api.onHome
        useLibrary()
        api.onHome = home
    }
}
