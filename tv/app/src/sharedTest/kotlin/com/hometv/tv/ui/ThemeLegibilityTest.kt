package com.hometv.tv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.data.Session
import com.hometv.tv.testing.TEST_SERVER
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.ui.theme.HomeTvColors
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * prd3 Theme & legibility: a `Text` without an explicit color must be light on the dark theme
 * (contrast ≥ 4.5:1 against the background), never Compose's default black.
 */
@RunWith(AndroidJUnit4::class)
class ThemeLegibilityTest : UiTestBase() {
    /** The color the text was actually laid out with (the resolved `LocalContentColor` included). */
    private fun textColor(value: String): Color {
        val node = rule.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().first()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style.color
    }

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun assertLegible(vararg texts: String, on: Color = HomeTvColors.Background) {
        texts.forEach { value ->
            val color = textColor(value)
            val ratio = contrast(color, on)
            assertTrue("'$value' is drawn in $color: contrast $ratio:1 against $on (needs ≥ 4.5)", ratio >= 4.5)
        }
    }

    @Test
    fun setupTitleAndFieldLabelAreLight() {
        launch(Session())
        assertLegible("HomeTV", "Dirección del servidor")
    }

    @Test
    fun pickerTitleAndProfileNamesAreLight() {
        useLibrary()
        launch(Session(serverUrl = TEST_SERVER))
        assertLegible("¿Quién está viendo?", "Esther", "Javi")
    }

    @Test
    fun homeRowTitlesAreLight() {
        useLibrary()
        launch()
        assertLegible("Seguir viendo", "Añadidos recientemente")
    }

    @Test
    fun theUnwatchedBadgeIsDarkOnItsPrimaryBackground() {
        useLibrary()
        launch()
        // The Office (Añadidos recientemente) has 3 unwatched episodes.
        assertLegible("3", on = HomeTvColors.Primary)
    }

    @Test
    fun filmDetailMetadataUsesTheOverArtworkSecondaryColor() {
        useLibrary()
        launch()
        press(androidx.compose.ui.input.key.Key.DirectionDown)
        press(androidx.compose.ui.input.key.Key.DirectionRight)
        press(androidx.compose.ui.input.key.Key.DirectionCenter)
        // "2021 · 2 h 35 min" sits on the backdrop: it must use DetailText.secondary (see DetailLegibilityTest).
        val color = textColor("2021 · 2 h 35 min")
        assertTrue("the metadata line is drawn in $color", color == com.hometv.tv.ui.detail.DetailText.secondary)
    }

    @Test
    fun theBiographyTextStaysLightWhenItsBlockIsFocused() {
        useLibrary()
        api.onFilm = { id ->
            com.hometv.tv.core.api.FilmDetail(
                id = id, title = "Film $id", match_status = "matched", media_files = listOf(com.hometv.tv.core.playback.F.file(1)),
                cast = listOf(com.hometv.tv.core.api.CastMember(4, "Timothée Chalamet", "Paul")),
            )
        }
        val biography = "Timothée Hal Chalamet es un actor estadounidense."
        api.onPerson = { id ->
            com.hometv.tv.core.api.PersonDetail(
                person_id = id, name = "Timothée Chalamet", biography = biography, credits_complete = true,
                appearances = listOf(com.hometv.tv.core.api.PersonAppearance("film", dune, "Paul Atreides")),
            )
        }
        launch()
        press(androidx.compose.ui.input.key.Key.DirectionDown)
        press(androidx.compose.ui.input.key.Key.DirectionRight)
        press(androidx.compose.ui.input.key.Key.DirectionCenter)
        press(androidx.compose.ui.input.key.Key.DirectionDown)
        press(androidx.compose.ui.input.key.Key.DirectionCenter)
        press(androidx.compose.ui.input.key.Key.DirectionUp)
        assertFocused("person_bio")
        // tv-material's Surface switches LocalContentColor to a dark colour when focused: the text sets its own.
        val color = textColor(biography)
        assertTrue("the focused biography is drawn in $color", color == HomeTvColors.OnBackground)
        assertLegible(biography, on = HomeTvColors.SurfaceVariant)
    }
}
