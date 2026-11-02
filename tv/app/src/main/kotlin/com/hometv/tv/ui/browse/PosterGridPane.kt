package com.hometv.tv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.common.PosterItem
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.PosterCard
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.components.requestFocusWhenReady
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens

/**
 * The poster grid of Browse and Mi lista: 6 fixed columns of
 * [PosterCard]s (progress, watched ✓, unwatched badge) tagged `grid_<index>`, the first row's Up on
 * [header] (the screen's first header button, not the top nav), and the failed-page row ("No se han
 * podido cargar más títulos." [Reintentar], tag [retryTag]). Focus: initially and on each
 * [focusVersion] change, the card at [restoreIndex], scrolled into view, unless [shouldRestore]
 * declines it (Mi lista keeps focus on its header when a resume fills an empty list). [onFocused]
 * drives paging.
 */
@Composable
fun PosterGridPane(
    items: List<PosterItem>,
    focusVersion: Int?,
    restoreIndex: () -> Int,
    pageFailed: Boolean,
    header: FocusRequester,
    onFocused: (Int) -> Unit,
    onClick: (PosterItem) -> Unit,
    onRetry: () -> Unit,
    retryTag: String,
    modifier: Modifier = Modifier,
    shouldRestore: (focusVersion: Int?) -> Boolean = { true },
) {
    val cards = rememberFocusRequesters<Int>()
    val grid = rememberLazyGridState()
    // Initial focus / Back from a detail: the last focused card; after a sort or filter change: the first card.
    LaunchedEffect(items.isNotEmpty(), focusVersion) {
        if (items.isEmpty() || !shouldRestore(focusVersion)) return@LaunchedEffect
        val index = restoreIndex()
        grid.scrollToItem(index)
        cards[index].requestFocusWhenReady()
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(BrowseViewModel.COLUMNS),
        state = grid,
        contentPadding = PaddingValues(horizontal = HomeTvDimens.SafeHorizontal, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            PosterCard(
                title = item.title,
                year = item.year,
                image = item.image,
                progress = item.progress,
                watched = item.watched,
                badge = item.badge,
                myRating = item.myRating,
                onClick = { onClick(item) },
                modifier = Modifier
                    .focusRequester(cards[index])
                    // The first row's Up goes to the header buttons, not to the top nav.
                    .focusProperties { if (index < BrowseViewModel.COLUMNS) up = header }
                    .testTag("grid_$index")
                    .onFocusChanged { if (it.isFocused) onFocused(index) },
            )
        }
        if (pageFailed) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.browse_load_more_failed), style = MaterialTheme.typography.bodyMedium, color = HomeTvColors.Error)
                    ActionButton(stringResource(R.string.action_retry), onRetry, Modifier.testTag(retryTag))
                }
            }
        }
    }
}
