package com.hometv.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.AvatarUi
import com.hometv.tv.ui.components.NavTab
import com.hometv.tv.ui.components.TopNav
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.components.requestFocusWhenReady
import com.hometv.tv.ui.navigation.AppNavigator
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens

/** Settings / About. The only admin feature: the server change, admins only, behind the PIN. */
@Composable
fun SettingsScreen(vm: SettingsViewModel, navigator: AppNavigator, avatar: AvatarUi?, showSettings: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tabs = rememberFocusRequesters<NavTab>()
    val focus = rememberFocusRequesters<String>()
    BackHandler { navigator.backToHome(NavTab.SETTINGS) }
    LaunchedEffect(vm) { vm.changeServer.collect { navigator.changeServer() } }
    // Viewers have no Settings screen: a profile demoted while it is open (the `/api/me`
    // refresh of `SettingsViewModel`) goes back to Home, focus on "Inicio" (the bar no longer has "Ajustes").
    LaunchedEffect(showSettings) { if (!showSettings) navigator.backToHome(NavTab.HOME) }
    // Initial focus: [Cambiar servidor]. The [Cambiar de perfil] fallback only covers the moment before a
    // demotion leaves the screen; `canChangeServer` stays as a guard in depth.
    LaunchedEffect(state.canChangeServer) { focus[if (state.canChangeServer) "server" else "profile"].requestFocusWhenReady() }
    Column(Modifier.fillMaxSize().background(HomeTvColors.Background)) {
        TopNav(NavTab.SETTINGS, navigator::openTab, avatar, navigator::switchProfile, tabs, showSettings)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 16.dp)) {
            Section(stringResource(R.string.settings_server)) {
                Text(state.serverUrl, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(520.dp))
                if (state.canChangeServer) {
                    ActionButton(stringResource(R.string.settings_change_server), vm::onChangeServer, Modifier.focusRequester(focus["server"]).testTag("settings_change_server"))
                }
            }
            Section(stringResource(R.string.settings_profile)) {
                Text(state.profileName.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(520.dp))
                ActionButton(stringResource(R.string.settings_switch_profile), navigator::switchProfile, Modifier.focusRequester(focus["profile"]).testTag("settings_switch_profile"))
            }
            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
            Text(state.appVersion.resolve(), style = MaterialTheme.typography.bodyMedium)
            state.serverVersion?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium) }
            state.caps?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = HomeTvColors.Muted, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(16.dp))
            Image(painterResource(R.drawable.tmdb_logo), contentDescription = stringResource(R.string.tmdb_logo), modifier = Modifier.width(154.dp))
            state.tmdbAttribution?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HomeTvColors.Muted, modifier = Modifier.padding(top = 8.dp).testTag("tmdb_attribution")) }
        }
    }
    state.pin?.let { pin -> PinDialog(pin, vm::onPinDigit, vm::onPinDelete, vm::onPinDismiss) }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) { content() }
}
