package com.hometv.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.hometv.tv.ui.components.Artwork
import com.hometv.tv.ui.components.AvatarUi
import com.hometv.tv.ui.components.FocusRequesters
import com.hometv.tv.ui.components.LandscapeCard
import com.hometv.tv.ui.components.LoadingScreen
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
import com.hometv.tv.ui.components.SeeAllCard as SeeAllCardView

/** (4) Home: top nav, hero, rows (prd3 §3); Back returns to the first card before exiting. */
@Composable
fun HomeScreen(vm: HomeViewModel, navigator: AppNavigator, avatar: AvatarUi?, focusTab: NavTab?, onFocusTabConsumed: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val hero by vm.hero.collectAsStateWithLifecycle()
    val tabs = rememberFocusRequesters<NavTab>()
    val cards = rememberFocusRequesters<String>()
    val listStates = HomeRowId.entries.associateWith { rememberLazyListState() }
    val scroll = rememberScrollState()

    LaunchedEffect(vm) { vm.navigation.collect(navigator::handle) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onVisible() }
    BackHandler { if (!vm.onBack()) navigator.exit() }

    // Back from Browse / Search / Settings: focus the tab of the screen we came from.
    val pendingTab by rememberUpdatedState(focusTab)
    LaunchedEffect(focusTab) {
        val tab = focusTab ?: return@LaunchedEffect
        tabs[tab].requestFocusWhenReady()
        onFocusTabConsumed()
    }
    // Entry (Back from a detail: the card that opened it), fresh data (same key), Back to the first card.
    LaunchedEffect(state.focus?.version, state.rows.isEmpty()) {
        if (pendingTab != null) return@LaunchedEffect
        val target = vm.restoreTarget() ?: state.focus ?: return@LaunchedEffect
        val row = state.rows.getOrNull(target.rowIndex) ?: return@LaunchedEffect
        if (target.rowIndex == 0) scroll.scrollTo(0)
        listStates.getValue(row.id).scrollToItem(target.cardIndex)
        cards[target.key].requestFocusWhenReady()
    }

    Column(Modifier.fillMaxSize().background(HomeTvColors.Background)) {
        TopNav(NavTab.HOME, navigator::openTab, avatar, navigator::switchProfile, tabs, onFocused = vm::onNavFocused)
        val error = state.error
        when {
            error != null -> ScreenErrorView(error, onRetry = vm::retry, onBack = navigator::exit, onChangeServer = navigator::changeServer)
            state.loading -> LoadingScreen()
            state.libraryEmpty && state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.home_library_empty), style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(max = 640.dp))
            }
            else -> {
                Hero(hero)
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(bottom = HomeTvDimens.SafeVertical)) {
                    state.rows.forEach { row -> HomeRowView(row, vm, cards, listStates.getValue(row.id)) }
                }
            }
        }
    }
}

@Composable
private fun Hero(hero: HeroUi?) {
    Box(Modifier.fillMaxWidth().height(HERO_HEIGHT)) {
        if (hero?.backdrop != null) {
            Artwork(hero.backdrop, null, Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.62f).fillMaxHeight())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(HomeTvColors.Background, HomeTvColors.Background.copy(alpha = 0.85f), Color.Transparent))))
        if (hero != null) {
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = HomeTvDimens.SafeHorizontal).fillMaxWidth(0.55f).testTag("hero")) {
                val title = if (hero.year != null) stringResource(R.string.pair_dot, hero.title, hero.year) else hero.title
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                hero.episodeLine?.let { Text(it.resolve(), style = MaterialTheme.typography.titleMedium, maxLines = 1) }
                hero.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = HomeTvColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun HomeRowView(row: HomeRow, vm: HomeViewModel, requesters: FocusRequesters<String>, listState: LazyListState) {
    SectionTitle(stringResource(row.id.title))
    val longPress = row.id == HomeRowId.CONTINUE || row.id == HomeRowId.NEXT_UP
    LazyRow(state = listState, contentPadding = PaddingValues(horizontal = HomeTvDimens.SafeHorizontal, vertical = 8.dp), horizontalArrangement = RowSpacing) {
        items(row.cards, key = { it.key }) { card ->
            val modifier = Modifier.focusRequester(requesters[card.key]).testTag("card_${card.key}")
            when (card) {
                is SeeAllCard -> SeeAllCardView(onClick = { vm.onCardClick(card) }, modifier = modifier, onFocused = { vm.onSeeAllFocused(card) })
                is ItemCard -> if (card.style == CardStyle.LANDSCAPE) {
                    LandscapeCard(
                        title = card.title,
                        subtitle = card.subtitle?.resolve(),
                        image = card.image,
                        progress = card.progress,
                        onClick = { vm.onCardClick(card) },
                        onLongClick = if (longPress) ({ vm.onCardLongClick(card) }) else null,
                        modifier = modifier,
                        onFocused = { vm.onCardFocused(card) },
                    )
                } else {
                    PosterCard(
                        title = card.title,
                        year = card.year,
                        image = card.image,
                        progress = card.progress,
                        watched = card.watched,
                        badge = card.badge,
                        onClick = { vm.onCardClick(card) },
                        modifier = modifier,
                        onFocused = { vm.onCardFocused(card) },
                    )
                }
            }
        }
    }
}

private val HERO_HEIGHT = 200.dp
