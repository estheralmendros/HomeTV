package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.CastMember
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.playback.F
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.testing.UiTestPlatform
import com.hometv.tv.ui.ratings.RatingEditor
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ratings prd3 FR-T8 / T-AC-6..8, 11, 11b: the D-pad rating dialog opened from Film detail's `rating_mine`. */
@RunWith(AndroidJUnit4::class)
class RatingDialogFocusTest : UiTestBase() {
    private var myRating: Double? = 4.0

    private fun openDialog() {
        useLibrary()
        api.onFilm = { id ->
            FilmDetail(id = id, title = "Dune", year = 2021, match_status = "matched", media_files = listOf(F.file(1)), my_rating = myRating, cast = listOf(CastMember(1, "Timothée", "Paul")))
        }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
        press(Key.DirectionDown)
        assertFocused("rating_mine")
        press(Key.DirectionCenter)
        assertFocused("rating_stars")
    }

    private fun absent(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    private fun ratingPuts() = api.calls.count { it == "PUT /api/ratings/films/13" }

    /** The merged node TalkBack reads (the stars' own description must not repeat the parent's). */
    private fun mergedDescriptions(tag: String): List<String> =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }

    @Test
    fun theStarsAndTheChipAreDescribedOnce() {
        openDialog()
        assertEquals(listOf("4 estrellas"), mergedDescriptions("rating_stars"))
        back()
        assertFocused("rating_mine")
        assertEquals(listOf("Tu puntuación: 4 estrellas. Cambiar"), mergedDescriptions("rating_mine"))
    }

    @Test
    fun rightStepsByAHalfAndOkOnTheStarsSavesThenTheConfirmationCloses() {
        openDialog()
        node("rating_stars").assert(hasContentDescription("4 estrellas"))
        press(Key.DirectionRight)
        node("rating_stars").assert(hasContentDescription("4,5 estrellas"))
        assertFocused("rating_stars")
        myRating = 4.5
        press(Key.DirectionCenter)
        assertEquals(1, ratingPuts())
        node("rating_saved").assertExists()
        assertTrue("the buttons are replaced", absent("rating_save"))
        assertFocused("rating_stars")
        UiTestPlatform.advance(RatingEditor.SAVED_MILLIS + 100)
        settle()
        assertTrue(absent("rating_dialog"))
        assertFocused("rating_mine")
        node("rating_mine").assert(hasContentDescription("Tu puntuación: 4,5 estrellas. Cambiar"))
    }

    @Test
    fun leftAndRightAreClampedAndDownUpMoveBetweenStarsAndButtons() {
        myRating = null
        openDialog()
        node("rating_stars").assert(hasContentDescription("Sin puntuación"))
        press(Key.DirectionLeft)
        node("rating_stars").assert(hasContentDescription("0,5 estrellas"))
        press(Key.DirectionLeft)
        node("rating_stars").assert(hasContentDescription("0,5 estrellas"))
        press(Key.DirectionRight, times = 12)
        node("rating_stars").assert(hasContentDescription("5 estrellas"))
        press(Key.DirectionDown)
        assertFocused("rating_save")
        press(Key.DirectionRight)
        assertFocused("rating_cancel")
        assertTrue("no Quitar puntuación when unrated", absent("rating_remove"))
        press(Key.DirectionUp)
        assertFocused("rating_stars")
    }

    @Test
    fun okTwiceOrOkThenGuardarSendsOnePutAndNeverCrashes() {
        val gate = CompletableDeferred<Unit>()
        api.onRatingWrite = { _, _, _ -> gate.await() }
        openDialog()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals(1, ratingPuts())
        // Back while saving does nothing: the dialog stays until the write settles.
        back()
        node("rating_dialog").assertExists()
        gate.complete(Unit)
        settle()
        node("rating_saved").assertExists()
        back()
        assertTrue("Back once saved closes at once", absent("rating_dialog"))
        assertEquals(listOf("PUT /api/ratings/films/13"), api.calls.filter { it.contains("/ratings/") })
    }

    @Test
    fun manualBackAndCancelarSendNothingAndReturnToRatingMine() {
        openDialog()
        press(Key.DirectionRight)
        back()
        assertTrue(absent("rating_dialog"))
        assertFocused("rating_mine")
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertFocused("rating_cancel")
        press(Key.DirectionCenter)
        assertFocused("rating_mine")
        assertTrue(api.calls.none { it.contains("/ratings/") })
    }

    @Test
    fun aFailedSaveShowsTheErrorAndFocusesReintentar() {
        api.onRatingWrite = { _, _, _ -> throw httpError(500, "internal_error") }
        openDialog()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        node("rating_error").assertExists()
        assertFocused("rating_retry")
        node("rating_stars").assert(hasContentDescription("4,5 estrellas"))
        api.onRatingWrite = { _, _, _ -> }
        press(Key.DirectionCenter)
        node("rating_saved").assertExists()
        assertEquals(2, ratingPuts())
    }

    @Test
    fun removingClosesAtOnceAndTheChipIsDisabledUntilTheDeleteLands() {
        val gate = CompletableDeferred<Unit>()
        api.onRatingWrite = { _, _, _ -> gate.await() }
        openDialog()
        press(Key.DirectionDown)
        press(Key.DirectionRight, times = 2)
        assertFocused("rating_remove")
        myRating = null
        press(Key.DirectionCenter)
        assertTrue(absent("rating_dialog"))
        assertFocused("rating_mine")
        node("rating_mine").assert(hasContentDescription("Puntuar"))
        node("rating_mine").assertIsNotEnabled()
        press(Key.DirectionCenter)
        assertTrue("OK does nothing while the write is in flight", absent("rating_dialog"))
        // Back leaves the detail; the write still lands in appScope.
        back()
        gate.complete(Unit)
        settle()
        assertEquals(1, api.count("DELETE /api/ratings/films/13"))
    }
}
