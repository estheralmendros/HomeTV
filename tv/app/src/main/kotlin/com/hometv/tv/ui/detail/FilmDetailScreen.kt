package com.hometv.tv.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.core.api.CastMember
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.Artwork
import com.hometv.tv.ui.components.ListDialog
import com.hometv.tv.ui.components.LoadingScreen
import com.hometv.tv.ui.components.RowSpacing
import com.hometv.tv.ui.components.ScreenErrorView
import com.hometv.tv.ui.components.SectionTitle
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.components.requestFocusWhenReady
import com.hometv.tv.ui.navigation.AppNavigator
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens

/** (7) Film detail (prd3 §6); Back pops to the screen that opened it (which restores its card focus). */
@Composable
fun FilmDetailScreen(vm: FilmDetailViewModel, navigator: AppNavigator) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.navigation.collect(navigator::handle) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onVisible() }
    Box(Modifier.fillMaxSize().background(HomeTvColors.Background)) {
        when (val s = state) {
            FilmDetailUiState.Loading -> LoadingScreen()
            is FilmDetailUiState.Error -> ScreenErrorView(s.error, vm::retry, onBack = navigator::back, onChangeServer = navigator::changeServer)
            is FilmDetailUiState.Content -> FilmContent(s, vm)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FilmContent(state: FilmDetailUiState.Content, vm: FilmDetailViewModel) {
    val film = state.film
    val buttons = rememberFocusRequesters<ButtonId>()
    LaunchedEffect(state.focus, state.focusVersion, state.dialog == null) {
        if (state.dialog == null) buttons[state.focus].requestFocusWhenReady()
    }
    Backdrop(film.backdrop?.large, BACKDROP_HEIGHT)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = HomeTvDimens.SafeVertical)) {
        Row(Modifier.padding(top = 140.dp)) {
            Artwork(film.poster?.large, film.title, Modifier.size(180.dp, 270.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)))
            Column(Modifier.padding(start = 32.dp).fillMaxWidth()) {
                Text(film.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(state.meta.resolve(), style = MaterialTheme.typography.titleMedium, color = HomeTvColors.Muted, modifier = Modifier.padding(top = 8.dp))
                if (state.matched) {
                    film.tagline?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                    film.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
                } else {
                    Text(stringResource(R.string.detail_metadata_unavailable), style = MaterialTheme.typography.bodyMedium, color = HomeTvColors.Muted, modifier = Modifier.padding(top = 8.dp))
                }
                FlowRow(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.buttons.forEach { button ->
                        ActionButton(
                            text = button.text.resolve(),
                            onClick = { vm.onButton(button.id) },
                            enabled = button.enabled,
                            icon = if (button.id in PLAY_BUTTONS) R.drawable.ic_play else null,
                            modifier = Modifier.focusRequester(buttons[button.id]).testTag("button_${button.id.name}"),
                        )
                    }
                }
                state.unplayableMessage?.let { Text(it.resolve(), color = HomeTvColors.Error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp)) }
            }
        }
        if (film.cast.isNotEmpty()) {
            SectionTitle(stringResource(R.string.detail_cast), Modifier.padding(start = 0.dp))
            LazyRow(contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = RowSpacing) {
                items(film.cast, key = { it.person_id }) { CastCard(it) }
            }
        }
    }
    state.dialog?.let { dialog ->
        ListDialog(dialog.title.resolve(), dialog.items.map { it.resolve() }, dialog.selected, vm::onDialogSelect, vm::onDialogDismiss)
    }
}

@Composable
fun CastCard(member: CastMember) {
    Column(Modifier.width(96.dp)) {
        Artwork(member.profile_image?.small, null, Modifier.size(96.dp).clip(CircleShape))
        Text(member.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        member.character?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HomeTvColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

private val PLAY_BUTTONS = setOf(ButtonId.RESUME, ButtonId.PLAY, ButtonId.PLAY_PART)
private val BACKDROP_HEIGHT = 420.dp
