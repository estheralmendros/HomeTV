package com.hometv.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens
import com.hometv.tv.ui.theme.HomeTvFocus

private val PosterWidth = 132.dp
private val PosterHeight = 198.dp
private val LandscapeWidth = 240.dp
private val LandscapeHeight = 135.dp

/** The focus visuals of every card: scale 1.08 and a 3 dp light ring. */
@Composable
private fun FocusCard(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    width: Dp,
    height: Dp,
    modifier: Modifier,
    onFocused: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(width, height).onFocusChanged { if (it.isFocused) onFocused() },
        scale = CardDefaults.scale(focusedScale = HomeTvDimens.FOCUSED_SCALE),
        border = CardDefaults.border(focusedBorder = HomeTvFocus.ring),
        shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
    ) { content() }
}

/** Poster card (rows and grids): poster, title below (marquee when focused), year; progress / watched check / badge. */
@Composable
fun PosterCard(
    title: String,
    year: String?,
    image: String?,
    progress: Float?,
    watched: Boolean,
    badge: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(PosterWidth)) {
        FocusCard(onClick, onLongClick, PosterWidth, PosterHeight, modifier.onFocusChanged { focused = it.isFocused }, onFocused) {
            Box(Modifier.fillMaxSize()) {
                Artwork(image, title, Modifier.fillMaxSize())
                CardBadges(progress, watched, badge)
            }
        }
        Text(
            title,
            maxLines = 1,
            overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp).then(if (focused) Modifier.basicMarquee() else Modifier),
        )
        if (year != null) Text(year, style = MaterialTheme.typography.bodySmall, color = HomeTvColors.Muted)
    }
}

/** Landscape card (Seguir viendo / A continuación): backdrop or still with the progress strip, title and subtitle below. */
@Composable
fun LandscapeCard(
    title: String,
    subtitle: String?,
    image: String?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
) {
    Column(Modifier.width(LandscapeWidth)) {
        FocusCard(onClick, onLongClick, LandscapeWidth, LandscapeHeight, modifier, onFocused) {
            Box(Modifier.fillMaxSize()) {
                Artwork(image, title, Modifier.fillMaxSize())
                if (progress != null) ProgressStrip(progress, Modifier.align(Alignment.BottomStart))
            }
        }
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = HomeTvColors.Muted)
    }
}

/** The trailing "Ver todo" card of the Películas / Series rows. */
@Composable
fun SeeAllCard(onClick: () -> Unit, modifier: Modifier = Modifier, onFocused: () -> Unit = {}) {
    Column(Modifier.width(PosterWidth)) {
        FocusCard(onClick, null, PosterWidth, PosterHeight, modifier, onFocused) {
            Box(Modifier.fillMaxSize().background(HomeTvColors.SurfaceVariant), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.home_see_all), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CardBadges(progress: Float?, watched: Boolean, badge: String?) {
    if (progress != null) ProgressStrip(progress, Modifier.align(Alignment.BottomStart))
    if (watched) {
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = stringResource(R.string.watched),
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(HomeTvColors.Primary, RoundedCornerShape(12.dp)).padding(3.dp),
            tint = HomeTvColors.OnPrimary,
        )
    }
    if (badge != null) {
        Text(
            badge,
            style = MaterialTheme.typography.labelLarge,
            color = HomeTvColors.OnPrimary,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(HomeTvColors.Primary, RoundedCornerShape(10.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}
