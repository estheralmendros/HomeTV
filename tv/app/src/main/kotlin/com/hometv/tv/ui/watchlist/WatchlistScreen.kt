package com.hometv.tv.ui.watchlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.browse.PosterGridPane
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.AvatarUi
import com.hometv.tv.ui.components.ListDialog
import com.hometv.tv.ui.components.LoadingScreen
import com.hometv.tv.ui.components.NavTab
import com.hometv.tv.ui.components.ScreenErrorView
import com.hometv.tv.ui.components.TopNav
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.components.requestFocusWhenReady
import com.hometv.tv.ui.navigation.AppNavigator
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens

/**
 * (11) Mi lista (watchlist prd3 FR-T10): Browse's layout with "Ordenar" / "Tipo" list dialogs, the
 * shared [PosterGridPane], the empty and filtered-empty states, and a reload in place on every resume.
 */
@Composable
fun WatchlistScreen(vm: WatchlistViewModel, navigator: AppNavigator, avatar: AvatarUi?, showSettings: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tabs = rememberFocusRequesters<NavTab>()
    val sortButton = remember { FocusRequester() }
    val showAllButton = remember { FocusRequester() }
    BackHandler { navigator.backToHome(NavTab.WATCHLIST) }
    LaunchedEffect(vm) { vm.navigation.collect(navigator::handle) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onVisible() }
    // No card to focus (FR-T10.7): "Ver todo" with a type filter, else "Ordenar"; after the new body is composed.
    LaunchedEffect(state.headerFocusVersion) {
        if (state.headerFocusVersion == 0 || state.items.isNotEmpty()) return@LaunchedEffect
        (if (state.noMatches) showAllButton else sortButton).requestFocusWhenReady()
    }
    // The body (grid, or the empty / no-matches column) reports whether it holds focus (FR-T9.7).
    val bodyFocus = Modifier.onFocusChanged { vm.onBodyFocusChanged(it.hasFocus) }
    // The grid takes focus when it appears on a screen nothing holds focus on (opening it, Back from a detail),
    // or for a new GridFocus; a resume that fills an empty list while the header or the top nav has focus
    // leaves focus there (AC-T3d).
    var screenHasFocus by remember { mutableStateOf(false) }
    var seenFocusVersion by remember { mutableStateOf(state.focus?.version) }
    val shouldRestore = { version: Int? ->
        val fresh = version != seenFocusVersion
        seenFocusVersion = version
        fresh || !screenHasFocus
    }
    Column(Modifier.fillMaxSize().background(HomeTvColors.Background).onFocusChanged { screenHasFocus = it.hasFocus }) {
        TopNav(NavTab.WATCHLIST, navigator::openTab, avatar, navigator::switchProfile, tabs, showSettings)
        val error = state.error
        Text(
            state.header.resolve(),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 4.dp).testTag("watchlist_header"),
        )
        // Browse's button row: it scrolls sideways (margin inside, so the focus ring is never clipped) if ever too wide.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ActionButton(state.sortLabel.resolve(), vm::openSortDialog, Modifier.focusRequester(sortButton).testTag("watchlist_sort"))
            ActionButton(state.typeLabel.resolve(), vm::openTypeDialog, Modifier.testTag("watchlist_type"))
        }
        when {
            error != null && state.items.isEmpty() -> ScreenErrorView(error, vm::retry, onBack = { navigator.backToHome(NavTab.WATCHLIST) })
            state.loading && state.items.isEmpty() -> LoadingScreen()
            state.empty -> Text(
                stringResource(R.string.watchlist_empty),
                style = MaterialTheme.typography.titleMedium,
                color = HomeTvColors.Muted,
                modifier = bodyFocus.padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 24.dp).testTag("watchlist_empty"),
            )
            state.noMatches -> Column(bodyFocus.padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 24.dp)) {
                Text(
                    state.noMatchesText.resolve(),
                    style = MaterialTheme.typography.titleMedium,
                    color = HomeTvColors.Muted,
                    modifier = Modifier.testTag("watchlist_no_matches"),
                )
                ActionButton(
                    stringResource(R.string.watchlist_show_all),
                    vm::showAll,
                    Modifier.padding(top = 16.dp).focusRequester(showAllButton).testTag("watchlist_show_all"),
                )
            }
            else -> PosterGridPane(
                items = state.items,
                focusVersion = state.focus?.version,
                restoreIndex = vm::restoreIndex,
                pageFailed = state.pageFailed,
                header = sortButton,
                onFocused = vm::onFocused,
                onClick = vm::onCardClick,
                onRetry = vm::retry,
                retryTag = "watchlist_retry",
                modifier = bodyFocus,
                shouldRestore = shouldRestore,
            )
        }
    }
    state.dialog?.let { dialog ->
        ListDialog(dialog.title.resolve(), dialog.items.map { it.resolve() }, dialog.selected, vm::onDialogSelect, vm::onDialogDismiss)
    }
}
