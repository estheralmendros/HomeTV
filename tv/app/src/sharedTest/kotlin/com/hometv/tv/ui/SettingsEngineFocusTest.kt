package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.Profile
import com.hometv.tv.testing.UiTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ajustes → "Reproducción" (the admin switch "Usar siempre VLC (experimental)", shown only with libVLC) and
 * Acerca de → [Licencias de código abierto] (prd3 FR-13, FR-14).
 */
@RunWith(AndroidJUnit4::class)
class SettingsEngineFocusTest : UiTestBase() {
    private var vlc = true

    override val vlcAvailable: Boolean get() = vlc

    /** A notice longer than the dialog, so scrolling to its end is observable. */
    override val notices: String = (listOf("THIRD PARTY NOTICES") + (1..200).map { "Line $it of the notice" }).joinToString("\n")

    private fun openSettingsAsAdmin() {
        useLibrary()
        api.onMe = { Profile(5, "Esther", "avatar-05", is_admin = true) }
        launch()
        // Inicio → Películas → Series → Mi lista → Actividad → Ajustes.
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 5)
        press(Key.DirectionCenter)
        assertFocused("settings_change_server")
    }

    @Test
    fun downWalksServerProfileForceVlcThenLicencesAndOkTogglesInPlace() {
        openSettingsAsAdmin()
        rule.onNodeWithText("Reproducción", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Solo para pruebas: en este televisor, todos los perfiles reproducen los vídeos con VLC a partir de la próxima reproducción.", useUnmergedTree = true).assertExists()
        press(Key.DirectionDown)
        assertFocused("settings_switch_profile")
        press(Key.DirectionDown)
        assertFocused("settings_force_vlc")
        node("settings_force_vlc_value").assertTextEquals("Desactivado")

        press(Key.DirectionCenter)

        node("settings_force_vlc_value").assertTextEquals("Activado")
        assertTrue(enginePrefs.forceVlc.value)
        assertFocused("settings_force_vlc")

        press(Key.DirectionDown)
        assertFocused("settings_licenses")
        assertFullyVisible("settings_licenses")

        // Up reverses the order.
        press(Key.DirectionUp)
        assertFocused("settings_force_vlc")
        press(Key.DirectionUp)
        assertFocused("settings_switch_profile")
        press(Key.DirectionUp)
        assertFocused("settings_change_server")
    }

    @Test
    fun withoutLibVlcThereIsNoPlaybackSectionAndDownGoesFromProfileToLicences() {
        vlc = false
        openSettingsAsAdmin()
        assertTrue(absent("settings_force_vlc"))
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("Reproducción"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        press(Key.DirectionDown)
        assertFocused("settings_switch_profile")
        press(Key.DirectionDown)
        assertFocused("settings_licenses")
    }

    @Test
    fun theLicenceDialogShowsTheNoticeScrollsToItsEndAndBackReturnsToTheButton() {
        openSettingsAsAdmin()
        press(Key.DirectionDown, times = 3)
        assertFocused("settings_licenses")

        press(Key.DirectionCenter)

        val text = rule.onNode(hasAnyAncestor(hasTestTag("licenses_text")) and androidx.compose.ui.test.hasText("THIRD PARTY NOTICES", substring = true), useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue(text.startsWith("THIRD PARTY NOTICES"))
        assertFocused("licenses_text")
        val range = { node("licenses_text").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange] }
        assertEquals(0f, range().value(), 0f)
        assertTrue("the notice is longer than the dialog", range().maxValue() > 0f)
        assertFalse(range().value() >= range().maxValue())

        var presses = 0
        while (range().value() < range().maxValue() && presses < 60) {
            press(Key.DirectionDown)
            presses++
        }
        assertEquals("Down scrolls to the last line", range().maxValue(), range().value(), 0.5f)
        assertTrue("several pages", presses > 1)

        back()
        assertTrue(absent("licenses_text"))
        assertFocused("settings_licenses")
    }
}
