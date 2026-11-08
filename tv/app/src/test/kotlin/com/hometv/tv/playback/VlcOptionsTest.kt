package com.hometv.tv.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AC-7.2: the LibVLC instance and per-media options. */
class VlcOptionsTest {
    @Test
    fun `instance options keep libVLC quiet, reconnect, never pass audio through and style the subtitles`() {
        val options = VlcOptions.libVlc(1080)
        assertTrue("--sub-margin=86" in options, "$options")
        assertTrue("--http-reconnect" in options, "$options")
        // Quiet: no -v / -vv, and verbosity -1 (libVLC's Android logger opens nothing below 0).
        assertTrue(options.none { it.startsWith("-v") }, "$options")
        assertEquals(listOf("--verbose=-1"), options.filter { it.startsWith("--verbose") })
        assertTrue(options.none { it.startsWith("--spdif") }, "$options")
        val style = listOf("--freetype-color=16777215", "--freetype-outline-thickness=4", "--freetype-outline-color=0", "--freetype-rel-fontsize=19")
        assertTrue(options.containsAll(style), "$options")
        assertTrue("--sub-margin=58" in VlcOptions.libVlc(720), "8 % of 720 px, rounded")
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
