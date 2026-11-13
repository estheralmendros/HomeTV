package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.testing.UiTestPlatform
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Holding OK: the long press acts on its own; the release that follows lands on whatever got focus
 * after it (the detail's first button, the episode dialog's first button) and must not click it.
 */
@RunWith(AndroidJUnit4::class)
class LongPressReleaseTest : UiTestBase() {
    @Test
    fun holdingOkOnAContinueCardOpensTheDetailWithoutPressingItsFirstButton() {
        useLibrary()
        launch()
        assertFocused("card_continue:film:12")
        holdOk()
        assertFocused("button_PLAY")
        // Still on the detail: D-pad moves work as usual.
        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        assertFocused("button_PLAY")
    }

    @Test
    fun holdingOkOnAnEpisodeOpensItsActionsWithoutChoosingTheFirstOne() {
        useLibrary()
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        press(Key.DirectionDown, times = 2)
        UiTestPlatform.advance(300)
        settle()
        press(Key.DirectionRight)
        assertFocused("episode_203")
        holdOk()
        assertFocused("episode_play_from_start")
        assertTrue("the dialog closed", !absent("episode_actions"))
        // Both choices stay reachable.
        press(Key.DirectionRight)
        assertFocused("episode_toggle_watched")
    }

    /** A TV remote over HDMI-CEC never repeats: the hold is one down and a release ~550 ms later. */
    @Test
    fun holdingOkOverHdmiOnAContinueCardOpensTheDetail() {
        useLibrary()
        launch()
        assertFocused("card_continue:film:12")
        holdOkOverHdmi()
        assertTrue("the player opened", absent("player"))
        assertFocused("button_PLAY")
        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        assertFocused("button_PLAY")
    }

    @Test
    fun holdingOkOverHdmiOnAnEpisodeOpensItsActions() {
        useLibrary()
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        press(Key.DirectionDown, times = 2)
        UiTestPlatform.advance(300)
        settle()
        press(Key.DirectionRight)
        assertFocused("episode_203")
        holdOkOverHdmi()
        assertTrue("the player opened", absent("player"))
        assertFocused("episode_play_from_start")
        press(Key.DirectionRight)
        assertFocused("episode_toggle_watched")
    }

    /** The release of that hold went to the dialog, never back to the episode: its next tap is still a click. */
    @Test
    fun afterAnHdmiHoldOpenedTheActionsATapOnTheEpisodePlays() {
        useLibrary()
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        press(Key.DirectionDown, times = 2)
        UiTestPlatform.advance(300)
        settle()
        press(Key.DirectionRight)
        holdOkOverHdmi()
        assertFocused("episode_play_from_start")
        back()
        assertFocused("episode_203")
        holdOkOverHdmi(heldMs = 150)
        node("player").assertIsDisplayed()
    }

    @Test
    fun aQuickTapOverHdmiOnAContinueCardStillPlays() {
        useLibrary()
        launch()
        assertFocused("card_continue:film:12")
        holdOkOverHdmi(heldMs = 150)
        node("player").assertIsDisplayed()
    }

    @Test
    fun aShortPressStillClicks() {
        useLibrary()
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertFocused("card_recent:film:13")
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
    }
}
