package com.hometv.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.hometv.tv.ui.components.Artwork
import com.hometv.tv.ui.theme.HomeTvColors

/** Backdrop (`large`) with a gradient into the background. */
@Composable
fun Backdrop(path: String?, height: Dp) {
    Box(Modifier.fillMaxWidth().height(height)) {
        if (path != null) Artwork(path, null, Modifier.fillMaxWidth().height(height))
        Box(
            Modifier.fillMaxWidth().height(height).background(
                Brush.verticalGradient(listOf(Color.Transparent, HomeTvColors.Background.copy(alpha = 0.7f), HomeTvColors.Background)),
            ),
        )
    }
}
