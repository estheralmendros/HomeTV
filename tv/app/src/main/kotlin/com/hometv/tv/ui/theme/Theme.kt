package com.hometv.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

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

    /** The focus ring: light, 3 dp (prd3 Focus & navigation AC). */
    val FocusRing = Color(0xFFFFFFFF)
    val Progress = Color(0xFFE53935)
    val Placeholder = Color(0xFF2E3542)
}

/** Safe-area padding (48 dp horizontal / 27 dp vertical) and focus visuals shared by the screens. */
object HomeTvDimens {
    val SafeHorizontal = 48.dp
    val SafeVertical = 27.dp
    val FocusRingWidth = 3.dp
    const val FOCUSED_SCALE = 1.08f

    /** Focus scale of a wide block (the Person biography): [FOCUSED_SCALE] would push it past the 48 dp margin. */
    const val WIDE_FOCUSED_SCALE = 1.02f
    const val AVATAR_FOCUSED_SCALE = 1.1f
}

object HomeTvFocus {
    val ring = Border(BorderStroke(HomeTvDimens.FocusRingWidth, HomeTvColors.FocusRing), shape = RoundedCornerShape(8.dp))
    val circleRing = Border(BorderStroke(HomeTvDimens.FocusRingWidth, HomeTvColors.FocusRing), shape = CircleShape)
}

private val colors = darkColorScheme(
    primary = HomeTvColors.Primary,
    onPrimary = HomeTvColors.OnPrimary,
    // Used by the tv-material TabRow: the selected tab's label and the dimmed pill (no library default colors).
    onPrimaryContainer = HomeTvColors.OnBackground,
    secondaryContainer = HomeTvColors.SurfaceVariant,
    background = HomeTvColors.Background,
    surface = HomeTvColors.Surface,
    surfaceVariant = HomeTvColors.SurfaceVariant,
    onBackground = HomeTvColors.OnBackground,
    onSurface = HomeTvColors.OnBackground,
    error = HomeTvColors.Error,
)

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 15.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
)

/**
 * The screens paint the background themselves (no root `Surface`), so the default text color is
 * provided here: without it a `Text` with no `color` falls back to Compose's black.
 */
@Composable
fun HomeTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalContentColor provides HomeTvColors.OnBackground, content = content)
    }
}
