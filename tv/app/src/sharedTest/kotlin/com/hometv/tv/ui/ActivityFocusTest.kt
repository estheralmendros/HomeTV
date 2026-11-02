package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.ActivityEntry
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.HomeResponse
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.api.ProfileRef
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.testing.UiTestBase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Actividad tab and screen, and the card badge. */
@RunWith(AndroidJUnit4::class)
class ActivityFocusTest : UiTestBase() {
    override val clock: Clock = Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"), ZoneOffset.UTC)

    private fun entry(profile: Long, name: String, item: com.hometv.tv.core.api.Summary, stars: Double, at: String) =
        ActivityEntry(ProfileRef(profile, name, "avatar-0$profile"), item.type, item, stars, at)

    private var entries = listOf(
        entry(2, "Luis", FilmSummary(13, "Alien", 1979), 2.0, "2026-10-31T11:55:00Z"),
        entry(5, "Esther", ShowSummary(7, "The Office", 2005), 5.0, "2026-10-30T09:00:00Z"),
    ) + (1L..60L).map { entry(3, "Ana", FilmSummary(100 + it, "Film $it", 2000), 3.5, "2026-10-12T10:00:00Z") }

    private fun useActivity() {
        useLibrary()
        api.onActivity = { p, s -> Page(entries.drop((p - 1) * s).take(s), p, s, entries.size) }
    }

    /** Home → Up to the nav → Right ×4 (Inicio → … → Mi lista → Actividad) → OK. */
    private fun openActivity() {
        launch()
        press(Key.DirectionUp)
        press(Key.DirectionRight, times = 4)
        assertFocused("tab_ACTIVITY")
        press(Key.DirectionCenter)
    }

    private fun absent(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    @Test
    fun rowsReadAsSentencesAndOkOpensTheDetailAndBackRestoresTheRow() {
        useActivity()
        openActivity()
        node("activity_header").assert(hasText("Actividad"))
        assertFocused("activity_row_0")
        node("activity_row_0").assert(hasContentDescription("Luis ha puntuado Alien con 2 estrellas, hace 5 minutos"))
        node("activity_row_1").assert(hasContentDescription("Has puntuado The Office con 5 estrellas, ayer"))
        node("activity_row_2").assert(hasContentDescription("Ana ha puntuado Film 1 con 3,5 estrellas, 12 oct"))
        assertFullyVisible("activity_row_0")
        press(Key.DirectionDown)
        assertFocused("activity_row_1")
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
        val loads = api.count("GET /api/activity?page=1&page_size=50")
        back()
        assertFocused("activity_row_1")
        assertEquals("refetched after returning", loads + 1, api.count("GET /api/activity?page=1&page_size=50"))
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        assertTrue("Up from row 0 reaches the top nav", focusedTags().any { it.contains("tab_") })
        back()
        assertFocused("tab_ACTIVITY")
    }

    @Test
    fun aSecondBackFromADetailRestoresTheRowOpenedLastEvenWhenTheReloadFails() {
        useActivity()
        openActivity()
        press(Key.DirectionDown)
        assertFocused("activity_row_1")
        press(Key.DirectionCenter)
        assertFocused("button_RESUME")
        back()
        assertFocused("activity_row_1")
        // Second round trip from another row; the reload on return fails (the rows stay as they are).
        press(Key.DirectionUp)
        assertFocused("activity_row_0")
        press(Key.DirectionCenter)
        assertFocused("button_PLAY")
        api.onActivity = { _, _ -> throw httpError(500, "internal_error") }
        val loads = api.count("GET /api/activity?page=1&page_size=50")
        back()
        assertEquals("the reload ran", loads + 1, api.count("GET /api/activity?page=1&page_size=50"))
        assertFocused("activity_row_0")
    }

    @Test
    fun theFocusedRowCentresItsContentInsideTheFrame() {
        useActivity()
        openActivity()
        assertFocused("activity_row_0")
        val row = node("activity_row_0").fetchSemanticsNode()
        // The row's own content: the one whose top lies inside the row (the focused content is scaled
        // by WIDE_FOCUSED_SCALE around its centre, the row's semantics bounds are not).
        val all = rule.onAllNodes(hasTestTag("activity_row_content"), useUnmergedTree = true).fetchSemanticsNodes()
        val content = all.first { it.boundsInRoot.top in (row.boundsInRoot.top - 4f)..row.boundsInRoot.bottom }
        val density = row.layoutInfo.density.density
        val rowCentre = row.boundsInRoot.center.y / density
        val contentCentre = content.boundsInRoot.center.y / density
        assertEquals("content centre $contentCentre dp vs row centre $rowCentre dp", rowCentre, contentCentre, 0.5f)
        val rowHeight = row.boundsInRoot.height / density
        val contentHeight = content.boundsInRoot.height / density
        assertTrue("content height $contentHeight dp fills the $rowHeight dp row", contentHeight >= rowHeight - 0.5f)
    }

    @Test
    fun focusNearTheEndLoadsTheNextPage() {
        useActivity()
        openActivity()
        press(Key.DirectionDown, times = 46)
        assertFocused("activity_row_46")
        assertEquals(1, api.count("GET /api/activity?page=2&page_size=50"))
        press(Key.DirectionDown, times = 10)
        assertFocused("activity_row_56")
    }

    @Test
    fun anEmptyHouseholdShowsTheTextAndFocusStaysOnTheTab() {
        useLibrary()
        openActivity()
        node("activity_empty").assert(hasText("Todavía nadie ha puntuado nada. Cuando alguien puntúe una película o serie, aparecerá aquí."))
        assertFocused("tab_ACTIVITY")
        back()
        assertFocused("tab_ACTIVITY")
    }

    @Test
    fun cardsShowTheStarBadgeOnlyWithMyRating() {
        useLibrary()
        api.onHome = { HomeResponse(recently_added = listOf(office.copy(my_rating = 4.5), alien)) }
        launch()
        node("card_recent:show:7").assertExists()
        val badges = rule.onAllNodes(hasTestTag("rating_badge"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, badges.size)
        rule.onNode(hasTestTag("rating_badge") and hasContentDescription("tu puntuación: 4,5 estrellas"), useUnmergedTree = true).assertExists()
        assertTrue(!absent("card_recent:film:13"))
    }
}
