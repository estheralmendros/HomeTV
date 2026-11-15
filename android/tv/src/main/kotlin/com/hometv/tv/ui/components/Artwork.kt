package com.hometv.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.ImageLoader
import coil.compose.AsyncImage
import com.hometv.tv.ui.theme.HomeTvColors

/** API-relative artwork path → absolute URL (`AppContainer.artworkUrl`). */
val LocalArtwork = staticCompositionLocalOf<(String?) -> String?> { { null } }

/** Coil loader on the API OkHttpClient (Bearer header); null in previews/tests without images. */
val LocalAppImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

/**
 * An image from the server over a colored placeholder that shows [fallbackTitle]
 * (missing images → colored placeholder with title text), or [placeholder] instead (the Person
 * screen's initial). A failed load leaves the placeholder.
 */
@Composable
fun Artwork(
    path: String?,
    fallbackTitle: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: (@Composable () -> Unit)? = null,
) {
    val url = LocalArtwork.current(path)
    val loader = LocalAppImageLoader.current
    Box(modifier.background(HomeTvColors.Placeholder), contentAlignment = Alignment.Center) {
        if (placeholder != null) {
            placeholder()
        } else if (fallbackTitle != null) {
            Text(
                fallbackTitle,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = HomeTvColors.Muted,
                modifier = Modifier.padding(8.dp),
            )
        }
        if (url != null && loader != null) {
            AsyncImage(model = url, contentDescription = null, imageLoader = loader, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}
