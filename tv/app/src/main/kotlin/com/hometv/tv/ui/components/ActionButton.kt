package com.hometv.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.hometv.tv.ui.theme.HomeTvDimens
import com.hometv.tv.ui.theme.HomeTvFocus

/**
 * A tv-material button with the app's focus visuals (scale + 3 dp ring) and an optional leading icon.
 * [contentDescription] replaces the accessible name when it differs from [text] ("Mi lista" → "Añadir a Mi lista").
 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    onLongClick: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = if (contentDescription == null) modifier else modifier.semantics { this.contentDescription = contentDescription },
        enabled = enabled,
        scale = ButtonDefaults.scale(focusedScale = HomeTvDimens.FOCUSED_SCALE),
        border = ButtonDefaults.border(focusedBorder = Border(HomeTvFocus.ring.border, shape = RoundedCornerShape(percent = 50))),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}
