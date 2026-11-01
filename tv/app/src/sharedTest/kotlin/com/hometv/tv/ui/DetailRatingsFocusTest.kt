package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.CastMember
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.HouseholdAverage
import com.hometv.tv.core.api.OtherRating
import com.hometv.tv.core.api.ProfileRef
import com.hometv.tv.core.api.SeasonSummary
import com.hometv.tv.core.api.ShowDetail
import com.hometv.tv.core.data.RatingKind
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.util.Stars
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.testing.UiTestPlatform
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ratings prd3 FR-T11/T12 / T-AC-10..12: the detail ratings block's content, D-pad paths and focus restores. */
@RunWith(AndroidJUnit4::class)
class DetailRatingsFocusTest : UiTestBase() {
    private fun other(id: Long, name: String, stars: Double) = OtherRating(ProfileRef(id, name, "avatar-0$id"), stars, "2026-10-3${id % 10}T10:00:00Z")

    private val three = listOf(other(1, "Ana", 4.5), other(2, "Luis", 2.0), other(3, "Kids", 3.0))
    private var others = three

    private fun openFilm(mine: Double? = 4.0) {
        useLibrary()
        api.onFilm = { id ->
            FilmDetail(
                id = id, title = "Dune", year = 2021, match_status = "matched", media_files = listOf(F.file(1)), cast = listOf(CastMember(1, "Timothée", "Paul")),
                my_rating = mine, ratings = others, household_average = if (others.isEmpty()) null else HouseholdAverage(3.5, others.size + 1),
            )
        }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
    }

    private fun openShow() {
        useLibrary()
        val base = api.onShow
        api.onShow = { id -> base(id).copy(my_rating = 3.0, ratings = others, household_average = HouseholdAverage(3.3, others.size + 1)) }
        launch()
        press(Key.DirectionDown)
        assertFocused("card_recent:show:7")
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
    }

    private fun absent(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    private fun assertText(tag: String, text: String) {
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertExists()
    }

    @Test
    fun theFilmBlockShowsMineTheAverageTwoOthersAndTheRest() {
        openFilm()
        node("rating_mine").assert(hasContentDescription("Tu puntuación: 4 estrellas. Cambiar"))
        assertText("rating_average", "Media de la familia")
        assertText("rating_average", "3,5 (4)")
        node("rating_other_0").assert(hasText("Ana"))
        node("rating_other_1").assert(hasText("Luis"))
        assertTrue("two others at most", absent("rating_other_2"))
        assertText("rating_more", "y 1 más")
    }

    @Test
    fun nothingForOthersAndPuntuarWhenUnrated() {
        others = emptyList()
        openFilm(mine = null)
        node("rating_mine").assert(hasContentDescription("Puntuar"))
        assertText("rating_mine", "Puntuar")
        assertTrue(absent("rating_average"))
        assertTrue(absent("rating_other_0"))
        assertTrue(absent("rating_more"))
    }

    @Test
    fun filmDownUpPathsAndThePuntuacionesList() {
        openFilm()
        press(Key.DirectionDown)
        assertFocused("rating_mine")
        press(Key.DirectionRight)
        assertFocused("rating_mine")
        press(Key.DirectionDown)
        assertFocused("rating_more")
        press(Key.DirectionUp)
        assertFocused("rating_mine")
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        node("list_dialog").assertExists()
        assertFocused("dialog_item_0")
        assertText("dialog_item_0", "Ana · 4,5 estrellas")
        assertText("dialog_item_2", "Kids · 3 estrellas")
        back()
        assertTrue(absent("list_dialog"))
        assertFocused("rating_more")
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter) // OK on an item only closes the list
        assertFocused("rating_more")
        press(Key.DirectionDown)
        assertFocused("cast_1:0")
        press(Key.DirectionUp)
        assertFocused("rating_mine")
        press(Key.DirectionUp)
        assertFocused("button_PLAY")
        assertTrue("no version was picked", absent("button_VERSION"))
    }

    @Test
    fun closingTheRatingDialogFocusesRatingMine() {
        openFilm()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertFocused("rating_stars")
        back()
        assertFocused("rating_mine")
    }

    @Test
    fun closingTheListAfterARefetchRemovedYNMasFocusesRatingMine() {
        openFilm()
        press(Key.DirectionDown, times = 2)
        assertFocused("rating_more")
        press(Key.DirectionCenter)
        others = listOf(three.first())
        // A save elsewhere (e.g. the web) is not seen; one from this app refetches the detail.
        container.appScope.launch { container.ratings.set(RatingKind.FILM, 13, Stars(6)) }
        settle()
        back()
        assertTrue(absent("rating_more"))
        assertFocused("rating_mine")
    }

    @Test
    fun showDownUpPathsAndTheFirstSeason() {
        openShow()
        press(Key.DirectionDown)
        assertFocused("rating_mine")
        press(Key.DirectionDown)
        assertFocused("rating_more")
        press(Key.DirectionDown)
        assertFocused("season_2")
        press(Key.DirectionUp)
        assertFocused("season_1")
        press(Key.DirectionUp)
        assertFocused("rating_mine")
        press(Key.DirectionUp)
        assertFocused("button_RESUME")
        others = listOf(three.first())
        back()
        openShowAgain()
        press(Key.DirectionDown)
        assertFocused("rating_mine")
        press(Key.DirectionDown)
        assertFocused("season_2")
    }

    @Test
    fun showDownReachesASelectedSeasonBelowThePaneFold() {
        // Six seasons, next episode in season 6: the season list composes only its first rows.
        useLibrary()
        val base = api.onShow
        val sixth = EpisodeSummary(id = 601, show_id = 7, show_title = "The Office", season_number = 6, episode_number = 1, title = "Episode 1")
        api.onShow = { id ->
            base(id).copy(
                my_rating = 3.0, ratings = others, household_average = HouseholdAverage(3.3, others.size + 1), next_episode = sixth,
                seasons = (1..6).map { SeasonSummary(it.toLong(), it, null, unwatched_episode_count = 3) },
            )
        }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
        press(Key.DirectionDown)
        assertFocused("rating_mine")
        press(Key.DirectionDown)
        assertFocused("rating_more")
        press(Key.DirectionDown)
        assertFocused("season_6")
        press(Key.DirectionUp)
        assertFocused("season_5")
        others = emptyList()
        back()
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
        press(Key.DirectionDown)
        assertFocused("rating_mine")
        press(Key.DirectionDown) // no "y N más": straight to the selected season
        assertFocused("season_6")
    }

    @Test
    fun showDownWithoutANextEpisodeOrASeasonZeroLandsOnTheFirstSeason() {
        useLibrary()
        val base = api.onShow
        api.onShow = { id ->
            base(id).copy(
                my_rating = 3.0, ratings = others, household_average = HouseholdAverage(3.3, others.size + 1), next_episode = null, unwatched_episode_count = 0,
                seasons = (3..5).map { SeasonSummary(it.toLong(), it, null) },
            )
        }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertFocused("button_WATCHED")
        press(Key.DirectionDown)
        assertFocused("rating_mine")
        press(Key.DirectionDown, times = 2)
        assertFocused("season_3")
    }

    @Test
    fun showDownWithNoSeasonsStaysOnTheRatingsBlock() {
        // `selectedSeason` falls back to 0 with no season at all: Down must not reach a missing row.
        others = emptyList()
        useLibrary()
        val base = api.onShow
        api.onShow = { id -> base(id).copy(my_rating = 3.0, next_episode = null, unwatched_episode_count = 0, seasons = emptyList()) }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertFocused("button_WATCHED")
        press(Key.DirectionDown)
        assertFocused("rating_mine")
        press(Key.DirectionDown)
        assertFocused("rating_mine")
    }

    private fun openShowAgain() {
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
    }

    @Test
    fun onShowARatingCloseThenMarcarComoVistoKeepsFocusOnThatButton() {
        openShow()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertFocused("rating_stars")
        back()
        assertFocused("rating_mine")
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        assertFocused("button_WATCHED")
        press(Key.DirectionCenter)
        UiTestPlatform.advance(300)
        settle()
        assertFocused("button_WATCHED")
        assertEquals(1, api.watchedBodies.size)
    }
}
