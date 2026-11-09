@file:OptIn(UnstableApi::class)

package com.hometv.tv.playback

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.hometv.tv.core.playback.SubtitleEdge
import com.hometv.tv.core.playback.SubtitleLook

/** What `ExoSurface` sets on the `SubtitleView`: opaque ARGB colours, a `CaptionStyleCompat.EDGE_TYPE_*`, fractions of the view height. */
data class ExoCaptionSpec(
    val foreground: Int,
    val edgeType: Int,
    val edgeColor: Int,
    val textSizeFraction: Float,
    val bottomPaddingFraction: Float,
)

/** The profile's subtitle look on ExoPlayer (`specs/subtitles-improvements/prd.md` FR-6); the device caption settings are ignored. */
object ExoSubtitleStyle {
    private const val OPAQUE = 0xFF000000.toInt()

    fun of(look: SubtitleLook): ExoCaptionSpec = ExoCaptionSpec(
        foreground = OPAQUE or look.color.rgb,
        edgeType = when (look.edge) {
            SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            SubtitleEdge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
        },
        edgeColor = OPAQUE or look.effectiveEdgeColor.rgb,
        textSizeFraction = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * look.size.factor,
        bottomPaddingFraction = look.position.bottomFraction,
    )
}

/** Transparent background and window, the default typeface. */
fun ExoCaptionSpec.toCaptionStyle(): CaptionStyleCompat =
    CaptionStyleCompat(foreground, Color.TRANSPARENT, Color.TRANSPARENT, edgeType, edgeColor, null)
