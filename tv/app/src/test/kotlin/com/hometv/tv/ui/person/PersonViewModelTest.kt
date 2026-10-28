package com.hometv.tv.ui.person

import com.hometv.tv.R
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.Image
import com.hometv.tv.core.api.PersonAppearance
import com.hometv.tv.core.api.PersonDetail
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.data.FakeHomeTvApi
import com.hometv.tv.data.DetailRefresh
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.common.PosterItem
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.text.UiText
import java.net.UnknownHostException
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** cast-appearances prd3 §3: the Person screen's state, focus memory and in-place refetch. */
class PersonViewModelTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    private val repos = TestRepos()
    private val detailRefresh = DetailRefresh()
    private val today = LocalDate.of(2026, 10, 27)

    private val dune = FilmSummary(12, "Dune", 2021, poster = Image("/p/dune-s.jpg", "/p/dune-l.jpg"), progress = Progress(position_seconds = 9300.0, duration_seconds = 9360.0, watched = true))
    private val wonka = FilmSummary(13, "Wonka", 2023)
    private val homeland = ShowSummary(7, "Homeland", 2011, unwatched_episode_count = 90)

    private fun appearance(item: com.hometv.tv.core.api.Summary, character: String?) =
        PersonAppearance(if (item is ShowSummary) "show" else "film", item, character)

    private var person = PersonDetail(
        person_id = 3,
        name = "Timothée Chalamet",
        profile_image = Image("/api/artwork/w185/t.jpg", "/api/artwork/h632/t.jpg"),
        biography = "Timothée Hal Chalamet es un actor estadounidense.",
        biography_language = "es-ES",
        birthday = "1995-12-27",
        place_of_birth = "Manhattan, New York City, New York, USA",
        credits_complete = true,
        appearances = listOf(appearance(wonka, "Willy Wonka"), appearance(homeland, "Finn Walden"), appearance(dune, "Paul Atreides")),
    )

    init {
        repos.api.onPerson = { person }
    }

    private fun vm() = PersonViewModel(3, repos.catalog, repos.store, detailRefresh, today = { today })

    private fun PersonViewModel.content() = state.value as PersonUiState.Content

    private fun returnFromPlayer(vm: PersonViewModel) {
        detailRefresh.markStale()
        vm.onVisible()
    }

    @Test
    fun `loading, then the person mapped to the screen`() = runTest(main.dispatcher) {
        val vm = vm()
        assertEquals(PersonUiState.Loading, vm.state.value)
        advanceUntilIdle()
        val c = vm.content()
        assertEquals(listOf("GET /api/people/3"), repos.api.calls)
        assertEquals("Timothée Chalamet", c.name)
        assertEquals("/api/artwork/h632/t.jpg", c.photo)
        assertEquals("T", c.initial)
        assertEquals(
            UiText.Res(R.string.person_life_living, listOf("27 de diciembre de 1995", UiText.Plural(R.plurals.person_age, 30, listOf("30")))),
            c.lifeSpan,
        )
        assertEquals("Manhattan, New York City, New York, USA", c.placeOfBirth)
        assertEquals("Timothée Hal Chalamet es un actor estadounidense.", c.biography)
        assertFalse(c.incomplete)
        assertFalse(c.bioDialogOpen)
        assertEquals(listOf("film:13", "show:7", "film:12"), c.cards.map { it.item.key }, "server order")
        assertEquals(PosterItem.of(dune), c.cards[2].item, "the Browse/Search card: poster.small, ✓, progress")
        assertEquals("90", c.cards[1].item.badge)
    }

    @Test
    fun `subtitles are como character for films and shows, nothing without a character`() = runTest(main.dispatcher) {
        person = person.copy(appearances = listOf(appearance(dune, "Paul Atreides"), appearance(homeland, "Finn Walden"), appearance(wonka, null), appearance(homeland.copy(id = 8), "  ")))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(
            listOf(
                UiText.Res(R.string.person_as_character, listOf("Paul Atreides")),
                UiText.Res(R.string.person_as_character, listOf("Finn Walden")),
                null,
                null,
            ),
            vm.content().cards.map { it.subtitle },
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lifeSpans")
    fun `life span line`(case: String, birthday: String?, deathday: String?, expected: UiText?) = runTest(main.dispatcher) {
        person = person.copy(birthday = birthday, deathday = deathday)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(expected, vm.content().lifeSpan, case)
    }

    @Test
    fun `initial of the name for the photo placeholder`() = runTest(main.dispatcher) {
        person = person.copy(name = "  émile Hirsch", profile_image = null)
        val vm = vm()
        advanceUntilIdle()
        assertEquals("É", vm.content().initial)
        assertEquals(null, vm.content().photo)

        person = person.copy(name = "   ")
        val blank = vm()
        advanceUntilIdle()
        assertEquals("?", blank.content().initial)
    }

    @Test
    fun `credits_complete false is the incomplete note, blank texts are hidden`() = runTest(main.dispatcher) {
        person = PersonDetail(person_id = 3, name = "Josh Brolin", biography = "  ", place_of_birth = "", credits_complete = false)
        val vm = vm()
        advanceUntilIdle()
        val c = vm.content()
        assertTrue(c.incomplete)
        assertEquals(null, c.biography)
        assertEquals(null, c.placeOfBirth)
        assertEquals(null, c.lifeSpan)
        assertEquals(emptyList<AppearanceCard>(), c.cards)
    }

    @Test
    fun `initial focus is the first card, else the biography, else nothing`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(PersonFocus.Card("film:13"), vm.content().focus)

        person = person.copy(appearances = emptyList())
        val bioOnly = vm()
        advanceUntilIdle()
        assertEquals(PersonFocus.Bio, bioOnly.content().focus)

        person = person.copy(biography = null)
        val nothing = vm()
        advanceUntilIdle()
        assertEquals(PersonFocus.None, nothing.content().focus)
    }

    @Test
    fun `OK on a card remembers it and opens the film or show detail`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val version = vm.content().focusVersion
        vm.onCardSelected(vm.content().cards[2])
        assertEquals(PersonFocus.Card("film:12"), vm.content().focus)
        assertEquals(version, vm.content().focusVersion)
        assertEquals(NavRequest.Detail("film", 12), vm.navigation.first())
        vm.onCardSelected(vm.content().cards[1])
        assertEquals(NavRequest.Detail("show", 7), vm.navigation.first())
        assertEquals(PersonFocus.Card("show:7"), vm.content().focus)
    }

    @Test
    fun `the biography dialog opens and closes with focus back on the biography`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val version = vm.content().focusVersion
        vm.openBio()
        assertTrue(vm.content().bioDialogOpen)
        vm.closeBio()
        assertFalse(vm.content().bioDialogOpen)
        assertEquals(PersonFocus.Bio, vm.content().focus)
        assertEquals(version + 1, vm.content().focusVersion)
    }

    @Test
    fun `an unknown person is PersonGone with no ItemGone event`() = runTest(main.dispatcher) {
        repos.recordEvents(backgroundScope)
        repos.api.onPerson = { throw FakeHomeTvApi.httpError(404, "not_found") }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(PersonUiState.Error(ScreenError.PersonGone), vm.state.value)
        assertEquals(emptyList<Any>(), repos.received)
    }

    @Test
    fun `an unreachable server is the Connection Error and Retry reloads from Loading`() = runTest(main.dispatcher) {
        repos.api.onPerson = { throw UnknownHostException("hometv") }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(PersonUiState.Error(ScreenError.Connection("hometv.tail1234.ts.net", false)), vm.state.value)
        repos.api.onPerson = { person }
        vm.retry()
        assertEquals(PersonUiState.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals("Timothée Chalamet", vm.content().name)
    }

    @Test
    fun `becoming visible refetches in place only after a Player launch`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(1, repos.api.count("GET /api/people/3"))

        person = person.copy(appearances = listOf(appearance(wonka.copy(progress = Progress(position_seconds = 600.0, duration_seconds = 6000.0)), "Willy Wonka")))
        returnFromPlayer(vm)
        assertTrue(vm.state.value is PersonUiState.Content, "in place: never Loading")
        advanceUntilIdle()
        assertEquals(2, repos.api.count("GET /api/people/3"))
        assertEquals(0.1f, vm.content().cards.single().item.progress)
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(2, repos.api.count("GET /api/people/3"), "each Player launch is seen once")
    }

    @Test
    fun `a refetch that moves the selected card keeps focus and focusVersion unchanged`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onCardSelected(vm.content().cards[2])
        vm.navigation.first()
        val version = vm.content().focusVersion
        person = person.copy(appearances = listOf(appearance(wonka, "Willy Wonka"), appearance(dune, "Paul Atreides")))
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(listOf("film:13", "film:12"), vm.content().cards.map { it.item.key })
        assertEquals(PersonFocus.Card("film:12"), vm.content().focus)
        assertEquals(version, vm.content().focusVersion)
    }

    @Test
    fun `a refetch after the biography dialog keeps focus on the biography`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.openBio()
        vm.closeBio()
        val version = vm.content().focusVersion
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(PersonFocus.Bio, vm.content().focus)
        assertEquals(version, vm.content().focusVersion)
    }

    @Test
    fun `a refetch without the selected card falls back to the first card, the biography or nothing`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onCardSelected(vm.content().cards[2])
        vm.navigation.first()
        person = person.copy(appearances = listOf(appearance(homeland, "Finn Walden"), appearance(wonka, "Willy Wonka")))
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(PersonFocus.Card("show:7"), vm.content().focus)

        vm.onCardSelected(vm.content().cards[0])
        vm.navigation.first()
        person = person.copy(appearances = emptyList())
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(PersonFocus.Bio, vm.content().focus)

        person = person.copy(biography = null)
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(PersonFocus.None, vm.content().focus)
    }

    @Test
    fun `a failed in-place refetch keeps the current content`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val before = vm.content()
        repos.api.onPerson = { throw UnknownHostException("hometv") }
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(before, vm.state.value)
    }

    @Test
    fun `no second refetch starts while one is running`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val gate = CompletableDeferred<PersonDetail>()
        repos.api.onPerson = { gate.await() }
        returnFromPlayer(vm)
        runCurrent()
        returnFromPlayer(vm)
        runCurrent()
        assertEquals(2, repos.api.count("GET /api/people/3"))
        gate.complete(person)
        advanceUntilIdle()
        assertTrue(vm.state.value is PersonUiState.Content)
    }

    companion object {
        private fun age(n: Int) = UiText.Plural(R.plurals.person_age, n, listOf(n.toString()))

        @JvmStatic
        fun lifeSpans(): List<Arguments> = listOf(
            Arguments.of("living", "1995-12-27", null, UiText.Res(R.string.person_life_living, listOf("27 de diciembre de 1995", age(30)))),
            Arguments.of("living, 1 year", "2025-10-01", null, UiText.Res(R.string.person_life_living, listOf("1 de octubre de 2025", age(1)))),
            Arguments.of("future birthday: the date alone", "2027-01-01", null, UiText.Data("1 de enero de 2027")),
            Arguments.of(
                "deceased", "1932-10-08", "2010-05-03",
                UiText.Res(R.string.person_life_deceased, listOf("8 de octubre de 1932", "3 de mayo de 2010", age(77))),
            ),
            Arguments.of(
                "death before birth: no age", "2010-05-03", "1932-10-08",
                UiText.Res(R.string.person_life_deceased_no_age, listOf("3 de mayo de 2010", "8 de octubre de 1932")),
            ),
            Arguments.of("deathday only", null, "2010-05-03", UiText.Res(R.string.person_life_death_only, listOf("3 de mayo de 2010"))),
            Arguments.of("unknown", null, null, null),
            Arguments.of("invalid", "2021-02-30", null, null),
        )
    }
}
