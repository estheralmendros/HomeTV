package com.hometv.tv.ui.ratings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.hometv.tv.R
import com.hometv.tv.core.util.StarFill
import com.hometv.tv.core.util.Stars
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.text.starsText
import com.hometv.tv.ui.theme.HomeTvColors

/**
 * Five star icons (ratings prd3 FR-T8): full and half ones in [HomeTvColors.Rating], empty outlines in
 * [emptyColor] ([HomeTvColors.Muted]; over artwork the detail block passes a lighter tone); null = five empty stars. One accessible node: "4,5 estrellas" / "Sin puntuación".
 * Inside a control that already says the value ([described] false), the row is hidden from accessibility so TalkBack reads it once.
 */
@Composable
fun StarRow(stars: Stars?, size: Dp, modifier: Modifier = Modifier, spacing: Dp = 2.dp, emptyColor: Color = HomeTvColors.Muted, described: Boolean = true) {
    val description = if (described) starsText(stars).resolve() else null
    Row(
        modifier.clearAndSetSemantics { description?.let { contentDescription = it } },
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stars.fillsOf(stars).forEach { fill ->
            val (icon, tint) = when (fill) {
                StarFill.FULL -> R.drawable.ic_star_full to HomeTvColors.Rating
                StarFill.HALF -> R.drawable.ic_star_half to HomeTvColors.Rating
                StarFill.EMPTY -> R.drawable.ic_star_empty to emptyColor
            }
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size))
        }
    }
}
