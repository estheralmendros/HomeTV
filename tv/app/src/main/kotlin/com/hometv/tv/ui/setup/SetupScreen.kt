package com.hometv.tv.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.common.TvTextField
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.RequestFocus
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors

/** (1) Server setup: URL field (initial focus) pre-filled with `https://`, [Conectar], errors under the field. */
@Composable
fun SetupScreen(vm: SetupViewModel, onConnected: (changed: Boolean) -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val requesters = rememberFocusRequesters<String>()
    BackHandler(onBack = onBack)
    LaunchedEffect(vm) { vm.events.collect { if (it is SetupEvent.Connected) onConnected(it.changed) } }
    Box(Modifier.fillMaxSize().background(HomeTvColors.Background), contentAlignment = Alignment.Center) {
        Column(Modifier.width(720.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.setup_subtitle), style = MaterialTheme.typography.titleMedium, color = HomeTvColors.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 32.dp))
            Text(stringResource(R.string.setup_url_label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            TvTextField(
                value = state.url,
                onValueChange = vm::onUrlChange,
                hint = stringResource(R.string.setup_url_hint),
                imeAction = ImeAction.Done,
                onImeAction = vm::submit,
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.fillMaxWidth().focusRequester(requesters["url"]).testTag("setup_url"),
            )
            state.error?.let { Text(it.resolve(), color = HomeTvColors.Error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) }
            val label = when {
                state.validating -> stringResource(R.string.setup_validating)
                state.needsBootstrap -> stringResource(R.string.action_retry)
                else -> stringResource(R.string.setup_connect)
            }
            ActionButton(label, vm::submit, Modifier.padding(top = 24.dp).testTag("setup_connect"), enabled = !state.validating)
            Text(
                stringResource(R.string.setup_tip),
                style = MaterialTheme.typography.bodySmall,
                color = HomeTvColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 32.dp),
            )
        }
    }
    RequestFocus(Unit) { requesters["url"] }
}
