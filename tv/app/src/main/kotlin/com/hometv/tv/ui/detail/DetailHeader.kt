package com.hometv.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.hometv.tv.ui.components.Artwork
import com.hometv.tv.ui.theme.HomeTvColors

/** Text drawn over the detail backdrop (prd3 Theme & legibility): the secondary lines (metadata). */
object DetailText {
    /** Lighter than [HomeTvColors.Muted]: ≥ 4.5:1 over the scrim even where the artwork is white. */
    val secondary: Color = HomeTvColors.SecondaryOnArt
}

/**
 * Opacity of the background-colored scrim over the whole backdrop, under the header text: the art
 * stays visible, dimmed, and white or [DetailText.secondary] text reaches 4.5:1 over any pixel.
 */
const val BACKDROP_SCRIM_ALPHA = 0.7f

/** Backdrop (`large`) under a [BACKDROP_SCRIM_ALPHA] scrim, with a gradient into the background. */
@Composable
fun Backdrop(path: String?, height: Dp) {
    Box(Modifier.fillMaxWidth().height(height)) {
        if (path != null) Artwork(path, null, Modifier.fillMaxWidth().height(height))
        Box(Modifier.fillMaxWidth().height(height).background(HomeTvColors.Background.copy(alpha = BACKDROP_SCRIM_ALPHA)))
        Box(
            Modifier.fillMaxWidth().height(height).background(
                Brush.verticalGradient(listOf(Color.Transparent, HomeTvColors.Background.copy(alpha = 0.7f), HomeTvColors.Background)),
            ),
        )
    }
}
