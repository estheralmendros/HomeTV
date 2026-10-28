package com.hometv.tv.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
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
import com.hometv.tv.ui.components.focusItem
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.components.rememberFocusedItemKey
import com.hometv.tv.ui.components.requestFocusWhenReady
import com.hometv.tv.ui.navigation.AppNavigator
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens
import com.hometv.tv.ui.theme.HomeTvFocus

/** (7) Film detail (prd3 §6); Back pops to the screen that opened it (which restores its card focus). */
@Composable
fun FilmDetailScreen(vm: FilmDetailViewModel, navigator: AppNavigator) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.navigation.collect(navigator::handle) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onVisible() }
    Box(Modifier.fillMaxSize().background(HomeTvColors.Background)) {
        when (val s = state) {
            FilmDetailUiState.Loading -> LoadingScreen()
            is FilmDetailUiState.Error -> ScreenErrorView(s.error, vm::retry, onBack = navigator::back)
            is FilmDetailUiState.Content -> FilmContent(s, vm)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FilmContent(state: FilmDetailUiState.Content, vm: FilmDetailViewModel) {
    val film = state.film
    val buttons = rememberFocusRequesters<ButtonId>()
    val castRequesters = rememberFocusRequesters<String>()
    // Saveable: the row comes back scrolled where it was after the Person screen pops.
    val castState = rememberLazyListState()
    // Selecting a card publishes `castFocus` (an effect key) while the screen is leaving: the focused card is skipped.
    val focusedCast = rememberFocusedItemKey<String>()
    LaunchedEffect(state.focus, state.castFocus, state.focusVersion, state.dialog == null) {
        if (state.dialog != null) return@LaunchedEffect
        val cast = state.castFocus
        if (cast == null) {
            buttons[state.focus].requestFocusWhenReady()
        } else {
            // Back from Person (cast-appearances prd3 FR-T5): the card that opened it, scrolled into the row first.
            castState.focusItem(cast.key, cast.index, focusedCast, castRequesters[cast.key])
        }
    }
    Backdrop(film.backdrop?.large, BACKDROP_HEIGHT)
    val page = rememberScrollState()
    // The header fits at the top (HEADER_TOP_GAP), so the page opens unscrolled. Only the cast row scrolls it;
    // back Up on a header button the whole header returns (prd3 §6 step 1): bringing only the focused button
    // into view would leave the page where the cast row put it, with the title cut off.
    var headerFocused by remember { mutableStateOf(false) }
    LaunchedEffect(headerFocused) { if (headerFocused) page.animateScrollTo(0) }
    // On a TV (FEATURE_LEANBACK) Compose's default spec pivots the focused element towards 30 % of the
    // viewport, so entering the cast row scrolled the page as far as it could even when everything fit.
    // The page scrolls only as far as the focused element and its focus-scale room need (the viewport clips
    // the scaled card's ring); the cast row keeps the platform spec.
    val platformSpec = LocalBringIntoViewSpec.current
    val focusRoomPx = with(LocalDensity.current) { CAST_ROW_PADDING.toPx() }
    val pageSpec = remember(focusRoomPx) { FocusRoomBringIntoViewSpec(focusRoomPx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec) {
        // The vertical safe area is outside the scroll: focus scrolling (the cast row) stops 27 dp above the edge.
        Column(Modifier.fillMaxSize().padding(vertical = HomeTvDimens.SafeVertical).verticalScroll(page).testTag("film_detail_page").padding(horizontal = HomeTvDimens.SafeHorizontal)) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides platformSpec) {
                Row(Modifier.padding(top = HEADER_TOP_GAP).onFocusChanged { headerFocused = it.hasFocus }) {
                    Artwork(film.poster?.large, film.title, Modifier.size(180.dp, 270.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)))
                    Column(Modifier.padding(start = 32.dp).fillMaxWidth()) {
                        Text(film.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(state.meta.resolve(), style = MaterialTheme.typography.titleMedium, color = DetailText.secondary, modifier = Modifier.padding(top = 8.dp))
                        if (state.matched) {
                            film.tagline?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                            film.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
                        } else {
                            Text(stringResource(R.string.detail_metadata_unavailable), style = MaterialTheme.typography.bodyMedium, color = DetailText.secondary, modifier = Modifier.padding(top = 8.dp))
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
                    // The page already has the 48 dp horizontal safe margin: the title (and, below, the first card) start right on it.
                    SectionTitle(stringResource(R.string.detail_cast), startPadding = 0.dp)
                    // Focusable cards (prd3 §6 step 1): Down from the buttons enters the row at the first card
                    // (later: the last one focused), Left/Right scroll it, Up returns to the first button.
                    val firstButton = state.buttons.firstOrNull()?.id
                    // Shifted left by its content padding: the first card starts on the margin, and the row still
                    // has room for the focus scale (it is not clipped at the margin).
                    // focusRestorer redirects any focus entering the row (a requestFocus included) to its last focused
                    // card, else to the fallback; after Back from Person nothing is remembered, so the fallback is the
                    // remembered cast card itself.
                    val restoreTarget = castRequesters[state.castFocus?.key ?: castKey(film.cast.first(), 0)]
                    LazyRow(
                        state = castState,
                        modifier = Modifier.offset(x = -CAST_ROW_PADDING).focusRestorer(restoreTarget),
                        contentPadding = PaddingValues(horizontal = CAST_ROW_PADDING, vertical = 8.dp),
                        horizontalArrangement = RowSpacing,
                    ) {
                        // Keyed by position too: TMDB can credit one person twice, and the server keeps both credits.
                        itemsIndexed(film.cast, key = { index, member -> castKey(member, index) }) { index, member ->
                            val key = castKey(member, index)
                            CastCard(
                                member,
                                key,
                                onClick = { vm.onCastSelected(key, index, member.person_id) },
                                modifier = Modifier
                                    .focusRequester(castRequesters[key])
                                    .then(focusedCast.track(key))
                                    .focusProperties { if (firstButton != null) up = buttons[firstButton] },
                            )
                        }
                    }
                }
            }
        }
    }
    state.dialog?.let { dialog ->
        ListDialog(dialog.title.resolve(), dialog.items.map { it.resolve() }, dialog.selected, vm::onDialogSelect, vm::onDialogDismiss)
    }
}

/** A cast member: focusable for D-pad browsing (focus ring + scale); OK opens the Person screen. */
@Composable
fun CastCard(member: CastMember, key: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(CAST_CARD_WIDTH).testTag("cast_$key"),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = HomeTvColors.SurfaceVariant),
        border = ClickableSurfaceDefaults.border(focusedBorder = HomeTvFocus.ring),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HomeTvDimens.FOCUSED_SCALE),
    ) {
        Column(Modifier.padding(6.dp)) {
            Artwork(member.profile_image?.small, null, Modifier.size(CAST_CARD_WIDTH - 12.dp).clip(CircleShape))
            Text(member.name, style = MaterialTheme.typography.bodySmall, color = HomeTvColors.OnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            member.character?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HomeTvColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private val CAST_CARD_WIDTH = 108.dp
private val CAST_ROW_PADDING = 8.dp

private val PLAY_BUTTONS = setOf(ButtonId.RESUME, ButtonId.PLAY, ButtonId.PLAY_PART)
private val BACKDROP_HEIGHT = 420.dp

/**
 * Scrolls only by the distance that brings the element, plus [roomPx] above and below it, fully into view
 * (the non-TV default, widened by the room a focused card's scale draws outside its bounds).
 */
@OptIn(ExperimentalFoundationApi::class)
private class FocusRoomBringIntoViewSpec(private val roomPx: Float) : BringIntoViewSpec {
    private val minimal = object : BringIntoViewSpec {}

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        minimal.calculateScrollDistance(offset - roomPx, size + 2 * roomPx, containerSize)
}

/**
 * Space above the header: small enough that the header and the whole cast row, including the focused card's
 * scale room ([CAST_ROW_PADDING] below the row), fit on screen without scrolling.
 */
private val HEADER_TOP_GAP = 8.dp
