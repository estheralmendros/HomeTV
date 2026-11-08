package com.hometv.tv.playback

import java.util.Locale
import kotlin.math.roundToInt

/** LibVLC instance and per-media options. */
object VlcOptions {
    /** Media3's `SUBTITLE_BOTTOM_PADDING` in `PlayerScreen` (8 % of the height). */
    private const val SUBTITLE_BOTTOM_FRACTION = 0.08

    /**
     * LibVLC instance options: quiet (`--verbose=-1`: libVLC's Android logger opens nothing below 0, so libVLC
     * writes nothing to logcat; no -v/-vv, no log listener), `--http-reconnect`, no passthrough (`--spdif`
     * not set), and the subtitle style of today's `SubtitleView` (white text, black outline, ≈ 5.3 % of the
     * video height, 8 % bottom margin). `--sub-margin` counts the pixels of the picture libVLC renders the
     * subtitles into (the displayed size for a video up to the display's resolution).
     */
    fun libVlc(displayHeightPx: Int): ArrayList<String> = arrayListOf(
        "--verbose=-1",
        "--http-reconnect",
        "--freetype-color=16777215",
        "--freetype-outline-thickness=4",
        "--freetype-outline-color=0",
        "--freetype-rel-fontsize=19",
        "--sub-margin=${(SUBTITLE_BOTTOM_FRACTION * displayHeightPx).roundToInt()}",
    )

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
