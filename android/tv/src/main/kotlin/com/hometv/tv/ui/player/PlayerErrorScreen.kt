package com.hometv.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.hometv.tv.core.playback.PlayerAction
import com.hometv.tv.ui.components.ErrorAction
import com.hometv.tv.ui.components.ErrorScreen
import com.hometv.tv.ui.text.UiFormat
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors

/**
 * A full-screen player error: the catalog message, the reason line (unsupported reasons),
 * and the buttons chosen by `PlaybackErrorMapper` ([Reintentar] / [Reintentar desde aquí] /
 * [Probar otra versión] / [Volver]; the first one is focused). Never server text.
 */
@Composable
fun PlayerErrorScreen(error: PlayerScreenUi.Error, onAction: (PlayerAction) -> Unit) {
    val actions = error.actions.map { action -> ErrorAction(stringResource(UiFormat.actionRes(action))) { onAction(action) } }
    ErrorScreen(error.message.resolve(), actions, Modifier.background(HomeTvColors.Background).testTag("player_error"), detail = error.reason?.resolve())
}
