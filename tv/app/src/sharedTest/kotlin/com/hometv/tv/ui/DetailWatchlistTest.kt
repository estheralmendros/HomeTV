package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.CastMember
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.playback.F
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.ui.detail.ButtonId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The "Mi lista" button of Film and Show detail, its inline failure line and the action row's fit. */
@RunWith(AndroidJUnit4::class)
class DetailWatchlistTest : UiTestBase() {
    private fun openFilm() {
        useLibrary()
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertFocused("card_recent:film:13")
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
    }

    private fun openShow() {
        useLibrary()
        launch()
        press(Key.DirectionDown)
        assertFocused("card_recent:show:7")
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
    }

    /** The button's visible label (its text node). */
    private fun assertLabel(tag: String, text: String) {
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertExists()
    }

    @Test
    fun onFilmDetailTheButtonFollowsWatchedAndOkFlipsItInPlace() {
        openFilm()
        press(Key.DirectionRight)
        assertFocused("button_WATCHED")
        press(Key.DirectionRight)
        assertFocused("button_WATCHLIST")
        assertLabel("button_WATCHLIST", "Mi lista")
        node("button_WATCHLIST").assert(hasContentDescription("Añadir a Mi lista"))

        press(Key.DirectionCenter)
        assertFocused("button_WATCHLIST")
        assertLabel("button_WATCHLIST", "En Mi lista")
        node("button_WATCHLIST").assert(hasContentDescription("Quitar de Mi lista"))
        assertEquals(1, api.count("PUT /api/watchlist/films/13"))
        assertTrue("no success message", rule.onAllNodes(hasTestTag("watchlist_error"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        press(Key.DirectionCenter)
        assertFocused("button_WATCHLIST")
        assertLabel("button_WATCHLIST", "Mi lista")
        assertEquals(1, api.count("DELETE /api/watchlist/films/13"))
    }

    @Test
    fun aFailedWriteShowsTheInlineLineUnderTheButtonsAndKeepsFocus() {
        api.onWatchlistWrite = { _, _, _ -> throw httpError(500, "internal_error") }
        openFilm()
        press(Key.DirectionRight, times = 2)
        press(Key.DirectionCenter)
        assertFocused("button_WATCHLIST")
        assertLabel("button_WATCHLIST", "Mi lista")
        rule.onNode(hasTestTag("watchlist_error") and hasText("No se ha podido actualizar Mi lista."), useUnmergedTree = true).assertExists()
        assertFullyVisible("watchlist_error")
        // The next action clears it.
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertTrue(rule.onAllNodes(hasTestTag("watchlist_error"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun onShowDetailTheButtonFollowsWatchedAndTheRowFits() {
        openShow()
        press(Key.DirectionRight)
        assertFocused("button_WATCHED")
        press(Key.DirectionRight)
        assertFocused("button_WATCHLIST")
        node("button_WATCHLIST").assert(hasContentDescription("Añadir a Mi lista"))
        listOf("button_RESUME", "button_WATCHED", "button_WATCHLIST").forEach { assertInsideSafeArea(it) }
        press(Key.DirectionCenter)
        assertFocused("button_WATCHLIST")
        assertLabel("button_WATCHLIST", "En Mi lista")
        assertEquals(1, api.count("PUT /api/watchlist/shows/7"))
    }

    @Test
    fun aFailedShowWriteShowsTheInlineLineInsideTheSafeArea() {
        api.onWatchlistWrite = { _, _, _ -> throw java.net.ConnectException("refused") }
        openShow()
        press(Key.DirectionRight, times = 2)
        press(Key.DirectionCenter)
        assertFocused("button_WATCHLIST")
        rule.onNode(hasTestTag("watchlist_error") and hasText("No se ha podido actualizar Mi lista."), useUnmergedTree = true).assertExists()
        assertInsideSafeArea("watchlist_error")
    }

    @Test
    fun theWidestFilmRowKeepsEveryButtonInsideTheSafeAreaWhenFocused() {
        // The widest row: one standalone file + two parts with two versions each, progress on a part:
        // Reanudar / Ver desde el principio / Marcar como visto / Mi lista / Versión / Parte / Versión de la parte.
        useLibrary()
        val overview = List(12) { "En un lejano futuro, la galaxia conocida es gobernada por casas nobles." }.joinToString(" ")
        api.onFilm = { id ->
            FilmDetail(
                id = id, title = "Un título larguísimo que no cabe en una sola línea de la pantalla del televisor", year = 2021,
                runtime_minutes = 155, match_status = "matched", tagline = "Más allá del miedo, el destino espera.", overview = overview,
                media_files = listOf(
                    F.file(1, label = "2160p H264 · EAC3 5.1"),
                    F.part(11, 1, label = "1080p H264 · AC3 5.1"), F.part(12, 1, label = "720p H264 · AAC 2.0"),
                    F.part(21, 2, label = "1080p H264 · AC3 5.1"), F.part(22, 2, label = "720p H264 · AAC 2.0"),
                ),
                progress = F.progress(11, position = 600.0, duration = 3000.0),
                cast = (1L..8L).map { CastMember(it, "Actor $it", "Personaje $it") },
            )
        }
        api.onWatchlistWrite = { _, _, _ -> throw java.net.ConnectException("refused") }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
        val ids = listOf(
            ButtonId.RESUME, ButtonId.PLAY_FROM_START, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.VERSION, ButtonId.PART, ButtonId.PART_VERSION,
        )
        ids.forEach { node("button_${it.name}").assertExists() }
        assertTrue("the overview is clamped to 3 lines", overviewLines() <= 3)
        assertInsideSafeArea("button_RESUME")
        // Right walks the buttons in row order, across the wrapped rows (Right from WATCHED is WATCHLIST).
        ids.drop(1).forEach { id ->
            press(Key.DirectionRight)
            assertFocused("button_${id.name}")
            assertInsideSafeArea("button_${id.name}")
        }
        press(Key.DirectionLeft, times = 3)
        assertFocused("button_WATCHLIST")
        press(Key.DirectionCenter)
        assertFocused("button_WATCHLIST")
        assertInsideSafeArea("watchlist_error")
        assertInsideSafeArea("button_WATCHLIST")
    }

    private fun overviewLines(): Int {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node("film_overview").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().lineCount
    }
}
