package com.hometv.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
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
import com.hometv.tv.ui.components.SafeAreaBringIntoViewSpec
import com.hometv.tv.ui.components.TextDialog
import com.hometv.tv.ui.components.TopNav
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.components.requestFocusWhenReady
import com.hometv.tv.ui.navigation.AppNavigator
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens

/**
 * Settings / About (admins only): the server change behind the PIN, the profile switch, "Reproducción" (the device
 * switch "Usar siempre VLC (experimental)", only with libVLC) and Acerca de with [Licencias de código abierto].
 */
@OptIn(ExperimentalFoundationApi::class)
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
        // The page runs to the screen's bottom edge (no empty strip); the safe area is padding inside the scroll, and
        // focus scrolling (the last item, [Licencias de código abierto]) stops 27 dp above the edge.
        val density = LocalDensity.current
        val platformSpec = LocalBringIntoViewSpec.current
        val pageSpec = remember(density, platformSpec) { SafeAreaBringIntoViewSpec(0f, with(density) { HomeTvDimens.SafeVertical.toPx() }, base = platformSpec) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings_page")
                    .padding(start = HomeTvDimens.SafeHorizontal, end = HomeTvDimens.SafeHorizontal, top = 16.dp, bottom = 16.dp + HomeTvDimens.SafeVertical),
            ) {
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
                // Admins with libVLC only (a device without it would ignore the switch). No PIN: it only changes how this TV decodes.
                if (state.showForceVlc) {
                    val value = stringResource(if (state.forceVlc) R.string.settings_on else R.string.settings_off)
                    Section(stringResource(R.string.settings_playback), hint = stringResource(R.string.settings_force_vlc_hint)) {
                        ActionButton(
                            stringResource(R.string.settings_force_vlc),
                            vm::onToggleForceVlc,
                            Modifier.focusRequester(focus["vlc"]).testTag("settings_force_vlc"),
                            icon = if (state.forceVlc) R.drawable.ic_check else null,
                            contentDescription = stringResource(R.string.settings_force_vlc_description, value),
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp).testTag("settings_force_vlc_value"))
                    }
                }
                Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                Text(state.appVersion.resolve(), style = MaterialTheme.typography.bodyMedium)
                state.serverVersion?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium) }
                state.caps?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = HomeTvColors.Muted, modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(16.dp))
                Image(painterResource(R.drawable.tmdb_logo), contentDescription = stringResource(R.string.tmdb_logo), modifier = Modifier.width(154.dp))
                state.tmdbAttribution?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HomeTvColors.Muted, modifier = Modifier.padding(top = 8.dp).testTag("tmdb_attribution")) }
                // Every Settings viewer: libVLC ships in every APK, available or not.
                ActionButton(stringResource(R.string.settings_licenses), vm::onOpenLicenses, Modifier.padding(top = 16.dp).focusRequester(focus["licenses"]).testTag("settings_licenses"))
            }
        }
    }
    state.pin?.let { pin -> PinDialog(pin, vm::onPinDigit, vm::onPinDelete, vm::onPinDismiss) }
    // Raw legal text (English, verbatim), not catalog UI text.
    state.licenses?.let { TextDialog(stringResource(R.string.licenses_title), it, vm::onCloseLicenses, textTestTag = "licenses_text") }
    // Closing the licence dialog gives focus back to [Licencias de código abierto].
    val licensesOpen = state.licenses != null
    var licensesWereOpen by remember { mutableStateOf(false) }
    LaunchedEffect(licensesOpen) {
        if (!licensesOpen && licensesWereOpen) focus["licenses"].requestFocusWhenReady()
        licensesWereOpen = licensesOpen
    }
}

/** A titled row of content; an optional muted [hint] line below the row. */
@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HomeTvColors.Muted, modifier = Modifier.padding(top = 8.dp)) }
    }
}
