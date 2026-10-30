package com.hometv.tv.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.Shadows.shadowOf

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

    /** Lines the overview was laid out on. */
    private fun overviewLines(): Int {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node("film_overview").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().lineCount
    }

    private val longOverview = List(12) { "En un lejano futuro, la galaxia conocida es gobernada por casas nobles." }.joinToString(" ")

    @Test
    fun aFilmInProgressShowsItsWholeHeaderWheneverTheButtonsHaveFocus() {
        // The Dune case: tagline + a long overview + four buttons on two rows
        // (Reanudar / Ver desde el principio / Marcar como visto, then Mi lista).
        // A TV (FEATURE_LEANBACK): Compose then scrolls focus into view with its pivot spec, as on the device.
        shadowOf(ApplicationProvider.getApplicationContext<Context>().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        useLibrary()
        api.onFilm = { id ->
            FilmDetail(
                id = id, title = "Dune", year = 2021, runtime_minutes = 155, match_status = "matched",
                tagline = "Más allá del miedo, el destino espera.", overview = longOverview,
                media_files = listOf(F.file(1)), cast = cast,
                progress = Progress(position_seconds = 38.0, duration_seconds = 9300.0, media_file_id = 1),
                in_watchlist = false,
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
        // watchlist prd3 FR-T8: with 4 or more buttons the overview is clamped to 3 lines.
        assertTrue("the overview takes ${overviewLines()} lines", overviewLines() <= 3)
        // prd3 §6 step 2 (amended): "Reanudar desde 0:38", "Ver desde el principio" and "Marcar como visto"
        // share the first row; "Mi lista" wraps onto the next one.
        rule.onNodeWithText("Ver desde el principio", useUnmergedTree = true).assertExists()
        val rows = listOf("button_RESUME", "button_PLAY_FROM_START", "button_WATCHED").map { node(it).fetchSemanticsNode().positionInRoot.y }
        assertTrue("the three buttons are on one row (tops at $rows px)", rows.distinct().size == 1)
        val watchlistTop = node("button_WATCHLIST").fetchSemanticsNode().positionInRoot.y
        assertTrue("Mi lista is on the next row ($watchlistTop px vs ${rows.first()} px)", watchlistTop > rows.first())
        assertFullyVisible("button_WATCHLIST")
        // prd3 §6 step 1: the whole cast row (photos, names, characters) is on screen too, nothing cut off.
        assertFullyVisible("cast_1:0")
        assertFullyVisible("cast_7:6")

        // Down goes row by row: "Mi lista" (below Reanudar), then the cast.
        press(Key.DirectionDown)
        assertFocused("button_WATCHLIST")
        assertEquals("page scroll with the focus on Mi lista", 0f, pageScroll(), 0.5f)
        press(Key.DirectionDown)
        assertFocused("cast_1:0")
        settle()
        // The whole page fits: entering the cast row does not scroll it (the title would leave the screen).
        assertEquals("page scroll with the focus on the first cast card", 0f, pageScroll(), 0.5f)
        assertFullyVisible("cast_1:0")
        assertFocusRingInsidePage("cast_1:0")
        press(Key.DirectionUp)
        assertFocused("button_RESUME")
        settle()
        assertEquals("page scroll back on Reanudar", 0f, pageScroll(), 0.5f)
    }

    @Test
    fun aFilmWithThreeButtonsKeepsTheFiveLineOverview() {
        useLibrary()
        api.onFilm = { id ->
            FilmDetail(
                id = id, title = "Dune", year = 2021, runtime_minutes = 155, match_status = "matched",
                overview = longOverview, media_files = listOf(F.file(1)), cast = cast,
            )
        }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
        listOf("button_PLAY", "button_WATCHED", "button_WATCHLIST").forEach { node(it).assertExists() }
        assertEquals(5, overviewLines())
    }

    /**
     * The focused card is drawn scaled by [HomeTvDimens.FOCUSED_SCALE] around its centre; the page's scroll
     * viewport (which stops at the bottom safe area) clips whatever is drawn below it, focus ring included.
     */
    private fun assertFocusRingInsidePage(tag: String) {
        val card = node(tag).fetchSemanticsNode()
        val density = card.layoutInfo.density.density
        val overhang = card.size.height * (HomeTvDimens.FOCUSED_SCALE - 1f) / 2f
        val drawnBottom = (card.positionInRoot.y + card.size.height + overhang) / density
        val page = node("film_detail_page").fetchSemanticsNode()
        val viewportBottom = (page.positionInRoot.y + page.size.height) / density
        assertTrue("'$tag' is drawn down to $drawnBottom dp, below the page viewport ($viewportBottom dp): the ring is cut off", drawnBottom <= viewportBottom + 0.5f)
    }

    @Test
    fun aTallHeaderScrollsThePageJustEnoughForTheWholeFocusRing() {
        // A two-line title and a long overview: the cast row starts below the viewport, so the page has to scroll.
        shadowOf(ApplicationProvider.getApplicationContext<Context>().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        useLibrary()
        val overview = List(12) { "En un lejano futuro, la galaxia conocida es gobernada por casas nobles." }.joinToString(" ")
        api.onFilm = { id ->
            FilmDetail(
                id = id, title = "Un título larguísimo que no cabe en una sola línea de la pantalla del televisor", year = 2021,
                runtime_minutes = 155, match_status = "matched", tagline = "Más allá del miedo, el destino espera.", overview = overview,
                media_files = listOf(F.file(1)), cast = cast,
                progress = Progress(position_seconds = 38.0, duration_seconds = 9300.0, media_file_id = 1),
            )
        }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
        // "Mi lista" wraps below Reanudar (watchlist prd3 FR-T8): Down reaches it first, then the cast.
        press(Key.DirectionDown)
        assertFocused("button_WATCHLIST")
        press(Key.DirectionDown)
        assertFocused("cast_1:0")
        settle()
        assertTrue("the page scrolled to reach the cast row", pageScroll() > 0f)
        assertFocusRingInsidePage("cast_1:0")
        press(Key.DirectionRight)
        settle()
        assertFocusRingInsidePage("cast_2:1")
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
