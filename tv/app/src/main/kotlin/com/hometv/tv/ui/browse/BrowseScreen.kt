package com.hometv.tv.ui.browse

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.AvatarUi
import com.hometv.tv.ui.components.ListDialog
import com.hometv.tv.ui.components.LoadingScreen
import com.hometv.tv.ui.components.NavTab
import com.hometv.tv.ui.components.ScreenErrorView
import com.hometv.tv.ui.components.TopNav
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.navigation.AppNavigator
import com.hometv.tv.ui.navigation.Library
import com.hometv.tv.ui.text.resolve
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens

/** (5) Browse grid (films | shows): header with total and the sort / genre / decade buttons (list dialogs), 6 poster columns, paging. */
@Composable
fun BrowseScreen(vm: BrowseViewModel, library: Library, navigator: AppNavigator, avatar: AvatarUi?, showSettings: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tab = if (library == Library.FILMS) NavTab.FILMS else NavTab.SHOWS
    val tabs = rememberFocusRequesters<NavTab>()
    val sortButton = remember { FocusRequester() }
    BackHandler { navigator.backToHome(tab) }
    LaunchedEffect(vm) { vm.navigation.collect(navigator::handle) }
    Column(Modifier.fillMaxSize().background(HomeTvColors.Background)) {
        TopNav(tab, navigator::openTab, avatar, navigator::switchProfile, tabs, showSettings)
        val error = state.error
        Text(state.header.resolve(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 4.dp))
        // The four buttons get their own row (prd3 §4 step 2): beside the title, the longest labels
        // wrapped. It scrolls sideways (margin inside, so the focus ring is never clipped) if ever too wide.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ActionButton(state.sortLabel.resolve(), vm::openSortDialog, Modifier.focusRequester(sortButton).testTag("browse_sort"))
            ActionButton(state.genreLabel.resolve(), vm::openGenreDialog, Modifier.testTag("browse_genre"))
            ActionButton(state.decadeLabel.resolve(), vm::openDecadeDialog, Modifier.testTag("browse_decade"))
            ActionButton(state.watchedLabel.resolve(), vm::openWatchedDialog, Modifier.testTag("browse_watched"))
        }
        when {
            error != null && state.items.isEmpty() -> ScreenErrorView(error, vm::retry, onBack = { navigator.backToHome(tab) })
            state.loading && state.items.isEmpty() -> LoadingScreen()
            state.noMatches -> Text(
                stringResource(R.string.browse_no_matches),
                style = MaterialTheme.typography.titleMedium,
                color = HomeTvColors.Muted,
                modifier = Modifier.padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 24.dp).testTag("browse_no_matches"),
            )
            else -> PosterGridPane(
                items = state.items,
                focusVersion = state.focus?.version,
                restoreIndex = vm::restoreIndex,
                pageFailed = state.pageFailed,
                header = sortButton,
                onFocused = vm::onFocused,
                onClick = vm::onCardClick,
                onRetry = vm::retry,
                retryTag = "browse_retry",
            )
        }
    }
    state.dialog?.let { dialog ->
        ListDialog(dialog.title.resolve(), dialog.items.map { it.resolve() }, dialog.selected, vm::onDialogSelect, vm::onDialogDismiss)
    }
}
