package com.hometv.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.components.Artwork
import com.hometv.tv.ui.components.AvatarUi
import com.hometv.tv.ui.components.ConfirmDialog
import com.hometv.tv.ui.components.FocusRequesters
import com.hometv.tv.ui.components.LandscapeCard
import com.hometv.tv.ui.components.LoadingScreen
import com.hometv.tv.ui.components.NavTab
import com.hometv.tv.ui.components.PosterCard
import com.hometv.tv.ui.components.RowSpacing
import com.hometv.tv.ui.components.ScreenErrorView
import com.hometv.tv.ui.components.SectionTitle
import com.hometv.tv.ui.components.SeeAllCard as SeeAllCardView
import com.hometv.tv.ui.components.TopNav
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.components.requestFocusWhenReady
import com.hometv.tv.ui.navigation.AppNavigator
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens

/** (4) Home: top nav, hero, rows; Back returns to the first card, then asks "¿Salir de la aplicación?". */
@Composable
fun HomeScreen(vm: HomeViewModel, navigator: AppNavigator, avatar: AvatarUi?, showSettings: Boolean, focusTab: NavTab?, onFocusTabConsumed: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val hero by vm.hero.collectAsStateWithLifecycle()
    val tabs = rememberFocusRequesters<NavTab>()
    val cards = rememberFocusRequesters<String>()
    val listStates = HomeRowId.entries.associateWith { rememberLazyListState() }
    val scroll = rememberScrollState()

    LaunchedEffect(vm) { vm.navigation.collect(navigator::handle) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onVisible() }
    // Home stops with the activity (the user left the app), not because another screen covered it.
    val activity = LocalActivity.current as? LifecycleOwner
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity != null && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.onAppLeft()
    }
    BackHandler { vm.onBack() }

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

    val error = state.error
    val showsRows = error == null && !state.loading && !(state.libraryEmpty && state.rows.isEmpty())
    Box(Modifier.fillMaxSize().background(HomeTvColors.Background)) {
        // The hero art is a screen-level layer behind the top nav, the hero text and the first rows.
        if (showsRows) HeroBackdrop(hero?.backdrop)
        Column(Modifier.fillMaxSize()) {
            TopNav(NavTab.HOME, navigator::openTab, avatar, navigator::switchProfile, tabs, showSettings, onFocused = vm::onNavFocused)
            when {
                error != null -> ScreenErrorView(error, onRetry = vm::retry, onBack = vm::onBack)
                state.loading -> LoadingScreen()
                state.libraryEmpty && state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.home_library_empty), style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(max = 640.dp))
                }
                else -> {
                    Hero(hero)
                    HomeRows(state.rows, vm, cards, listStates, scroll)
                }
            }
        }
    }
    state.exitDialog?.let {
        ConfirmDialog(
            stringResource(R.string.exit_dialog_title), stringResource(R.string.action_exit), stringResource(R.string.action_cancel),
            onConfirm = vm::onExitConfirmed, onDismiss = vm::onExitDismissed,
        )
    }
}

/**
 * The focused item's backdrop, full-bleed in the top-right of the screen: undimmed on the right,
 * fading into the background to the left (at least [HERO_TEXT_SCRIM_ALPHA] where the hero text
 * can be) and at the bottom, so no image edge shows.
 */
@Composable
private fun HeroBackdrop(path: String?) {
    if (path == null) return
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(HERO_BACKDROP_WIDTH).height(HERO_BACKDROP_HEIGHT).testTag("home_backdrop")) {
            Artwork(path, null, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to HomeTvColors.Background,
                        HERO_TEXT_EDGE to HomeTvColors.Background.copy(alpha = HERO_TEXT_SCRIM_ALPHA),
                        0.7f to Color.Transparent,
                    ),
                ),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to HomeTvColors.Background)))
        }
    }
}

@Composable
private fun Hero(hero: HeroUi?) {
    // Text only: the art is the screen-level [HeroBackdrop] behind it.
    Box(Modifier.fillMaxWidth().height(HERO_HEIGHT)) {
        if (hero != null) {
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = HomeTvDimens.SafeHorizontal).fillMaxWidth(0.55f).testTag("hero")) {
                // Title alone; the year is a smaller, light-grey sublabel below it.
                Text(hero.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Muted is fine here: a 4-digit year at the left margin never reaches the backdrop (it starts at 32 %).
                hero.year?.let { Text(it, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal), color = HomeTvColors.Muted, maxLines = 1) }
                hero.episodeLine?.let { Text(it.resolve(), style = MaterialTheme.typography.titleMedium, maxLines = 1) }
                hero.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = HomeTvColors.SecondaryOnArt, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * The rows under the hero: the focused row is pinned to the top of this area, so no
 * part of an earlier row stays visible, the space after the last row lets it be pinned too, and the
 * area runs to the screen's bottom edge.
 * Only the vertical container pins; the rows' own horizontal scrolling keeps the inherited behavior.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeRows(rows: List<HomeRow>, vm: HomeViewModel, cards: FocusRequesters<String>, listStates: Map<HomeRowId, LazyListState>, scroll: ScrollState) {
    val density = LocalDensity.current
    val inherited = LocalBringIntoViewSpec.current
    var headerPx by remember { mutableFloatStateOf(0f) }
    var viewportPx by remember { mutableIntStateOf(0) }
    var lastRowPx by remember { mutableIntStateOf(0) }
    val pinRows = remember { PinRowToTop { headerPx } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides pinRows) {
        // The scroll viewport runs to the screen's bottom edge: the next row peeks down to it (no empty strip). The
        // focused row is pinned under the hero and fits above the bottom safe area (HERO_HEIGHT).
        Column(Modifier.fillMaxWidth().onSizeChanged { viewportPx = it.height }.verticalScroll(scroll).testTag("home_rows")) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides inherited) {
                rows.forEachIndexed { index, row ->
                    HomeRowView(
                        row, vm, cards, listStates.getValue(row.id),
                        onHeaderMeasured = { headerPx = it },
                        modifier = if (index == rows.lastIndex) Modifier.onSizeChanged { lastRowPx = it.height } else Modifier,
                    )
                }
            }
            Spacer(Modifier.height(with(density) { (viewportPx - lastRowPx).coerceAtLeast(0).toDp() }))
        }
    }
}

/** Scrolls so the focused card sits right under its row's header: the row's title at the top. */
@OptIn(ExperimentalFoundationApi::class)
private class PinRowToTop(private val headerPx: () -> Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - headerPx()
}

@Composable
private fun HomeRowView(
    row: HomeRow,
    vm: HomeViewModel,
    requesters: FocusRequesters<String>,
    listState: LazyListState,
    onHeaderMeasured: (Float) -> Unit,
    modifier: Modifier = Modifier,
) = Column(modifier.testTag("home_row_${row.id.prefix}")) {
    val rowPaddingPx = with(LocalDensity.current) { ROW_VERTICAL_PADDING.toPx() }
    SectionTitle(stringResource(row.id.title), Modifier.onSizeChanged { onHeaderMeasured(it.height + rowPaddingPx) })
    val longPress = row.id == HomeRowId.CONTINUE
    LazyRow(state = listState, contentPadding = PaddingValues(horizontal = HomeTvDimens.SafeHorizontal, vertical = ROW_VERTICAL_PADDING), horizontalArrangement = RowSpacing) {
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
                        myRating = card.myRating,
                    )
                }
            }
        }
    }
}

/** Minimum opacity of the left fade wherever the hero text can be: ≥ 4.5:1 over any art. */
const val HERO_TEXT_SCRIM_ALPHA = 0.8f

/**
 * Where the hero text ends, as a fraction of the backdrop's width: the text column is 55 % of the
 * padded width (≈ 0.55 of the screen) and the backdrop starts at 1 − [HERO_BACKDROP_WIDTH]; 0.4 has margin.
 */
private const val HERO_TEXT_EDGE = 0.4f

/** The hero art: 68 % of the screen width from the right edge, from the screen top down behind the first rows. */
private const val HERO_BACKDROP_WIDTH = 0.68f
private val HERO_BACKDROP_HEIGHT = 340.dp

/** Leaves room under the top nav for the tallest row (posters) above the bottom safe area. */
private val HERO_HEIGHT = 144.dp

/** Room above and below the cards of a row, for the focus scale. */
private val ROW_VERTICAL_PADDING = 8.dp
