package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.CastMember
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.HouseholdAverage
import com.hometv.tv.core.api.OtherRating
import com.hometv.tv.core.api.ProfileRef
import com.hometv.tv.core.playback.F
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.ui.detail.ButtonId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native graphics, real text metrics): the ratings block fits Film and
 * Show detail at 960×540 dp in the widest cases; long names are ellipsized, never the stars or
 * "y N más"; Show keeps one full episode row.
 */
@RunWith(AndroidJUnit4::class)
class DetailRatingsFitTest : UiTestBase() {
    private val longNames = listOf("Maximiliano Alejandro", "Bartolomé Esteban Murillo", "Ana", "Luis", "Kids")
    private val others = longNames.mapIndexed { i, name -> OtherRating(ProfileRef(i + 1L, name, "avatar-0${i + 1}"), 4.5 - i * 0.5, "2026-10-3${i}T10:00:00Z") }
    private val overview = List(12) { "En un lejano futuro, la galaxia conocida es gobernada por casas nobles." }.joinToString(" ")

    private fun widthDp(tag: String): Float {
        val n = node(tag).fetchSemanticsNode()
        return n.size.width / n.layoutInfo.density.density
    }

    private fun heightDp(tag: String): Float {
        val n = node(tag).fetchSemanticsNode()
        return n.size.height / n.layoutInfo.density.density
    }

    private fun assertBlockFits() {
        assertFullyVisible("rating_mine")
        assertFullyVisible("rating_more")
        assertInsideSafeArea("rating_mine")
        assertInsideSafeArea("rating_more")
        assertFullyVisible("rating_other_0")
        assertFullyVisible("rating_other_1")
        assertTrue("names are capped at 140 dp", widthDp("rating_other_0") <= 140.5f && widthDp("rating_other_1") <= 140.5f)
        assertTrue("the block is at most two 40 dp lines 8 dp apart (${heightDp("rating_block")} dp)", heightDp("rating_block") <= 88.5f)
    }

    @Test
    fun theWidestFilmHeaderKeepsTheBlockOnScreen() {
        useLibrary()
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
                my_rating = 4.5, ratings = others, household_average = HouseholdAverage(3.5, 6),
            )
        }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
        listOf(ButtonId.RESUME, ButtonId.PLAY_FROM_START, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.VERSION, ButtonId.PART).forEach { node("button_${it.name}").assertExists() }
        assertBlockFits()
        assertFullyVisible("rating_average")
        // Down from the last button row reaches the block with the page still at the top.
        press(Key.DirectionRight, times = 6)
        assertFocused("button_PART_VERSION")
        press(Key.DirectionDown)
        assertFocused("rating_mine")
        assertBlockFits()
        press(Key.DirectionDown)
        assertFocused("rating_more")
        assertBlockFits()
    }

    @Test
    fun theWidestShowHeaderKeepsOneFullEpisodeRow() {
        useLibrary()
        val base = api.onShow
        api.onShow = { id -> base(id).copy(overview = overview, genres = listOf(com.hometv.tv.core.api.Genre(1, "Comedia")), my_rating = 4.0, ratings = others, household_average = HouseholdAverage(3.5, 6)) }
        api.onWatchlistWrite = { _, _, _ -> throw java.net.ConnectException("refused") }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
        press(Key.DirectionRight, times = 2)
        assertFocused("button_WATCHLIST")
        press(Key.DirectionCenter)
        node("watchlist_error").assertExists()
        assertBlockFits()
        assertFullyVisible("episode_201")
        assertInsideSafeArea("season_watched")
        press(Key.DirectionDown)
        assertFocused("rating_mine")
        assertBlockFits()
        assertFullyVisible("episode_201")
    }
}
