package com.hometv.tv.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.components.LoadingScreen
import com.hometv.tv.ui.components.RequestFocus
import com.hometv.tv.ui.components.ScreenErrorView
import com.hometv.tv.ui.components.parseColor
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens
import com.hometv.tv.ui.theme.HomeTvFocus

/** (2) "¿Quién está viendo?": up to 2 rows of 6 avatars, focus on the last used profile, OK signs in (no PIN). */
@Composable
fun ProfilePickerScreen(vm: ProfilesViewModel, onSignedIn: () -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    LaunchedEffect(vm) { vm.events.collect { if (it is ProfilesEvent.SignedIn) onSignedIn() } }
    Box(Modifier.fillMaxSize().background(HomeTvColors.Background)) {
        when (val s = state) {
            ProfilesUiState.Loading -> LoadingScreen()
            is ProfilesUiState.Error -> ScreenErrorView(s.error, onRetry = vm::retry, onBack = onBack)
            is ProfilesUiState.Content -> Profiles(s, vm::select)
        }
    }
}

@Composable
private fun Profiles(state: ProfilesUiState.Content, onSelect: (Long) -> Unit) {
    val requesters = rememberFocusRequesters<Long>()
    Column(Modifier.fillMaxSize().padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.profiles_title), style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(bottom = 40.dp))
        state.profiles.chunked(PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                row.forEach { profile ->
                    Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            onClick = { onSelect(profile.id) },
                            modifier = Modifier.size(96.dp).focusRequester(requesters[profile.id]).testTag("profile_${profile.id}"),
                            shape = ClickableSurfaceDefaults.shape(CircleShape),
                            colors = ClickableSurfaceDefaults.colors(containerColor = parseColor(profile.color) ?: HomeTvColors.SurfaceVariant),
                            border = ClickableSurfaceDefaults.border(focusedBorder = HomeTvFocus.circleRing),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = HomeTvDimens.AVATAR_FOCUSED_SCALE),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(profile.initial, style = MaterialTheme.typography.displaySmall)
                            }
                        }
                        Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
        state.message?.let { Text(it.resolve(), color = HomeTvColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 24.dp).testTag("profiles_message")) }
    }
    RequestFocus(state.initialFocusId) { state.initialFocusId?.let { requesters[it] } }
}

private const val PER_ROW = 6
