package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.testing.UiTestBase
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The video surface follows the lazily created controller (prd.md FR-3.5, AC-3.16) and the container
 * builds no player before the first attach (AC-3.11b).
 */
@RunWith(AndroidJUnit4::class)
class PlayerSurfaceTest : UiTestBase() {
    private val direct = PlaybackResponse("direct", url = "/api/media-files/1/stream", audio_stream_index = 1, duration_seconds = 9360.0)

    /** Home → OK on "Seguir viendo" Dune → Player; the playback answer waits for [gate]. */
    private fun openFilm(gate: CompletableDeferred<Unit>) {
        useLibrary()
        api.onFilm = { id -> FilmDetail(id = id, title = "Dune", media_files = listOf(F.file(1))) }
        api.onPlayback = { _, _ ->
            gate.await()
            direct
        }
        launch()
        assertFocused("card_continue:film:12")
        press(Key.DirectionCenter)
        node("player").assertIsDisplayed()
    }

    @Test
    fun theSurfaceBindsTheExoControllerCreatedAtTheFirstAttach() {
        val gate = CompletableDeferred<Unit>()
        openFilm(gate)
        assertEquals("no player before the answer", 0, playerBuilds)
        gate.complete(Unit)
        settle()
        onPlayer { ready() }
        assertEquals(1, playerBuilds)
        val last = surfaceBindings.last()
        assertEquals(PlaybackEngine.EXO, last.first)
        assertSame(player, last.second)
    }

    @Test
    fun backWhileLoadingBuildsNoPlayer() {
        val gate = CompletableDeferred<Unit>()
        openFilm(gate)
        back()
        assertTrue(absent("player"))
        gate.complete(Unit)
        settle()
        assertEquals(0, playerBuilds)
        assertTrue(surfaceBindings.all { it.second == null })
    }
}
