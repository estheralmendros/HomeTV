package com.hometv.tv.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.common.PosterItem
import com.hometv.tv.ui.common.TvTextField
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.AvatarUi
import com.hometv.tv.ui.components.FocusRequesters
import com.hometv.tv.ui.components.NavTab
import com.hometv.tv.ui.components.PosterCard
import com.hometv.tv.ui.components.RowSpacing
import com.hometv.tv.ui.components.ScreenErrorView
import com.hometv.tv.ui.components.SectionTitle
import com.hometv.tv.ui.components.TopNav
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.components.requestFocusWhenReady
import com.hometv.tv.ui.navigation.AppNavigator
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens

/** (6) Search: field (initial focus), mic (hidden without a recognizer; also KEYCODE_SEARCH), Películas / Series rows. */
@Composable
fun SearchScreen(vm: SearchViewModel, navigator: AppNavigator, avatar: AvatarUi?) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tabs = rememberFocusRequesters<NavTab>()
    val focus = rememberFocusRequesters<String>()
    val voice = rememberVoiceInput(vm::onVoiceResult)
    BackHandler { navigator.backToHome(NavTab.SEARCH) }
    LaunchedEffect(vm) { vm.navigation.collect(navigator::handle) }
    LaunchedEffect(Unit) { (focus[lastFocused(vm)]).requestFocusWhenReady() }
    Column(
        Modifier.fillMaxSize().background(HomeTvColors.Background).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Search && voice != null) {
                voice.start()
                true
            } else false
        },
    ) {
        TopNav(NavTab.SEARCH, navigator::openTab, avatar, navigator::switchProfile, tabs)
        // Down from the field enters the first non-empty row at its first card.
        val firstResult = (state.films.firstOrNull() ?: state.shows.firstOrNull())?.key
        Row(Modifier.padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TvTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                hint = stringResource(R.string.search_placeholder),
                imeAction = ImeAction.Search,
                onImeAction = {},
                modifier = Modifier
                    .width(640.dp)
                    .focusRequester(focus[FIELD])
                    .focusProperties { firstResult?.let { down = focus[it] } }
                    .testTag("search_field")
                    .onFocusChanged { if (it.isFocused) vm.lastFocusedKey = FIELD },
            )
            if (voice != null) {
                ActionButton(stringResource(R.string.search_voice), voice::start, Modifier.padding(start = 16.dp).testTag("search_mic"), icon = R.drawable.ic_mic)
            }
        }
        val error = state.error
        val noResults = state.noResults
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            when {
                error != null -> ScreenErrorView(error, vm::retry, onBack = { navigator.backToHome(NavTab.SEARCH) }, onChangeServer = navigator::changeServer)
                noResults != null -> Text(
                    noResults.resolve(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 24.dp).testTag("search_no_results"),
                )
                else -> {
                    ResultRow(stringResource(R.string.nav_films), state.films, vm, focus)
                    ResultRow(stringResource(R.string.nav_shows), state.shows, vm, focus)
                }
            }
        }
    }
}

@Composable
private fun ResultRow(title: String, items: List<PosterItem>, vm: SearchViewModel, focus: FocusRequesters<String>) {
    if (items.isEmpty()) return
    SectionTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = HomeTvDimens.SafeHorizontal, vertical = 8.dp), horizontalArrangement = RowSpacing) {
        items(items, key = { it.key }) { item ->
            PosterCard(
                title = item.title,
                year = item.year,
                image = item.image,
                progress = item.progress,
                watched = item.watched,
                badge = item.badge,
                onClick = { vm.onCardClick(item) },
                modifier = Modifier.focusRequester(focus[item.key]).testTag("result_${item.key}"),
                onFocused = { vm.lastFocusedKey = item.key },
            )
        }
    }
}

private const val FIELD = "field"

/** Back from a detail returns focus to the result card that opened it; the field on first entry. */
private fun lastFocused(vm: SearchViewModel): String = vm.lastFocusedKey ?: FIELD
