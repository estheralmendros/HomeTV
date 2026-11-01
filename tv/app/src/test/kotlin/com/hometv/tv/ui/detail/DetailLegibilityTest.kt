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

    @Test
    fun `the Mi lista failure line (secondary tone) is not the error color`() {
        // WatchlistErrorLine draws in DetailText.secondary, readable over a white backdrop (test above).
        assertTrue(DetailText.secondary != HomeTvColors.Error)
    }

    /** ratings prd3 FR-T12: the block's stars are graphics (≥ 3:1) over the worst backdrop pixel; its texts use the secondary tone. */
    @Test
    fun `the ratings block's stars are visible over a white backdrop`() {
        val filled = contrast(HomeTvColors.Rating, worstBackground)
        assertTrue(filled >= 3.0, "filled star contrast over a white backdrop: $filled:1 (needs ≥ 3)")
        // [HomeTvColors.Muted] would be about 2.8:1 there: the block draws its empty outlines in DetailText.secondary.
        val empty = contrast(DETAIL_EMPTY_STAR, worstBackground)
        assertTrue(empty >= 3.0, "empty star contrast over a white backdrop: $empty:1 (needs ≥ 3)")
    }

    /** The focused `rating_mine` chip keeps its stars and text readable on its own container (no near-white fill). */
    @Test
    fun `the focused rating chip keeps its stars and text readable`() {
        val on = RatingChipColors.focusedContainer
        val filled = contrast(HomeTvColors.Rating, on)
        assertTrue(filled >= 3.0, "filled star on the focused chip: $filled:1 (needs ≥ 3)")
        val empty = contrast(DETAIL_EMPTY_STAR, on)
        assertTrue(empty >= 3.0, "empty star on the focused chip: $empty:1 (needs ≥ 3)")
        val text = contrast(RatingChipColors.focusedContent, on)
        assertTrue(text >= 4.5, "text on the focused chip: $text:1 (needs ≥ 4.5)")
    }
}
