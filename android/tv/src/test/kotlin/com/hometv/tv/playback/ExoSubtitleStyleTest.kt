package com.hometv.tv.playback

import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.hometv.tv.core.playback.SubtitleColor
import com.hometv.tv.core.playback.SubtitleEdge
import com.hometv.tv.core.playback.SubtitleEdgeColor
import com.hometv.tv.core.playback.SubtitleLook
import com.hometv.tv.core.playback.SubtitlePosition
import com.hometv.tv.core.playback.SubtitleSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** prd3 T-AC-10: the profile's look on ExoPlayer's `SubtitleView` (prd.md FR-6), never the device caption settings. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ExoSubtitleStyleTest {
    private val default = SubtitleLook.DEFAULT

    @Test
    fun `the default look is opaque white text with a black outline at 8 percent`() {
        assertEquals(
            ExoCaptionSpec(0xFFFFFFFF.toInt(), CaptionStyleCompat.EDGE_TYPE_OUTLINE, 0xFF000000.toInt(), SubtitleView.DEFAULT_TEXT_SIZE_FRACTION, 0.08f),
            ExoSubtitleStyle.of(default),
        )
    }

    @Test
    fun `every colour is opaque`() {
        assertEquals(
            listOf(0xFFFFFFFF, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFFFF00FF, 0xFFFFA500).map { it.toInt() },
            SubtitleColor.entries.map { ExoSubtitleStyle.of(default.copy(color = it)).foreground },
        )
    }

    @Test
    fun `every size scales the default text size fraction`() {
        assertEquals(
            listOf(0.8f, 1f, 1.3f).map { it * SubtitleView.DEFAULT_TEXT_SIZE_FRACTION },
            SubtitleSize.entries.map { ExoSubtitleStyle.of(default.copy(size = it)).textSizeFraction },
        )
    }

    @Test
    fun `every edge, edge colour and position, and a stored clash drawn with black`() {
        assertEquals(
            listOf(CaptionStyleCompat.EDGE_TYPE_OUTLINE, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, CaptionStyleCompat.EDGE_TYPE_NONE),
            SubtitleEdge.entries.map { ExoSubtitleStyle.of(default.copy(edge = it)).edgeType },
        )
        val yellow = default.copy(color = SubtitleColor.YELLOW)
        assertEquals(
            listOf(0xFF000000, 0xFF404040, 0xFFFFFFFF).map { it.toInt() },
            SubtitleEdgeColor.entries.map { ExoSubtitleStyle.of(yellow.copy(edgeColor = it)).edgeColor },
        )
        assertEquals(0xFF000000.toInt(), ExoSubtitleStyle.of(default.copy(edgeColor = SubtitleEdgeColor.WHITE)).edgeColor)
        assertEquals(listOf(0.08f, 0.14f, 0.20f), SubtitlePosition.entries.map { ExoSubtitleStyle.of(default.copy(position = it)).bottomPaddingFraction })
    }

    @Test
    fun `the caption style has transparent background and window and the default typeface`() {
        val style = ExoSubtitleStyle.of(default.copy(color = SubtitleColor.CYAN, edge = SubtitleEdge.SHADOW)).toCaptionStyle()
        assertEquals(0xFF00FFFF.toInt(), style.foregroundColor)
        assertEquals(Color.TRANSPARENT, style.backgroundColor)
        assertEquals(Color.TRANSPARENT, style.windowColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, style.edgeType)
        assertEquals(0xFF000000.toInt(), style.edgeColor)
        assertNull(style.typeface)
    }
}
