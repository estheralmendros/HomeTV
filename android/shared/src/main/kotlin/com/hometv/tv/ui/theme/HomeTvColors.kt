package com.hometv.tv.ui.theme

import androidx.compose.ui.graphics.Color

/** HomeTV colors (dark theme only). */
object HomeTvColors {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF1A1F27)
    val SurfaceVariant = Color(0xFF262C36)
    val Primary = Color(0xFF4FA3FF)

    /** Text and icons drawn on [Primary] (the card badges). */
    val OnPrimary = Color(0xFF06121F)
    val OnBackground = Color(0xFFECEFF4)
    val Muted = Color(0xFF9AA4B2)

    /** Secondary text over artwork (lighter than [Muted]; ≥ 4.5:1 over the hero/detail scrims on any image). */
    val SecondaryOnArt = Color(0xFFC9D1DC)
    val Error = Color(0xFFFF8A80)

    /** The focus ring: light, 3 dp. */
    val FocusRing = Color(0xFFFFFFFF)
    val Progress = Color(0xFFE53935)

    /** Rating stars (amber; ≥ 3:1 on Background and Surface, a graphic). Empty stars use [Muted]. */
    val Rating = Color(0xFFFFC107)
    val Placeholder = Color(0xFF2E3542)

    /** The translucent dark band behind the subtitle style dialog's sample line. */
    val SubtitleSampleBand = Color(0x99000000)
}
