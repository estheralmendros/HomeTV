package com.hometv.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

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
