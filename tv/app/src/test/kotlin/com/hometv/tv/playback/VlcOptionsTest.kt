package com.hometv.tv.playback

import com.hometv.tv.core.playback.SubtitleColor
import com.hometv.tv.core.playback.SubtitleEdge
import com.hometv.tv.core.playback.SubtitleEdgeColor
import com.hometv.tv.core.playback.SubtitleLook
import com.hometv.tv.core.playback.SubtitlePosition
import com.hometv.tv.core.playback.SubtitleSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AC-7.2: the LibVLC instance and per-media options. */
class VlcOptionsTest {
    private val default = SubtitleLook.DEFAULT

    @Test
    fun `instance options keep libVLC quiet, reconnect, never pass audio through and style the subtitles`() {
        val options = VlcOptions.libVlc(1080, default)
        assertTrue("--sub-margin=86" in options, "$options")
        assertTrue("--http-reconnect" in options, "$options")
        // Quiet: no -v / -vv, and verbosity -1 (libVLC's Android logger opens nothing below 0).
        assertTrue(options.none { it.startsWith("-v") }, "$options")
        assertEquals(listOf("--verbose=-1"), options.filter { it.startsWith("--verbose") })
        assertTrue(options.none { it.startsWith("--spdif") }, "$options")
        val style = listOf("--freetype-color=16777215", "--freetype-outline-thickness=4", "--freetype-outline-color=0", "--freetype-rel-fontsize=19")
        assertTrue(options.containsAll(style), "$options")
        assertTrue("--sub-margin=58" in VlcOptions.libVlc(720, default), "8 % of 720 px, rounded")
    }

    @Test
    fun `the default look is today's options plus an explicit shadow opacity of 0`() {
        assertEquals(
            listOf(
                "--verbose=-1", "--http-reconnect", "--freetype-color=16777215", "--freetype-rel-fontsize=19",
                "--freetype-outline-thickness=4", "--freetype-outline-color=0", "--freetype-shadow-opacity=0", "--sub-margin=86",
            ),
            VlcOptions.libVlc(1080, default),
        )
    }

    private fun style(look: SubtitleLook) = VlcOptions.libVlc(1000, look).filter { it.startsWith("--freetype") || it.startsWith("--sub-margin") }

    @Test
    fun `every size and colour (prd md FR-6)`() {
        assertEquals(listOf(24, 19, 15), SubtitleSize.entries.map { s -> style(default.copy(size = s)).single { it.startsWith("--freetype-rel-fontsize=") }.substringAfter('=').toInt() })
        assertEquals(
            listOf(16777215, 16776960, 65280, 65535, 16711935, 16753920),
            SubtitleColor.entries.map { c -> style(default.copy(color = c)).single { it.startsWith("--freetype-color=") }.substringAfter('=').toInt() },
        )
    }

    @Test
    fun `every edge and edge colour, always with an explicit shadow opacity`() {
        val yellow = default.copy(color = SubtitleColor.YELLOW)
        assertEquals(
            listOf("--freetype-color=16776960", "--freetype-rel-fontsize=19", "--freetype-outline-thickness=4", "--freetype-outline-color=4210752", "--freetype-shadow-opacity=0", "--sub-margin=80"),
            style(yellow.copy(edgeColor = SubtitleEdgeColor.DARK_GRAY)),
        )
        assertEquals(
            listOf(
                "--freetype-color=16776960", "--freetype-rel-fontsize=19", "--freetype-outline-thickness=0", "--freetype-shadow-opacity=255",
                "--freetype-shadow-color=16777215", "--freetype-shadow-angle=-45", "--freetype-shadow-distance=0.07", "--sub-margin=80",
            ),
            style(yellow.copy(edge = SubtitleEdge.SHADOW, edgeColor = SubtitleEdgeColor.WHITE)),
        )
        assertEquals(
            listOf("--freetype-color=16776960", "--freetype-rel-fontsize=19", "--freetype-outline-thickness=0", "--freetype-shadow-opacity=0", "--sub-margin=80"),
            style(yellow.copy(edge = SubtitleEdge.NONE)),
        )
        // A stored clash (white on white) is drawn with black.
        assertTrue("--freetype-outline-color=0" in style(default.copy(edgeColor = SubtitleEdgeColor.WHITE)))
        assertTrue("--freetype-shadow-color=0" in style(default.copy(edge = SubtitleEdge.SHADOW, edgeColor = SubtitleEdgeColor.WHITE)))
    }

    @Test
    fun `every position sets the sub margin from the display height, rounded`() {
        assertEquals(listOf("--sub-margin=86", "--sub-margin=151", "--sub-margin=216"), SubtitlePosition.entries.map { p -> VlcOptions.libVlc(1080, default.copy(position = p)).last() })
        assertEquals("--sub-margin=101", VlcOptions.libVlc(720, default.copy(position = SubtitlePosition.MIDDLE)).last())
    }

    @Test
    fun `media options cache like ExoPlayer, seek fast and start at the position`() {
        val start = VlcOptions.media(0)
        assertTrue(":network-caching=1500" in start, "$start")
        assertTrue(":input-fast-seek" in start, "$start")
        assertTrue(start.none { it.startsWith(":start-time") }, "$start")
        assertTrue(":start-time=600.500" in VlcOptions.media(600_500))
        assertTrue(":start-time=0.001" in VlcOptions.media(1))
    }

    @Test
    fun `a paused open adds start-paused`() {
        assertFalse(":start-paused" in VlcOptions.media(5_000))
        assertTrue(":start-paused" in VlcOptions.media(5_000, startPaused = true))
    }
}
