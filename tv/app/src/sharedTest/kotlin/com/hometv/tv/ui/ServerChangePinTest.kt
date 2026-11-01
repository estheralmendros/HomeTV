package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.api.UnlockResponse
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.testing.UiTestPlatform
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** prd3 §11 step 3: [Cambiar servidor] for admin profiles only, behind the admin PIN (asked every time). */
@RunWith(AndroidJUnit4::class)
class ServerChangePinTest : UiTestBase() {
    private companion object {
        const val DIALOG_WINDOW_INSETS = 80f
    }

    private fun openSettings() {
        // Inicio → Películas → Series → Mi lista → Actividad → Ajustes (Search is the lens left of Inicio).
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 5)
        press(Key.DirectionCenter)
    }

    private fun absent(tag: String) = rule.onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    private fun signInAsAdmin() {
        api.onMe = { Profile(5, "Esther", "avatar-05", is_admin = true) }
    }

    @Test
    fun aViewerCannotReachTheServerChange() {
        useLibrary()
        launch()
        // A viewer has no "Ajustes" tab (prd3 §3 step 1): the step that reaches it for an admin lands on
        // the avatar, so there is no way to [Cambiar servidor].
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 5)
        assertFocused("nav_avatar")
        assertTrue("no Ajustes tab for a viewer", absent("tab_SETTINGS"))
        assertTrue("no [Cambiar servidor] for a viewer", absent("settings_change_server"))
    }

    @Test
    fun aProfileDemotedWhileSettingsOpensLeavesForHomeWithTheInicioPill() {
        useLibrary()
        signInAsAdmin()
        launch()
        // Demoted on the server: the `/api/me` refresh of Settings answers a viewer (prd3 §11 intro).
        api.onMe = { Profile(5, "Esther", "avatar-05") }
        openSettings()
        assertTrue("a viewer has no Settings screen", absent("settings_switch_profile"))
        assertTrue("no Ajustes tab any more", absent("tab_SETTINGS"))
        node("card_continue:film:12").assertIsDisplayed()
        // Back on Home with focus on "Inicio"; Down returns to the rows.
        assertFocused("tab_HOME")
        press(Key.DirectionDown)
        assertFocused("card_continue:film:12")
    }

    @Test
    fun anAdminReachesSetupOnlyAfterTheRightPin() {
        useLibrary()
        signInAsAdmin()
        api.onUnlock = { pin -> if (pin == "1234") UnlockResponse("adm", "2026-10-23T18:00:00Z") else throw httpError(401, "invalid_pin") }
        launch()
        openSettings()
        assertFocused("settings_change_server")
        press(Key.DirectionCenter)
        node("pin_dialog").assertIsDisplayed()
        rule.onNodeWithText("Introduce el PIN de administrador", useUnmergedTree = true).assertIsDisplayed()
        assertFocused("pin_key_1")

        // A wrong PIN: the message, still in the dialog.
        press(Key.Zero, times = 4)
        rule.onNodeWithText("PIN incorrecto.", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(absent("setup_url"))

        // The keypad by D-pad: 1, 2, 3 on the first row, then 4 below 1.
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionLeft, times = 2)
        press(Key.DirectionCenter)
        node("setup_url").assertIsDisplayed()
        assertTrue(api.calls.count { it == "POST /api/admin/unlock" } == 2)

        // Setup from Settings: Back returns to Settings, and the PIN is asked again next time.
        back()
        assertFocused("settings_change_server")
        press(Key.DirectionCenter)
        node("pin_dialog").assertIsDisplayed()
    }

    @Test
    fun thePinDialogFitsInsideTheSafeArea() {
        useLibrary()
        signInAsAdmin()
        launch()
        openSettings()
        press(Key.DirectionCenter)
        val dialog = node("pin_dialog").fetchSemanticsNode()
        val density = dialog.layoutInfo.density.density
        val height = dialog.size.height / density
        val screen = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().first().size.height / density
        // Robolectric does not model a TV's dialog-window insets (they added ~77 dp on the emulator), so
        // the content must leave that much room inside the 27 dp vertical safe area.
        val room = screen - 2 * com.hometv.tv.ui.theme.HomeTvDimens.SafeVertical.value - DIALOG_WINDOW_INSETS
        assertTrue("the PIN dialog is $height dp tall; it must stay within $room dp", height <= room)
    }

    @Test
    fun backClosesThePinDialog() {
        useLibrary()
        signInAsAdmin()
        launch()
        openSettings()
        press(Key.DirectionCenter)
        node("pin_dialog").assertIsDisplayed()
        back()
        assertTrue(absent("pin_dialog"))
        assertFocused("settings_change_server")
    }

    @Test
    fun theLockoutCountdownShows() {
        useLibrary()
        signInAsAdmin()
        api.onUnlock = { throw httpError(429, "too_many_attempts", """{"retry_after_seconds": 42}""", retryAfter = "42") }
        launch()
        openSettings()
        press(Key.DirectionCenter)
        press(Key.Zero, times = 4)
        assertTrue(rule.onAllNodes(hasText("Demasiados intentos. Vuelve a intentarlo en 0:4", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theKeypadIsDisabledWhileLockedAndKeepsFocus() {
        useLibrary()
        signInAsAdmin()
        api.onUnlock = { throw httpError(429, "too_many_attempts", """{"retry_after_seconds": 2}""", retryAfter = "2") }
        launch()
        openSettings()
        press(Key.DirectionCenter)
        node("pin_key_1").assertIsEnabled()
        press(Key.Zero, times = 4)
        // prd3 §11 step 3: "keypad disabled" during the lockout, and visibly so; focus stays in the dialog.
        listOf("pin_key_1", "pin_key_0", "pin_key_delete").forEach { node(it).assertIsNotEnabled() }
        assertFocused("pin_key_1")
        UiTestPlatform.advance(3_000)
        settle()
        node("pin_key_1").assertIsEnabled()
    }
}
