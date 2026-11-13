package com.hometv.tv.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.Artwork
import com.hometv.tv.ui.components.ErrorAction
import com.hometv.tv.ui.components.ErrorScreen
import com.hometv.tv.ui.components.FocusRequesters
import com.hometv.tv.ui.components.ListDialog
import com.hometv.tv.ui.components.LoadingScreen
import com.hometv.tv.ui.components.ProgressStrip
import com.hometv.tv.ui.components.SafeAreaBringIntoViewSpec
import com.hometv.tv.ui.components.ScreenErrorView
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.components.rememberOkLongPress
import com.hometv.tv.ui.components.requestFocusWhenReady
import com.hometv.tv.ui.components.tryRequestFocus
import com.hometv.tv.ui.navigation.AppNavigator
import com.hometv.tv.ui.ratings.RatingDialog
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens
import com.hometv.tv.ui.theme.HomeTvFocus
import kotlinx.coroutines.launch

/** (8) Show detail: header, buttons, seasons (left) and episodes (right); Right/Left move between panes. */
@Composable
fun ShowDetailScreen(vm: ShowDetailViewModel, navigator: AppNavigator) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.navigation.collect(navigator::handle) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onVisible() }
    Box(Modifier.fillMaxSize().background(HomeTvColors.Background)) {
        when (val s = state) {
            ShowDetailUiState.Loading -> LoadingScreen()
            is ShowDetailUiState.Error -> ScreenErrorView(s.error, vm::retry, onBack = navigator::back)
            is ShowDetailUiState.Content -> if (s.seasonGone) {
                BackHandler { vm.onSeasonGoneBack() }
                ErrorScreen(stringResource(R.string.item_gone), listOf(ErrorAction(stringResource(R.string.action_back), vm::onSeasonGoneBack)))
            } else {
                ShowContent(s, vm)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShowContent(state: ShowDetailUiState.Content, vm: ShowDetailViewModel) {
    val show = state.show
    val buttons = rememberFocusRequesters<ButtonId>()
    val seasons = rememberFocusRequesters<Int>()
    val episodes = rememberFocusRequesters<Long>()
    val episodeList = rememberLazyListState()
    val seasonList = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var opener by remember { mutableStateOf<Long?>(null) }

    // Initial focus on the first button, again when a refetch replaced it (e.g. Resume → Play after
    // the Player) and nothing else on the screen holds focus.
    var contentHasFocus by remember { mutableStateOf(false) }
    val firstButton = state.buttons.first().id
    LaunchedEffect(firstButton) { if (!contentHasFocus) buttons[firstButton].requestFocusWhenReady() }
    val ratingMine = remember { FocusRequester() }
    val ratingMore = remember { FocusRequester() }
    LaunchedEffect(state.focusVersion) {
        // A rating dialog or the "Puntuaciones" list just closed: its opener chip.
        when (state.focusButton) {
            ButtonId.RATING_MINE -> return@LaunchedEffect run { ratingMine.requestFocusWhenReady() }
            ButtonId.RATING_MORE -> return@LaunchedEffect run { ratingMore.requestFocusWhenReady() }
            else -> Unit
        }
        val index = state.focusEpisodeIndex ?: return@LaunchedEffect
        val row = state.season?.episodes?.getOrNull(index) ?: return@LaunchedEffect
        episodeList.scrollToItem(index)
        episodes[row.id].requestFocusWhenReady()
    }
    // Another season in the pane: its list starts at the episode Right lands on (the next episode to
    // watch when it is in that season, else E1). The same season (focus back from one of its episodes,
    // a refetch) keeps the scroll; saved, so coming back from the Player does not scroll either.
    var shownSeason by rememberSaveable { mutableStateOf<Int?>(null) }
    // Until focus enters the list, a resize (the Mi lista error line) scrolls it to that episode again: a
    // last episode pinned above the bottom safe area would otherwise slide into it.
    var atEntry by remember { mutableStateOf(false) }
    var listHeight by remember { mutableIntStateOf(0) }
    val paneSeason = state.season
    suspend fun scrollToEntry(pane: SeasonPaneUi) = episodeList.scrollToItem(pane.entryIndex.coerceIn(0, (pane.episodes.size - 1).coerceAtLeast(0)))
    LaunchedEffect(paneSeason?.number) {
        if (paneSeason == null || paneSeason.number == shownSeason) return@LaunchedEffect
        shownSeason = paneSeason.number
        scrollToEntry(paneSeason)
        atEntry = true
    }
    LaunchedEffect(listHeight) { if (atEntry) paneSeason?.let { scrollToEntry(it) } }
    LaunchedEffect(state.episodeActions == null) {
        if (state.episodeActions == null) opener?.let { episodes[it].requestFocusWhenReady() }
    }

    Backdrop(show.backdrop?.large, 360.dp)
    Column(
        Modifier.fillMaxSize().onFocusChanged { contentHasFocus = it.hasFocus }
            // No bottom padding: the panes run to the screen's bottom edge (no empty strip under the lists).
            .padding(start = HomeTvDimens.SafeHorizontal, end = HomeTvDimens.SafeHorizontal, top = HomeTvDimens.SafeVertical),
    ) {
        // 24 dp (was 96): room for the ratings block, the panes keep one full episode row.
        Spacer(Modifier.height(TOP_SPACER))
        Text(show.title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(state.meta.resolve(), style = MaterialTheme.typography.titleMedium, color = DetailText.secondary, modifier = Modifier.padding(top = 6.dp))
        show.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = OVERVIEW_LINES, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.7f)) }
        val error = state.watchlistError
        // With the failed-write line, it takes the buttons' bottom padding.
        Row(Modifier.padding(top = 16.dp, bottom = if (error == null) 16.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            state.buttons.forEach { button ->
                ActionButton(
                    button.text.resolve(),
                    { vm.onButton(button.id) },
                    Modifier.focusRequester(buttons[button.id]).focusProperties { down = ratingMine }.testTag("button_${button.id.name}"),
                    icon = when (button.id) {
                        ButtonId.PLAY, ButtonId.RESUME -> R.drawable.ic_play
                        ButtonId.WATCHLIST -> watchlistIcon(show.in_watchlist)
                        else -> null
                    },
                    contentDescription = button.description?.resolve(),
                )
            }
        }
        if (error != null) {
            WatchlistErrorLine(error.resolve())
            Spacer(Modifier.height(8.dp))
        }
        val hasMore = state.ratingsRow.moreCount > 0
        // Down from the block: the selected season (else the first one), scrolled in first. The list
        // composes only its first rows, so a plain focus target may not be attached.
        val seasonIndex = state.seasons.indexOfFirst { it.number == state.selectedSeason }.coerceAtLeast(0)
        val downToSeason: () -> Boolean = {
            state.seasons.getOrNull(seasonIndex)?.let { target ->
                scope.launch {
                    seasonList.scrollToItem(seasonIndex)
                    seasons[target.number].requestFocusWhenReady()
                }
            }
            true
        }
        RatingsBlock(
            state.ratingsRow,
            ratingMine,
            ratingMore,
            onMine = vm::onRatingMine,
            onMore = vm::onRatingMore,
            modifier = Modifier.padding(bottom = RATINGS_BOTTOM_GAP),
            mineFocus = {
                up = buttons[firstButton]
                if (hasMore) down = ratingMore
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
            moreFocus = {
                up = ratingMine
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
            onMineDown = if (hasMore) null else downToSeason,
            onMoreDown = downToSeason,
        )
        // The lists end at the screen edge; their last item and focus scrolling stop 27 dp above it (the safe area).
        val density = LocalDensity.current
        val platformSpec = LocalBringIntoViewSpec.current
        val listSpec = remember(density, platformSpec) { SafeAreaBringIntoViewSpec(0f, with(density) { HomeTvDimens.SafeVertical.toPx() }, base = platformSpec) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides listSpec) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    Modifier.width(260.dp).fillMaxSize().testTag("season_list"),
                    state = seasonList,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp + HomeTvDimens.SafeVertical),
                ) {
                    itemsIndexed(state.seasons, key = { _, s -> s.number }) { index, season ->
                        ListItem(
                            selected = season.number == state.selectedSeason,
                            onClick = { vm.onSeasonFocused(season.number) },
                            headlineContent = { Text(season.title.resolve(), maxLines = 1) },
                            modifier = Modifier
                                .focusRequester(seasons[season.number])
                                .focusProperties { if (index == 0) up = ratingMine }
                                .testTag("season_${season.number}")
                                .onFocusChanged { if (it.isFocused) vm.onSeasonFocused(season.number) }
                                .onPreviewKeyEvent { event ->
                                    val pane = state.season
                                    if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionRight || pane == null || pane.number != season.number || pane.episodes.isEmpty()) {
                                        return@onPreviewKeyEvent false
                                    }
                                    val target = pane.episodes[pane.entryIndex.coerceIn(0, pane.episodes.lastIndex)]
                                    scope.launch {
                                        episodeList.scrollToItem(pane.entryIndex)
                                        episodes[target.id].requestFocusWhenReady()
                                    }
                                    true
                                },
                        )
                    }
                }
                Column(Modifier.padding(start = 24.dp).fillMaxSize()) {
                    val pane = state.season
                    if (pane == null) {
                        LoadingScreen(Modifier.height(120.dp))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                            Text(stringResource(R.string.pair_dot, pane.title.resolve(), pane.count.resolve()), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            ActionButton(pane.watchedButton.resolve(), vm::onSeasonWatched, Modifier.testTag("season_watched"))
                        }
                        LazyColumn(
                            Modifier.fillMaxSize().testTag("episode_list")
                                .onSizeChanged { listHeight = it.height }
                                .onFocusChanged { if (it.hasFocus) atEntry = false },
                            state = episodeList,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 4.dp + HomeTvDimens.SafeVertical),
                        ) {
                            itemsIndexed(pane.episodes, key = { _, e -> e.id }) { _, row ->
                                EpisodeRow(row, episodes, onClick = { vm.onEpisodeClick(row) }, onLongClick = { opener = row.id; vm.onEpisodeLongClick(row) }) {
                                    seasons[state.selectedSeason].tryRequestFocus()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    state.episodeActions?.let { actions ->
        EpisodeActionsDialog(actions, vm::onEpisodePlayFromStart, vm::onEpisodeToggleWatched, vm::onEpisodeActionsDismiss)
    }
    state.othersDialog?.let { dialog ->
        ListDialog(dialog.title.resolve(), dialog.items.map { it.resolve() }, dialog.selected, { vm.onOthersDialogClose() }, vm::onOthersDialogClose)
    }
    state.ratingDialog?.let { ui ->
        RatingDialog(ui, vm::onRatingStep, vm::onRatingSave, vm::onRatingSecondary, vm::onRatingRemove, vm::onRatingDismiss)
    }
}

private val TOP_SPACER = 24.dp
private const val OVERVIEW_LINES = 2

/** Between the ratings block and the panes. */
private val RATINGS_BOTTOM_GAP = 12.dp

@Composable
private fun EpisodeRow(row: EpisodeRowUi, requesters: FocusRequesters<Long>, onClick: () -> Unit, onLongClick: () -> Unit, onLeft: () -> Boolean) {
    // The long press works with every remote, including a TV remote over HDMI-CEC (no auto-repeat).
    val press = rememberOkLongPress(onClick, onLongClick)
    Surface(
        onClick = press.onClick,
        interactionSource = press.interactionSource,
        modifier = Modifier
            .then(press.modifier)
            .fillMaxWidth()
            .focusRequester(requesters[row.id])
            .testTag("episode_${row.id}")
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && onLeft() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(focusedBorder = HomeTvFocus.ring),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(containerColor = HomeTvColors.Surface),
    ) {
        Row(Modifier.padding(8.dp)) {
            Box(Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(6.dp))) {
                Artwork(row.still, null, Modifier.fillMaxSize())
                row.progress?.let { ProgressStrip(it, Modifier.align(Alignment.BottomStart)) }
            }
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.title.resolve(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (row.watched) Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.watched), modifier = Modifier.padding(start = 8.dp).size(20.dp))
                }
                Text(row.meta.resolve(), style = MaterialTheme.typography.bodySmall, color = HomeTvColors.Muted)
                row.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
