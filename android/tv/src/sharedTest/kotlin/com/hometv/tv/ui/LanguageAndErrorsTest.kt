package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.testing.UiTestBase
import java.net.ConnectException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spanish catalog texts and es-ES formatting whatever the
 * system language (the JVM variant `EnglishSystemLanguageTest` re-runs this class in en-US), the
 * Connection Error screen, the expired-session path and a removed title.
 */
@RunWith(AndroidJUnit4::class)
open class LanguageAndErrorsTest : UiTestBase() {
    private fun text(value: String) = rule.onNodeWithText(value, useUnmergedTree = true)

    @Test
    fun homeAndFilmDetailShowSpanishTextsAndEsEsFormatting() {
        useLibrary()
        launch()
        text("Seguir viendo").assertIsDisplayed()
        text("Inicio").assertIsDisplayed()
        // The default test profile is a viewer: "Ajustes" is admin-only (TopNavFocusTest).
        rule.onAllNodesWithText("Ajustes", useUnmergedTree = true).assertCountEquals(0)
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        text("Reproducir").assertIsDisplayed()
        text("Marcar como visto").assertIsDisplayed()
        text("2021 · 2 h 35 min").assertIsDisplayed()
        text("Reparto").assertIsDisplayed()
    }

    @Test
    fun anUnreachableServerShowsTheConnectionErrorAndRetryReloads() {
        useLibrary()
        val home = api.onHome
        api.onHome = { throw ConnectException("refused") }
        launch()
        text("No se puede conectar con tu servidor HomeTV en hometv.tail1234.ts.net. Puede que Tailscale esté desconectado; suele pasar después de reiniciar el televisor.").assertIsDisplayed()
        assertTrue("no [Cambiar servidor] on the Connection Error", rule.onAllNodes(androidx.compose.ui.test.hasText("Cambiar servidor")).fetchSemanticsNodes().isEmpty())
        assertTrue("Tailscale is not installed: no [Abrir Tailscale]", rule.onAllNodes(androidx.compose.ui.test.hasText("Abrir Tailscale")).fetchSemanticsNodes().isEmpty())
        api.onHome = home
        press(Key.DirectionCenter)
        text("Seguir viendo").assertIsDisplayed()
    }

    @Test
    fun aGoneProfileLeadsToThePickerWithTheExpiredBanner() {
        useLibrary()
        api.onHome = { throw httpError(401, "unauthorized") }
        api.onCreateSession = { throw httpError(404, "profile_not_found") }
        launch()
        text("Tu sesión ha caducado. Vuelve a elegir tu perfil.").assertIsDisplayed()
        text("¿Quién está viendo?").assertIsDisplayed()
        assertEquals(1, api.count("POST /api/profiles/5/session"))
        assertEquals(null, container.store.session.value.token)
    }

    @Test
    fun aRemovedTitleSaysSoAndItsCardLeavesHome() {
        useLibrary()
        launch()
        api.onFilm = { throw httpError(404, "not_found") }
        api.onHome = { com.hometv.tv.core.api.HomeResponse(continue_watching = listOf(dune, olympics), recently_added = listOf(office)) }
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        text("Este título ya no está disponible.").assertIsDisplayed()
        press(Key.DirectionCenter)
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("card_recent:film:13")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun playingAnEpisodeOpensThePlayerRouteAndBackReturnsToTheCard() {
        useLibrary()
        api.onEpisode = { kotlinx.coroutines.awaitCancellation() }
        api.onPlayback = { _, _ -> kotlinx.coroutines.awaitCancellation() }
        launch()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        text("Preparando el vídeo…").assertIsDisplayed()
        // The Home payload's media_files: POST .../playback is sent without waiting for GET /api/episodes/{id}.
        assertTrue(api.calls.contains("POST /api/media-files/2/playback"))
        back()
        assertFocused("card_continue:episode:203")
    }
}
