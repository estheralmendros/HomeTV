package com.hometv.tv.playback

import com.hometv.tv.core.playback.SubtitleEdge
import com.hometv.tv.core.playback.SubtitleLook
import com.hometv.tv.core.playback.SubtitleSize
import java.util.Locale
import kotlin.math.roundToInt

/** LibVLC instance and per-media options. */
object VlcOptions {
    /**
     * LibVLC instance options: quiet (`--verbose=-1`: libVLC's Android logger opens nothing below 0, so libVLC
     * writes nothing to logcat; no -v/-vv, no log listener), `--http-reconnect`, no passthrough (`--spdif`
     * not set), and the profile's subtitle [look] (`specs/subtitles-improvements/prd.md` FR-6: colour,
     * `rel-fontsize` 24/19/15 (a divisor), outline / shadow / none in the effective edge colour, always an
     * explicit shadow opacity, `--sub-margin` = the position's fraction of [displayHeightPx], rounded).
     * `--sub-margin` counts the pixels of the picture libVLC renders the subtitles into (the displayed size
     * for a video up to the display's resolution). The options are instance options: a new look needs a new
     * LibVLC (`LibVlcProvider.get(options)`).
     */
    fun libVlc(displayHeightPx: Int, look: SubtitleLook): ArrayList<String> = arrayListOf(
        "--verbose=-1",
        "--http-reconnect",
        "--freetype-color=${look.color.rgb}",
        "--freetype-rel-fontsize=${relFontSize(look.size)}",
    ).apply {
        val edge = look.effectiveEdgeColor.rgb
        when (look.edge) {
            SubtitleEdge.OUTLINE -> addAll(listOf("--freetype-outline-thickness=4", "--freetype-outline-color=$edge", "--freetype-shadow-opacity=0"))
            SubtitleEdge.SHADOW -> addAll(
                listOf(
                    "--freetype-outline-thickness=0", "--freetype-shadow-opacity=255", "--freetype-shadow-color=$edge",
                    "--freetype-shadow-angle=-45", "--freetype-shadow-distance=0.07",
                ),
            )
            SubtitleEdge.NONE -> addAll(listOf("--freetype-outline-thickness=0", "--freetype-shadow-opacity=0"))
        }
        add("--sub-margin=${(look.position.bottomFraction.toDouble() * displayHeightPx).roundToInt()}")
    }

    /** libVLC's relative font size divides the picture height: bigger = smaller text. */
    private fun relFontSize(size: SubtitleSize): Int = when (size) {
        SubtitleSize.SMALL -> 24
        SubtitleSize.MEDIUM -> 19
        SubtitleSize.LARGE -> 15
    }

    /**
     * Per-media options: `:network-caching=1500` (≈ Exo's 1.5 s start buffer), `:input-fast-seek`,
     * `:start-time=<seconds with 3 decimals>` when [startMs] > 0, and `:start-paused` for a paused open (the
     * input pauses itself at the start: no burst of audio before the first `Playing` event).
     */
    fun media(startMs: Long, startPaused: Boolean = false): List<String> = buildList {
        add(":network-caching=1500")
        add(":input-fast-seek")
        if (startMs > 0) add(":start-time=${String.format(Locale.ROOT, "%d.%03d", startMs / 1000, startMs % 1000)}")
        if (startPaused) add(":start-paused")
    }
}
