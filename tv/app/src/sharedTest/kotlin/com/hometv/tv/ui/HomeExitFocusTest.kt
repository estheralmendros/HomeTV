package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.HomeResponse
import com.hometv.tv.core.api.LibraryCounts
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.data.Session
import com.hometv.tv.testing.TEST_SERVER
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.ui.navigation.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back on Inicio, where it would close the app, asks "¿Salir de la aplicación?" [Salir] [Cancelar]
 * ("Salir" focused); "Cancelar"/Back keep Inicio as it was; "Salir" signs the profile out and exits.
 */
@RunWith(AndroidJUnit4::class)
class HomeExitFocusTest : UiTestBase() {
    private val firstCard = "card_continue:film:12"

    private fun textAbsent(text: String) = rule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    private fun x(tag: String) = node(tag).fetchSemanticsNode().positionInRoot.x

    private fun openDialogFromTheFirstCard() {
        useLibrary()
        launch()
        assertFocused(firstCard)
        back()
        node("confirm_dialog").assertExists()
    }

    @Test
    fun backOnTheFirstCardOpensTheDialogWithFocusOnSalir() {
        openDialogFromTheFirstCard()
        assertFalse(exited)
        rule.onNodeWithText("¿Salir de la aplicación?", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Salir", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Cancelar", useUnmergedTree = true).assertExists()
        assertFocused("confirm_dialog_confirm")
        assertTrue("[Salir] [Cancelar] in that order", x("confirm_dialog_confirm") < x("confirm_dialog_cancel"))
        assertEquals(node("confirm_dialog_confirm").fetchSemanticsNode().positionInRoot.y, node("confirm_dialog_cancel").fetchSemanticsNode().positionInRoot.y, 0.5f)
    }

    @Test
    fun leftAndRightMoveBetweenTheButtons() {
        openDialogFromTheFirstCard()
        press(Key.DirectionLeft)
        assertFocused("confirm_dialog_confirm")
        press(Key.DirectionRight)
        assertFocused("confirm_dialog_cancel")
        press(Key.DirectionRight)
        assertFocused("confirm_dialog_cancel")
        press(Key.DirectionUp)
        assertFocused("confirm_dialog_cancel")
        press(Key.DirectionDown)
        assertFocused("confirm_dialog_cancel")
        press(Key.DirectionLeft)
        assertFocused("confirm_dialog_confirm")
        assertFalse(exited)
    }

    @Test
    fun cancelarRestoresFocusOnTheFirstCard() {
        openDialogFromTheFirstCard()
        val calls = api.calls.size
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertTrue(absent("confirm_dialog"))
        assertFocused(firstCard)
        assertFalse(exited)
        assertEquals(signedIn, container.store.session.value)
        assertEquals(calls, api.calls.size)
    }

    @Test
    fun backClosesTheDialogAndFocusStays() {
        openDialogFromTheFirstCard()
        back()
        assertTrue(absent("confirm_dialog"))
        assertFocused(firstCard)
        assertFalse(exited)
        assertEquals(signedIn, container.store.session.value)
    }

    @Test
    fun aDeepCardStillGoesToTheFirstCardFirst() {
        useLibrary()
        launch()
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionRight)
        back()
        assertFocused(firstCard)
        assertTrue(absent("confirm_dialog"))
        back()
        assertFocused("confirm_dialog_confirm")
    }

    @Test
    fun backOnTheInicioTabReturnsToTheFirstCard() {
        useLibrary()
        launch()
        press(Key.DirectionUp)
        assertFocused("tab_HOME")
        back()
        assertFocused(firstCard)
        assertTrue(absent("confirm_dialog"))
        assertFalse(exited)
    }

    @Test
    fun backFromPeliculasReturnsToInicioWithoutTheDialog() {
        useLibrary()
        launch()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("grid_0")
        back()
        assertFocused("tab_FILMS")
        assertTrue(absent("confirm_dialog"))
        assertFalse(exited)
    }

    @Test
    fun backWhileLoadingOpensTheDialog() {
        useLibrary()
        val gate = CompletableDeferred<HomeResponse>()
        api.onHome = { gate.await() }
        try {
            launch()
            back()
            assertFocused("confirm_dialog_confirm")
            assertFalse(exited)
        } finally {
            gate.complete(HomeResponse())
        }
    }

    @Test
    fun backWithAnEmptyLibraryOpensTheDialog() {
        useLibrary()
        api.onHome = { HomeResponse(library = LibraryCounts(empty = true)) }
        api.onFilms = { p, s, _, _ -> Page(emptyList(), p, s, 0) }
        api.onShows = { p, s, _, _ -> Page(emptyList(), p, s, 0) }
        launch()
        back()
        assertFocused("confirm_dialog_confirm")
        assertFalse(exited)
    }

    @Test
    fun errorScreenBackOpensTheDialogAndCancelarFocusesReintentar() {
        useLibrary()
        api.onHome = { throw httpError(500, "internal_error") }
        launch()
        rule.onNodeWithText("Reintentar").assertIsFocused()
        back()
        assertFocused("confirm_dialog_confirm")
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertTrue(absent("confirm_dialog"))
        rule.onNodeWithText("Reintentar").assertIsFocused()
        assertFalse(exited)
    }

    @Test
    fun salirSignsOutAndExits() {
        openDialogFromTheFirstCard()
        assertNotNull("the Home load wrote the cache", runBlocking { container.homeCache.read(5) })
        val calls = api.calls.size
        press(Key.DirectionCenter)
        assertTrue(exited)
        assertEquals(Session(serverUrl = TEST_SERVER), container.store.session.value)
        assertEquals(Route.Profiles(), container.startDestination())
        assertNull(container.account.profile.value)
        assertEquals("no request after Salir: ${api.calls.drop(calls)}", calls, api.calls.size)
        assertNotNull("the Home cache survives Salir", runBlocking { container.homeCache.read(5) })
    }

    /**
     * A relaunch with the session shape «Salir» leaves (server URL only, as [salirSignsOutAndExits]
     * asserts); it launches with that session directly, since `setContent` runs once per test.
     */
    @Test
    fun aStartAfterSalirOpensThePickerOnTheFirstProfile() {
        useLibrary()
        launch(Session(serverUrl = TEST_SERVER))
        assertFocused("profile_3")
        assertTrue(textAbsent("Tu sesión ha caducado"))
    }
}
