package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.HomeResponse
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.RatingPrompt
import com.hometv.tv.core.api.WatchedResponse
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.playback.F
import com.hometv.tv.testing.UiTestBase
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ratings prd3 FR-T7 / T-AC-1..5b, 8a: the root prompt opens over the screen shown after the player
 * or a mark watched, never over the player; Back = Ahora no; focus returns; profile safety; the
 * host's write-failure policy.
 */
@RunWith(AndroidJUnit4::class)
class RatingPromptHostTest : UiTestBase() {
    private val dunePrompt = RatingPrompt("film", 12, "Dune")
    private val film13 = RatingPrompt("film", 13, "Film 13")

    private fun absent(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    private fun dismissals() = api.calls.filter { it.endsWith("/dismiss-prompt") }

    /** Film 13 detail; OK on "Marcar como visto" answers the prompt and opens it over the detail. */
    private fun promptOverFilmDetail() {
        useLibrary()
        api.onWatched = { body -> WatchedResponse(1, if (body.watched) film13 else null) }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        assertFocused("button_WATCHED")
        val first = api.onFilm
        api.onFilm = { id -> first(id).copy(progress = Progress(watched = true)) }
        press(Key.DirectionCenter)
        node("rating_dialog").assertExists()
        assertFocused("rating_stars")
    }

    @Test
    fun markWatchedOpensThePromptOverDetailAndBackIsAhoraNo() {
        promptOverFilmDetail()
        node("rating_question").assertExists()
        back()
        assertTrue(absent("rating_dialog"))
        assertEquals(listOf("POST /api/ratings/films/13/dismiss-prompt"), dismissals())
        assertFocused("button_WATCHED")
        // Handled for this app session: unmark + mark again does not reopen it.
        press(Key.DirectionCenter)
        api.onFilm = { id -> FilmDetail(id = id, title = "Film $id", match_status = "matched", media_files = listOf(F.file(1)), progress = Progress(watched = true)) }
        press(Key.DirectionCenter)
        assertTrue(absent("rating_dialog"))
    }

    @Test
    fun savingFromThePromptUpdatesTheDetailRowWithoutLeaving() {
        promptOverFilmDetail()
        press(Key.DirectionRight, times = 8)
        val first = api.onFilm
        api.onFilm = { id -> first(id).copy(my_rating = 4.0) }
        press(Key.DirectionCenter)
        node("rating_saved").assertExists()
        assertEquals(1, api.count("PUT /api/ratings/films/13"))
        back()
        assertTrue(absent("rating_dialog"))
        assertTrue("no dismissal after a save", dismissals().isEmpty())
        assertFocused("button_WATCHED")
        node("rating_mine").assertContentDescriptionCompat("Tu puntuación: 4 estrellas. Cambiar")
    }

    @Test
    fun neverOverThePlayerThenOverHomeWithFocusBackOnTheCard() {
        useLibrary()
        api.onFilm = { id -> FilmDetail(id = id, title = "Dune", media_files = listOf(F.file(1)), progress = Progress(1200.0, 9360.0, media_file_id = 1)) }
        api.onPlayback = { _, _ -> PlaybackResponse("direct", url = "/api/media-files/1/stream", audio_stream_index = 1, duration_seconds = 9360.0) }
        launch()
        assertFocused("card_continue:film:12")
        press(Key.DirectionCenter)
        onPlayer { ready() }
        // A prompt pending while the player is current (e.g. a periodic report carried it) stays pending.
        container.ratingPrompts.offer(5, dunePrompt)
        settle()
        assertTrue("never over the player", absent("rating_dialog"))
        back() // exit (no overlay is shown while playing)
        node("rating_dialog").assertExists()
        assertFocused("rating_stars")
        // No value yet: Down lands on the first enabled button, [Ahora no].
        press(Key.DirectionDown)
        assertFocused("rating_not_now")
        press(Key.DirectionCenter)
        assertTrue(absent("rating_dialog"))
        assertEquals(listOf("POST /api/ratings/films/12/dismiss-prompt"), dismissals())
        assertFocused("card_continue:film:12")
    }

    @Test
    fun theFinalPutOfAFilmEndOpensThePromptAfterTheExit() {
        useLibrary()
        api.onFilm = { id -> FilmDetail(id = id, title = "Dune", media_files = listOf(F.file(1, duration = 9360.0))) }
        api.onPlayback = { _, _ -> PlaybackResponse("direct", url = "/api/media-files/1/stream", audio_stream_index = 1, duration_seconds = 9360.0) }
        api.onProgressPrompt = { body -> if (body.position_seconds == 9360.0) dunePrompt else null }
        launch()
        press(Key.DirectionCenter)
        onPlayer { ready() }
        assertTrue(absent("rating_dialog"))
        onPlayer { end() }
        node("rating_dialog").assertExists()
        assertFocused("rating_stars")
    }

    @Test
    fun aPromptOfAnotherProfileIsNeverShown() {
        useLibrary()
        launch()
        container.ratingPrompts.offer(6, dunePrompt)
        settle()
        assertTrue(absent("rating_dialog"))
        assertNull("dropped by take(5)", container.ratingPrompts.pending.value)
    }

    @Test
    fun a404WhileSavingClosesAndTheCardLeavesHome() {
        useLibrary()
        api.onRatingWrite = { _, _, _ -> throw httpError(404, "not_found") }
        launch()
        container.ratingPrompts.offer(5, dunePrompt)
        settle()
        api.onHome = { HomeResponse(continue_watching = listOf(olympics), recently_added = listOf(office, alien)) } // the server has dropped it
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertTrue(absent("rating_dialog"))
        assertTrue("Home dropped the gone title", absent("card_continue:film:12"))
    }

    @Test
    fun a401AfterASuccessfulRenewalIsFailedWithReintentar() {
        useLibrary()
        api.onRatingWrite = { _, _, _ -> throw httpError(401, "unauthorized") }
        launch()
        container.ratingPrompts.offer(5, dunePrompt)
        settle()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(2, api.count("PUT /api/ratings/films/12"))
        assertEquals(1, api.count("POST /api/profiles/5/session"))
        node("rating_dialog").assertExists()
        node("rating_error").assertExists()
        assertFocused("rating_retry")
        assertTrue("no confirmation", absent("rating_saved"))
    }

    @Test
    fun aGoneProfileClosesTheDialogShowsThePickerAndNothingComesBack() {
        useLibrary()
        api.onRatingWrite = { _, _, _ -> throw httpError(401, "unauthorized") }
        api.onCreateSession = { throw httpError(404, "profile_not_found") }
        launch()
        container.ratingPrompts.offer(5, dunePrompt)
        settle()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertTrue(absent("rating_dialog"))
        node("profile_8").assertExists()
        assertTrue(dismissals().isEmpty())
        api.onCreateSession = { id -> com.hometv.tv.core.data.FakeHomeTvApi.session("tok-$id", id) }
        node("profile_8").performClickCompat()
        settle()
        assertTrue(absent("rating_dialog"))
    }

    @Test
    fun authExpiryWhileSavingClosesAtOnceAndTheLateOutcomeChangesNothing() {
        useLibrary()
        val gate = CompletableDeferred<Unit>()
        api.onRatingWrite = { _, _, _ -> gate.await() }
        launch()
        container.ratingPrompts.offer(5, dunePrompt)
        settle()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        node("rating_dialog").assertExists()
        rule.runOnUiThread { container.events.emit(AppEvent.AuthExpired) }
        settle()
        assertTrue(absent("rating_dialog"))
        node("profile_8").assertExists()
        gate.complete(Unit)
        settle()
        assertTrue(absent("rating_dialog"))
        assertTrue(absent("rating_saved"))
        assertTrue(dismissals().isEmpty())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertContentDescriptionCompat(text: String) =
        assert(androidx.compose.ui.test.hasContentDescription(text))

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickCompat() =
        performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
}
