package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.CastMember
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.Image
import com.hometv.tv.core.api.PersonAppearance
import com.hometv.tv.core.api.PersonDetail
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.api.Summary
import com.hometv.tv.core.playback.F
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.testing.UiTestPlatform
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OK on a Film detail cast card opens the Person screen; its
 * focus map, the biography dialog, Back chains that restore focus, the in-place refetch
 * after playback, the degraded / empty / 404 states and the 960×540 dp layout fit.
 */
@RunWith(AndroidJUnit4::class)
class PersonFocusTest : UiTestBase() {
    /** Ages are computed from the container's clock: fixed, so "(31 años)" never depends on the run date. */
    override val clock: Clock = Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC)

    private val cast = (1L..12L).map { CastMember(person_id = it, name = "Actor $it", character = "Personaje $it") }
    private var filmProgress: Progress? = null

    private val longBio = List(40) {
        "Timothée Hal Chalamet es un actor estadounidense que comenzó su carrera en televisión y en cortometrajes antes de debutar en el cine."
    }.joinToString(" ")

    private fun appearance(item: Summary, character: String?) = PersonAppearance(if (item is ShowSummary) "show" else "film", item, character)

    private val duneCard = FilmSummary(12, "Dune: Parte dos", 2024, progress = Progress(position_seconds = 1200.0, duration_seconds = 9360.0))
    private val officeCard = ShowSummary(7, "The Office", 2005, unwatched_episode_count = 3)

    /** A 1-line name with life span, place, a long biography; the first card has a long subtitle. */
    private fun chalamet(id: Long, appearances: List<PersonAppearance>? = null) = PersonDetail(
        person_id = id,
        name = "Timothée Chalamet",
        profile_image = Image("/api/artwork/w185/t.jpg", "/api/artwork/h632/t.jpg"),
        biography = longBio,
        biography_language = "es-ES",
        birthday = "1995-12-27",
        place_of_birth = "Manhattan, New York City, New York, USA",
        credits_complete = true,
        appearances = appearances ?: listOf(
            appearance(duneCard, "Paul Atreides, el hijo del duque Leto y de lady Jessica"),
            appearance(officeCard, "Finn Walden"),
        ) + (20L until 28L).map { appearance(FilmSummary(it, "Film $it", 2000), "Personaje $it") },
    )

    /** Never fetched with TMDB unreachable: name and photo only (`credits_complete` false). */
    private fun degraded(id: Long, appearances: List<PersonAppearance> = listOf(appearance(duneCard, "Paul Atreides"), appearance(officeCard, "Finn Walden"))) =
        PersonDetail(person_id = id, name = "Timothée Chalamet", credits_complete = false, appearances = appearances)

    private fun film(id: Long) = FilmDetail(
        id = id, title = "Film $id", year = 2021, runtime_minutes = 155, match_status = "matched", overview = "Una sinopsis.",
        media_files = listOf(F.file(1, duration = 7200.0)), cast = cast, progress = filmProgress,
    )

    /** Home → Alien (film 13) detail with focus on Reproducir. */
    private fun openFilmDetail(person: (Long) -> PersonDetail = { chalamet(it) }) {
        useLibrary()
        api.onFilm = { id -> film(id) }
        api.onPerson = { id -> person(id) }
        api.onPlayback = { id, _ -> PlaybackResponse("direct", url = "/api/media-files/$id/stream", audio_stream_index = 1, duration_seconds = 7200.0) }
        launch()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
    }

    /** Film 13 → Down ×2 (via `rating_mine`), Right ×3, OK on `cast_4:3` → Person 4. */
    private fun openPerson(person: (Long) -> PersonDetail = { chalamet(it) }) {
        openFilmDetail(person)
        press(Key.DirectionDown)
        assertFocused("rating_mine")
        press(Key.DirectionDown)
        press(Key.DirectionRight, times = 3)
        assertFocused("cast_4:3")
        press(Key.DirectionCenter)
    }

    private fun text(value: String) = rule.onNodeWithText(value, useUnmergedTree = true)

    /** The ✓ badge ("Visto") of the card [tag]. */
    private fun watchedBadge(tag: String) =
        rule.onNode(hasContentDescription("Visto") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    private fun scroll(tag: String): Float = node(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    // ---- Layout fit (written before the layout) -------------------------------------------------

    @Test
    fun thePageRunsToTheBottomEdge() {
        openPerson()
        assertFocused("appearance_film:12")
        assertReachesBottomEdge("person_page")
    }

    @Test
    fun theNameAndTheFirstCardsSubtitleFitTheSafeAreaWithFocusOnTheFirstCard() {
        openPerson()
        assertFocused("appearance_film:12")
        assertInsideSafeArea("appearance_film:12_subtitle")
        assertInsideSafeArea("person_name")
        assertInsideSafeArea("person_bio")
    }

    @Test
    fun inDegradedModeTheIncompleteNoteIsInTheHeaderAndFitsToo() {
        openPerson { degraded(it) }
        assertFocused("appearance_film:12")
        assertInsideSafeArea("appearance_film:12_subtitle")
        assertInsideSafeArea("person_name")
        assertInsideSafeArea("person_incomplete")
        text("Puede que falten títulos: no se ha podido consultar TMDB.").assertIsDisplayed()
        node("person_initial").assertIsDisplayed() // no photo: the initial…
        assertTrue("…decorative, not read by TalkBack", rule.onAllNodesWithText("T", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        node("person_bio").assertDoesNotExist()
    }

    // ---- Navigation and focus -------------------------------------------------------------------

    @Test
    fun okOnACastCardOpensThePersonAndBackRestoresThatCard() {
        openPerson()
        assertEquals(1, api.count("GET /api/people/4"))
        assertFocused("appearance_film:12")
        text("Timothée Chalamet").assertIsDisplayed()
        text("27 de diciembre de 1995 (31 años)").assertExists()
        text("Manhattan, New York City, New York, USA").assertIsDisplayed()
        text("En tu catálogo").assertIsDisplayed()
        text("como Finn Walden").assertIsDisplayed()
        back()
        assertFocused("cast_4:3")
        assertEquals(1, api.count("GET /api/people/4"))
    }

    @Test
    fun aFarRightCastCardIsRestoredToo() {
        openFilmDetail()
        press(Key.DirectionDown, times = 2) // via `rating_mine`
        press(Key.DirectionRight, times = 11)
        press(Key.DirectionCenter)
        assertTrue(api.calls.contains("GET /api/people/12"))
        assertFocused("appearance_film:12")
        back()
        assertFocused("cast_12:11")
        assertFullyVisible("cast_12:11")
    }

    @Test
    fun okOnAFilmOrShowCardOpensItsDetailAndBackReturnsToThatCard() {
        openPerson()
        press(Key.DirectionCenter)
        assertTrue(api.calls.contains("GET /api/films/12"))
        assertFocused("button_PLAY")
        back()
        assertFocused("appearance_film:12")

        press(Key.DirectionRight)
        assertFocused("appearance_show:7")
        press(Key.DirectionCenter)
        assertTrue(api.calls.contains("GET /api/shows/7"))
        text("The Office").assertIsDisplayed()
        back()
        assertFocused("appearance_show:7")
    }

    @Test
    fun movingAlongTheRowNeverPullsFocusBack() {
        openPerson()
        press(Key.DirectionRight)
        assertFocused("appearance_show:7")
        press(Key.DirectionRight)
        assertFocused("appearance_film:20")
        settle()
        assertFocused("appearance_film:20")
        press(Key.DirectionLeft)
        assertFocused("appearance_show:7")
        press(Key.DirectionDown)
        assertFocused("appearance_show:7")
    }

    @Test
    fun upReachesTheBiographyItsDialogScrollsAndBackReturnsToIt() {
        openPerson()
        press(Key.DirectionRight)
        assertFocused("appearance_show:7")
        press(Key.DirectionUp)
        assertFocused("person_bio")
        press(Key.DirectionUp)
        press(Key.DirectionLeft)
        press(Key.DirectionRight)
        assertFocused("person_bio")
        press(Key.DirectionCenter)
        node("person_bio_dialog").assertIsDisplayed()
        assertFocused("person_bio_dialog")
        text("Biografía").assertIsDisplayed()
        assertEquals(0f, scroll("person_bio_dialog"), 0.5f)
        press(Key.DirectionDown)
        UiTestPlatform.advance(1000)
        settle()
        assertTrue("Down scrolls the biography (scroll ${scroll("person_bio_dialog")})", scroll("person_bio_dialog") > 0f)
        back()
        node("person_bio_dialog").assertDoesNotExist()
        assertFocused("person_bio")
        press(Key.DirectionDown)
        assertFocused("appearance_show:7")
    }

    @Test
    fun withoutABiographyUpStaysOnTheRow() {
        openPerson { chalamet(it).copy(biography = null) }
        node("person_bio").assertDoesNotExist()
        press(Key.DirectionUp)
        assertFocused("appearance_film:12")
    }

    @Test
    fun aChainFilmActorFilmActorUnwindsOneStepAtATime() {
        openPerson()
        press(Key.DirectionCenter) // Dune (film 12)
        assertFocused("button_PLAY")
        press(Key.DirectionDown, times = 2) // via `rating_mine`
        press(Key.DirectionRight, times = 3)
        press(Key.DirectionCenter) // the same actor again
        assertEquals(2, api.count("GET /api/people/4"))
        assertFocused("appearance_film:12")
        back()
        assertFocused("cast_4:3") // film 12
        back()
        assertFocused("appearance_film:12") // the first Person
        back()
        assertFocused("cast_4:3") // film 13
        assertTrue(rule.onAllNodesWithText("Film 13", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Film 12", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aRefetchAfterPlaybackKeepsTheFocusTheViewerHas() {
        openPerson()
        press(Key.DirectionCenter) // Dune (film 12)
        assertFocused("button_PLAY")
        press(Key.DirectionCenter)
        node("player").assertIsDisplayed()
        onPlayer { ready() }
        back() // leaves the Player: film 12 refetches in place
        assertFocused("button_PLAY")

        val gate = CompletableDeferred<PersonDetail>()
        api.onPerson = { gate.await() }
        back() // to Person: its refetch is held
        assertEquals(2, api.count("GET /api/people/4"))
        assertFocused("appearance_film:12")
        watchedBadge("appearance_film:12").assertDoesNotExist()
        press(Key.DirectionUp)
        assertFocused("person_bio")

        val added = appearance(FilmSummary(30, "Wonka", 2023), "Willy Wonka")
        val watchedDune = duneCard.copy(progress = Progress(watched = true))
        gate.complete(chalamet(4, listOf(added, appearance(watchedDune, "Paul Atreides"), appearance(officeCard, "Finn Walden"))))
        settle()
        node("appearance_film:30").assertExists()
        watchedBadge("appearance_film:12").assertExists() // the badges update in place, no Loading
        assertFocused("person_bio")
        press(Key.DirectionDown)
        assertFocused("appearance_film:12") // the last focused card, now at index 1
    }

    @Test
    fun afterACastRoundTripAndPlayFocusIsOnTheFirstButtonNeverOnTheCast() {
        openPerson()
        back()
        assertFocused("cast_4:3")
        press(Key.DirectionUp)
        assertFocused("rating_mine")
        press(Key.DirectionUp)
        assertFocused("button_PLAY")
        press(Key.DirectionCenter)
        onPlayer { ready() }
        filmProgress = Progress(position_seconds = 600.0, duration_seconds = 7200.0, media_file_id = 1)
        back()
        assertFocused("button_RESUME")
        text("Reanudar desde 10:00").assertIsDisplayed()
    }

    @Test
    fun noTransientCastFocusWhileTheRefetchAfterPlayIsHeld() {
        openPerson()
        back()
        press(Key.DirectionUp, times = 2) // via `rating_mine`
        assertFocused("button_PLAY")
        settle()
        val before = scroll("film_detail_page")
        val loads = api.count("GET /api/films/13")
        press(Key.DirectionCenter)
        onPlayer { ready() }
        assertEquals("the Player's own film load", loads + 1, api.count("GET /api/films/13"))

        val gate = CompletableDeferred<FilmDetail>()
        api.onFilm = { gate.await() }
        back()
        assertEquals(loads + 2, api.count("GET /api/films/13"))
        assertFocused("button_PLAY")
        assertEquals("page scroll while the refetch is held", before, scroll("film_detail_page"), 0.5f)

        filmProgress = Progress(position_seconds = 600.0, duration_seconds = 7200.0, media_file_id = 1)
        gate.complete(film(13))
        settle()
        assertFocused("button_RESUME")
    }

    @Test
    fun afterACastRoundTripTheWatchedToggleKeepsFocusOnIt() {
        openPerson()
        back()
        press(Key.DirectionUp, times = 2) // via `rating_mine`
        press(Key.DirectionRight)
        assertFocused("button_WATCHED")
        filmProgress = Progress(watched = true)
        press(Key.DirectionCenter)
        text("Marcar como no visto").assertIsDisplayed()
        assertFocused("button_WATCHED")
    }

    // ---- States ---------------------------------------------------------------------------------

    @Test
    fun noAppearancesShowsTheEmptyTextAndFocusesTheBiography() {
        openPerson { chalamet(it, appearances = emptyList()) }
        text("No aparece en ningún título disponible de tu catálogo.").assertIsDisplayed()
        node("person_empty").assertIsDisplayed()
        assertFocused("person_bio")
        back()
        assertFocused("cast_4:3")
    }

    @Test
    fun degradedAndEmptyShowsTheNoteAndTheEmptyText() {
        openPerson { degraded(it, appearances = emptyList()) }
        node("person_incomplete").assertIsDisplayed()
        node("person_empty").assertIsDisplayed()
        assertInsideSafeArea("person_incomplete")
        back()
        assertFocused("cast_4:3")
    }

    @Test
    fun anUnknownPersonShowsTheGoneScreenAndVolverPops() {
        openPerson { throw com.hometv.tv.core.data.FakeHomeTvApi.httpError(404, "not_found") }
        text("Esta persona no está disponible.").assertIsDisplayed()
        text("Volver").assertIsDisplayed()
        press(Key.DirectionCenter)
        assertFocused("cast_4:3")
    }
}
