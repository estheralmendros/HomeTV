package com.hometv.tv.ui.ratings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.core.util.Format
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.Artwork
import com.hometv.tv.ui.components.RequestFocus
import com.hometv.tv.ui.components.requestFocusWhenReady
import com.hometv.tv.ui.ratings.RatingDialogUi.Mode
import com.hometv.tv.ui.ratings.RatingDialogUi.Phase
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.text.starsText
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvFocus

/**
 * The D-pad rating dialog, stateless: a [RatingEditor] owns [ui]. Poster and the
 * right column (title, "¿Qué te ha parecido?", the star row, the error line) on top; under them one
 * full-width button row (or "Puntuación guardada" while SAVED). The star row has initial focus:
 * Left/Right step ½, OK saves; Down goes to the first button, Up from a button returns. Back is
 * [onDismiss] (Ahora no / Cancelar; nothing while SAVING; closes at once once SAVED).
 */
@Composable
fun RatingDialog(
    ui: RatingDialogUi,
    onStep: (Int) -> Unit,
    onSave: () -> Unit,
    onSecondary: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val starsFocus = remember { FocusRequester() }
    val firstButton = remember { FocusRequester() }
    val secondButton = remember { FocusRequester() }
    val saving = ui.phase == Phase.SAVING
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(DIALOG_WIDTH).testTag("rating_dialog").background(HomeTvColors.Surface, RoundedCornerShape(12.dp)).padding(24.dp)) {
            Row {
                Artwork(ui.poster, ui.title, Modifier.size(POSTER_WIDTH, POSTER_HEIGHT).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.padding(start = 24.dp).weight(1f)) {
                    Text(ui.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("rating_title"))
                    if (ui.mode == Mode.PROMPT && ui.phase != Phase.SAVED) {
                        Text(stringResource(R.string.rating_question), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp).testTag("rating_question"))
                    }
                    val description = starsText(ui.stars).resolve()
                    Surface(
                        onClick = { if (ui.stars != null) onSave() },
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .focusRequester(starsFocus)
                            // The first enabled button: [Guardar] needs a value.
                            .focusProperties { down = if (ui.stars != null) firstButton else secondButton }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionLeft -> onStep(-1)
                                    Key.DirectionRight -> onStep(1)
                                    else -> return@onPreviewKeyEvent false
                                }
                                true
                            }
                            .semantics { contentDescription = description }
                            .testTag("rating_stars"),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = HomeTvColors.SurfaceVariant),
                        border = ClickableSurfaceDefaults.border(focusedBorder = HomeTvFocus.ring),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            StarRow(ui.stars, STAR_SIZE, spacing = 4.dp, described = false)
                            Text(
                                ui.stars?.let(Format::stars) ?: stringResource(R.string.rating_empty_value),
                                style = MaterialTheme.typography.titleMedium,
                                color = HomeTvColors.OnBackground,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                    if (ui.phase == Phase.FAILED) {
                        Text(
                            stringResource(R.string.rating_save_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = HomeTvColors.Error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).testTag("rating_error"),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (ui.phase == Phase.SAVED) {
                Box(Modifier.height(BUTTON_ROW_HEIGHT).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.rating_saved), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("rating_saved"))
                }
            } else {
                // One line in every mode and phase: the fit test fails if a future label no longer fits.
                Row(Modifier.fillMaxWidth().testTag("rating_buttons"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val up = Modifier.focusProperties { up = starsFocus }
                    val failed = ui.phase == Phase.FAILED
                    ActionButton(
                        stringResource(if (failed) R.string.action_retry else R.string.rating_save),
                        onSave,
                        up.focusRequester(firstButton).testTag(if (failed) "rating_retry" else "rating_save"),
                        enabled = ui.stars != null && !saving,
                    )
                    if (ui.mode == Mode.PROMPT) {
                        ActionButton(stringResource(R.string.rating_not_now), onSecondary, up.focusRequester(secondButton).testTag("rating_not_now"), enabled = !saving)
                    } else {
                        ActionButton(stringResource(R.string.action_cancel), onSecondary, up.focusRequester(secondButton).testTag("rating_cancel"), enabled = !saving)
                        if (ui.canRemove) ActionButton(stringResource(R.string.rating_remove), onRemove, up.testTag("rating_remove"), enabled = !saving)
                    }
                }
            }
        }
    }
    RequestFocus(Unit) { starsFocus }
    // SAVED: the buttons are gone, the stars keep focus (Back reaches the dialog). FAILED: [Reintentar].
    LaunchedEffect(ui.phase) {
        when (ui.phase) {
            Phase.SAVED -> starsFocus.requestFocusWhenReady()
            Phase.FAILED -> firstButton.requestFocusWhenReady()
            else -> Unit
        }
    }
}

private val DIALOG_WIDTH = 520.dp
private val POSTER_WIDTH = 96.dp
private val POSTER_HEIGHT = 144.dp
private val STAR_SIZE = 40.dp

/** The button row's height, kept by "Puntuación guardada" so the dialog does not jump. */
private val BUTTON_ROW_HEIGHT = 48.dp
