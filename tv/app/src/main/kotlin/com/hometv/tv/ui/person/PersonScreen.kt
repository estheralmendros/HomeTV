package com.hometv.tv.ui.person

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.components.Artwork
import com.hometv.tv.ui.components.LoadingScreen
import com.hometv.tv.ui.components.PosterCard
import com.hometv.tv.ui.components.RowSpacing
import com.hometv.tv.ui.components.SafeAreaBringIntoViewSpec
import com.hometv.tv.ui.components.ScreenErrorView
import com.hometv.tv.ui.components.TextDialog
import com.hometv.tv.ui.components.focusItem
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.components.rememberFocusedItemKey
import com.hometv.tv.ui.components.requestFocusWhenReady
import com.hometv.tv.ui.detail.DetailText
import com.hometv.tv.ui.navigation.AppNavigator
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens
import com.hometv.tv.ui.theme.HomeTvFocus

/** (10) Person; Back pops to the screen that opened it (Film detail restores its cast card). */
@Composable
fun PersonScreen(vm: PersonViewModel, navigator: AppNavigator) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.navigation.collect(navigator::handle) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onVisible() }
    Box(Modifier.fillMaxSize().background(HomeTvColors.Background)) {
        when (val s = state) {
            PersonUiState.Loading -> LoadingScreen()
            is PersonUiState.Error -> ScreenErrorView(s.error, vm::retry, onBack = navigator::back)
            is PersonUiState.Content -> PersonContent(s, vm)
        }
    }
}

/**
 * Header (photo, 1-line name, life span, place, incomplete note, 3-line biography block), "En tu
 * catálogo" and the poster row: sized so the name and the first card's subtitle fit 960×540 dp with
 * focus on the first card. D-pad moves are never reported to the VM: `state.focus` only
 * changes on OK, on closing the biography and on a refetch whose target is gone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonContent(state: PersonUiState.Content, vm: PersonViewModel) {
    val bio = remember { FocusRequester() }
    val cardRequesters = rememberFocusRequesters<String>()
    val row = rememberLazyListState()
    // OK publishes `focus = Card(key)` (an effect key) while the screen is leaving: the focused card is skipped.
    val focusedCard = rememberFocusedItemKey<String>()
    LaunchedEffect(state.focus, state.focusVersion, state.bioDialogOpen) {
        if (state.bioDialogOpen) return@LaunchedEffect
        when (val f = state.focus) {
            PersonFocus.Bio -> bio.requestFocusWhenReady()
            is PersonFocus.Card -> {
                val index = state.cards.indexOfFirst { it.item.key == f.key }
                if (index >= 0) row.focusItem(f.key, index, focusedCard, cardRequesters[f.key])
            }
            PersonFocus.None -> Unit
        }
    }
    val hasBio = state.biography != null
    // The page runs to the screen edges (no empty strip at the bottom); the vertical safe area is padding inside
    // the scroll, and focus scrolling stops 27 dp from the edges (as Film detail). The rows keep the platform spec.
    val platformSpec = LocalBringIntoViewSpec.current
    val density = LocalDensity.current
    val pageSpec = remember(density, platformSpec) {
        with(density) { SafeAreaBringIntoViewSpec(HomeTvDimens.SafeVertical.toPx(), HomeTvDimens.SafeVertical.toPx(), base = platformSpec) }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .testTag("person_page").padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = HomeTvDimens.SafeVertical),
        ) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides platformSpec) {
                Row {
                    Artwork(
                        state.photo,
                        null,
                        Modifier.size(PHOTO_WIDTH, PHOTO_HEIGHT).clip(RoundedCornerShape(8.dp)),
                        placeholder = {
                            // Decorative (as the photo): not read by TalkBack.
                            Text(
                                state.initial,
                                style = MaterialTheme.typography.displaySmall.copy(fontSize = 56.sp),
                                color = HomeTvColors.Muted,
                                modifier = Modifier.testTag("person_initial").clearAndSetSemantics {},
                            )
                        },
                    )
                    Column(Modifier.padding(start = 32.dp).fillMaxWidth()) {
                        Text(
                            state.name,
                            style = MaterialTheme.typography.displaySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("person_name"),
                        )
                        state.lifeSpan?.let {
                            Text(it.resolve(), style = MaterialTheme.typography.titleMedium, color = DetailText.secondary, modifier = Modifier.padding(top = 8.dp))
                        }
                        state.placeOfBirth?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = HomeTvColors.Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        // Not focusable, so it sits above the row: focus scrolling only reveals the focused card.
                        if (state.incomplete) {
                            Text(
                                stringResource(R.string.person_incomplete),
                                style = MaterialTheme.typography.bodySmall,
                                color = HomeTvColors.Muted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp).testTag("person_incomplete"),
                            )
                        }
                        state.biography?.let { text ->
                            BiographyBlock(
                                text,
                                onClick = vm::openBio,
                                modifier = Modifier.padding(top = BIO_TOP_GAP).focusRequester(bio).focusProperties {
                                    up = FocusRequester.Cancel
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                },
                            )
                        }
                    }
                }
                // Not SectionTitle: its 16 dp top padding costs the 8 dp the vertical budget needs (same typography).
                Text(stringResource(R.string.person_in_catalog), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                if (state.cards.isEmpty()) {
                    Text(
                        stringResource(R.string.person_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HomeTvColors.Muted,
                        modifier = Modifier.testTag("person_empty"),
                    )
                } else {
                    // As Film detail's cast row: shifted left by its content padding, so the first card starts on the
                    // margin and the focus scale is not clipped.
                    // focusRestorer redirects any focus entering the row (a requestFocus included) to its last focused
                    // card, else to the fallback: after Back from a detail nothing is remembered, so the fallback is
                    // the card of `state.focus` itself.
                    val remembered = (state.focus as? PersonFocus.Card)?.key?.takeIf { key -> state.cards.any { it.item.key == key } }
                    LazyRow(
                        state = row,
                        modifier = Modifier.offset(x = -ROW_PADDING).focusRestorer(cardRequesters[remembered ?: state.cards.first().item.key]),
                        contentPadding = PaddingValues(horizontal = ROW_PADDING, vertical = 8.dp),
                        horizontalArrangement = RowSpacing,
                    ) {
                        items(state.cards, key = { it.item.key }) { card ->
                            val key = card.item.key
                            PosterCard(
                                title = card.item.title,
                                year = card.item.year,
                                image = card.item.image,
                                progress = card.item.progress,
                                watched = card.item.watched,
                                badge = card.item.badge,
                                onClick = { vm.onCardSelected(card) },
                                modifier = Modifier
                                    .testTag("appearance_$key")
                                    .focusRequester(cardRequesters[key])
                                    .then(focusedCard.track(key))
                                    .focusProperties { up = if (hasBio) bio else FocusRequester.Cancel },
                                subtitle = card.subtitle?.resolve(),
                                subtitleTestTag = "appearance_${key}_subtitle",
                                myRating = card.item.myRating,
                            )
                        }
                    }
                }
            }
        }
    }
    if (state.bioDialogOpen && state.biography != null) {
        TextDialog(stringResource(R.string.person_biography), state.biography, vm::closeBio, textTestTag = "person_bio_dialog")
    }
}

/**
 * The 3-line biography: focusable when present (OK opens the full text; no overflow measuring on
 * the TV). The text colour is explicit: tv-material's `Surface` would switch `LocalContentColor`
 * to its dark focused content colour on the focused `SurfaceVariant` fill.
 */
@Composable
private fun BiographyBlock(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("person_bio"),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = HomeTvColors.SurfaceVariant),
        border = ClickableSurfaceDefaults.border(focusedBorder = HomeTvFocus.ring),
        scale = ClickableSurfaceDefaults.scale(focusedScale = HomeTvDimens.WIDE_FOCUSED_SCALE),
    ) {
        Text(
            text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = HomeTvColors.OnBackground,
            modifier = Modifier.padding(BIO_PADDING),
        )
    }
}

private val PHOTO_WIDTH = 120.dp
private val PHOTO_HEIGHT = 180.dp
private val BIO_TOP_GAP = 12.dp
private val BIO_PADDING = 8.dp
private val ROW_PADDING = 8.dp
