package com.hometv.tv.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.theme.HomeTvColors

/** A button of an error screen; the first one gets the initial focus. */
data class ErrorAction(val label: String, val onClick: () -> Unit)

/** Full-screen message with buttons (never a blank screen, never server text). */
@Composable
fun ErrorScreen(message: String, actions: List<ErrorAction>, modifier: Modifier = Modifier, detail: String? = null) {
    val requesters = rememberFocusRequesters<Int>()
    Box(modifier.fillMaxSize().testTag("error_screen"), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = stringResource(R.string.error_icon), modifier = Modifier.size(48.dp), tint = HomeTvColors.Error)
            Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = HomeTvColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                actions.forEachIndexed { i, action ->
                    ActionButton(action.label, action.onClick, Modifier.focusRequesterOf(requesters[i]))
                }
            }
        }
    }
    RequestFocus(actions.size) { requesters[0] }
}

/** The static loading state (no infinite animation: screens stay testable). */
@Composable
fun LoadingScreen(modifier: Modifier = Modifier, text: String = stringResource(R.string.loading)) {
    Box(modifier.fillMaxSize().testTag("loading"), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = HomeTvColors.Muted)
    }
}

/** Tailscale's TV launch intent, or null when the app is not installed. */
fun tailscaleIntent(context: Context): Intent? {
    val pm = context.packageManager
    return pm.getLeanbackLaunchIntentForPackage(TAILSCALE_PACKAGE) ?: pm.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
}

const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

/** Connection Error (prd3 §10): [Reintentar] (focused), [Abrir Tailscale] (only when installed), [Cambiar servidor]. */
@Composable
fun ConnectionErrorScreen(error: ScreenError.Connection, onRetry: () -> Unit, onChangeServer: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tailscale = remember { tailscaleIntent(context) }
    val message = if (error.tls) stringResource(R.string.setup_error_tls) else stringResource(R.string.error_connection, error.host)
    val actions = buildList {
        add(ErrorAction(stringResource(R.string.action_retry), onRetry))
        if (tailscale != null) add(ErrorAction(stringResource(R.string.error_open_tailscale)) { runCatching { context.startActivity(tailscale) } })
        add(ErrorAction(stringResource(R.string.settings_change_server), onChangeServer))
    }
    ErrorScreen(message, actions, modifier)
}

/** Renders a [ScreenError] with the screen's own retry/back behavior. */
@Composable
fun ScreenErrorView(error: ScreenError, onRetry: () -> Unit, onBack: () -> Unit, onChangeServer: () -> Unit, modifier: Modifier = Modifier) {
    when (error) {
        is ScreenError.Connection -> ConnectionErrorScreen(error, onRetry, onChangeServer, modifier)
        ScreenError.ItemGone -> ErrorScreen(stringResource(R.string.item_gone), listOf(ErrorAction(stringResource(R.string.action_back), onBack)), modifier)
        ScreenError.SignIn -> ErrorScreen(stringResource(R.string.error_sign_in), listOf(ErrorAction(stringResource(R.string.action_retry), onRetry)), modifier)
        is ScreenError.Unexpected -> ErrorScreen(stringResource(R.string.error_unexpected, error.code), listOf(ErrorAction(stringResource(R.string.action_retry), onRetry)), modifier)
        ScreenError.LoadFailed -> ErrorScreen(stringResource(R.string.error_load_failed), listOf(ErrorAction(stringResource(R.string.action_retry), onRetry)), modifier)
        ScreenError.ProfileGone -> LoadingScreen(modifier)
    }
}
