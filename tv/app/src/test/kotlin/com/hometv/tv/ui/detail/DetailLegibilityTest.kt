package com.hometv.tv.ui.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.hometv.tv.ui.theme.HomeTvColors
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * prd3 Theme & legibility: text over the detail backdrop stays readable whatever the artwork. Worst
 * case: a pure-white backdrop pixel under the [BACKDROP_SCRIM_ALPHA] scrim.
 */
class DetailLegibilityTest {
    private val worstBackground = HomeTvColors.Background.copy(alpha = BACKDROP_SCRIM_ALPHA).compositeOver(Color.White)

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun `the metadata line is readable over a white backdrop`() {
        val ratio = contrast(DetailText.secondary, worstBackground)
        assertTrue(ratio >= 4.5, "metadata contrast over a white backdrop: $ratio:1 (needs ≥ 4.5)")
    }

    @Test
    fun `title and synopsis are readable over a white backdrop`() {
        val ratio = contrast(HomeTvColors.OnBackground, worstBackground)
        assertTrue(ratio >= 4.5, "title/synopsis contrast over a white backdrop: $ratio:1 (needs ≥ 4.5)")
    }
}
