package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.CastMember
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.playback.F
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.ui.theme.HomeTvDimens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** prd3 §6 step 1 (cast navigation): the cast row is reachable with the D-pad and scrolls through every member. */
@RunWith(AndroidJUnit4::class)
class FilmCastFocusTest : UiTestBase() {
    private val cast = (1L..12L).map { CastMember(person_id = it, name = "Actor $it", character = "Personaje $it") }

    private fun openFilmDetail(cast: List<CastMember> = this.cast) {
        useLibrary()
        api.onFilm = { id ->
            FilmDetail(id = id, title = "Film $id", year = 2021, runtime_minutes = 155, match_status = "matched", overview = "Una sinopsis.", media_files = listOf(F.file(1)), cast = cast)
        }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
    }

    /** The card (photo, name and character) is entirely on screen, above the bottom safe area. */
    private fun assertFullyVisible(tag: String) {
        val card = node(tag).fetchSemanticsNode()
        val density = card.layoutInfo.density.density
        val root = rule.onAllNodes(isRoot()).fetchSemanticsNodes().first().size
        val left = card.positionInRoot.x / density
        val top = card.positionInRoot.y / density
        val right = left + card.size.width / density
        val bottom = top + card.size.height / density
        val safeBottom = root.height / density - HomeTvDimens.SafeVertical.value
        assertTrue("'$tag' spans x $left..$right dp, outside the ${root.width / density} dp screen", left >= 0f && right <= root.width / density)
        assertTrue("'$tag' ends at $bottom dp, below the safe area ($safeBottom dp)", top >= 0f && bottom <= safeBottom + 0.5f)
    }

    @Test
    fun downReachesTheCastAndRightScrollsThroughEveryMember() {
        openFilmDetail()
        press(Key.DirectionDown)
        assertFocused("cast_1:0")
        assertFullyVisible("cast_1:0")
        press(Key.DirectionRight, times = 11)
        assertFocused("cast_12:11")
        assertFullyVisible("cast_12:11")
        press(Key.DirectionUp)
        assertFocused("button_PLAY")
    }

    /** How far the film detail page is scrolled, in px (0 = the header at the top). */
    private fun pageScroll(): Float =
        node("film_detail_page").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()

    @Test
    fun aFilmInProgressShowsItsWholeHeaderWheneverTheButtonsHaveFocus() {
        // The Dune case: tagline + a 5-line overview + three buttons on two rows
        // (Reanudar / Ver desde el principio / Marcar como visto).
        useLibrary()
        val overview = List(12) { "En un lejano futuro, la galaxia conocida es gobernada por casas nobles." }.joinToString(" ")
        api.onFilm = { id ->
            FilmDetail(
                id = id, title = "Dune", year = 2021, runtime_minutes = 155, match_status = "matched",
                tagline = "Más allá del miedo, el destino espera.", overview = overview,
                media_files = listOf(F.file(1)), cast = cast,
                progress = Progress(position_seconds = 38.0, duration_seconds = 9300.0, media_file_id = 1),
            )
        }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
        settle()
        // prd3 §6 step 1: while a header button has focus the page is at the top (title, poster and
        // buttons all on screen), not scrolled to show the cast row.
        assertEquals("page scroll with the focus on Reanudar", 0f, pageScroll(), 0.5f)
        // prd3 §6 step 2: "Reanudar desde 0:38", "Ver desde el principio" and "Marcar como visto" share one row.
        rule.onNodeWithText("Ver desde el principio", useUnmergedTree = true).assertExists()
        val rows = listOf("button_RESUME", "button_PLAY_FROM_START", "button_WATCHED").map { node(it).fetchSemanticsNode().positionInRoot.y }
        assertTrue("the three buttons are on one row (tops at $rows px)", rows.distinct().size == 1)
        // prd3 §6 step 1: the whole cast row (photos, names, characters) is on screen too, nothing cut off.
        assertFullyVisible("cast_1:0")
        assertFullyVisible("cast_7:6")

        press(Key.DirectionDown)
        assertFocused("cast_1:0")
        assertFullyVisible("cast_1:0")
        press(Key.DirectionUp)
        assertFocused("button_RESUME")
        settle()
        assertEquals("page scroll back on Reanudar", 0f, pageScroll(), 0.5f)
    }

    /** Unclipped left edge of the first node matching [matcher], in dp. */
    private fun left(matcher: androidx.compose.ui.test.SemanticsMatcher): Float {
        val node = rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().first()
        return node.positionInRoot.x / node.layoutInfo.density.density
    }

    @Test
    fun theRepartoTitleAndTheFirstCardStartAtThePageMargin() {
        openFilmDetail()
        val margin = HomeTvDimens.SafeHorizontal.value
        val title = left(androidx.compose.ui.test.hasText("Reparto"))
        val card = left(androidx.compose.ui.test.hasTestTag("cast_1:0"))
        assertTrue("'Reparto' starts at $title dp, not at the $margin dp margin", kotlin.math.abs(title - margin) <= 1f)
        assertTrue("the first cast card starts at $card dp, not at the $margin dp margin", kotlin.math.abs(card - margin) <= 1f)
    }

    @Test
    fun aPersonCreditedTwiceOpensTheFilmAndBothCardsAreReachable() {
        // TMDB can credit one person twice (two characters): the server keeps both credits.
        openFilmDetail(listOf(CastMember(7, "Actor 7", "Personaje A"), CastMember(8, "Actor 8", "B"), CastMember(7, "Actor 7", "Personaje C")))
        press(Key.DirectionDown)
        assertFocused("cast_7:0")
        press(Key.DirectionRight, times = 2)
        assertFocused("cast_7:2")
    }
}
