package com.hometv.tv.ui.activity

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.components.ActionButton
import com.hometv.tv.ui.components.AvatarUi
import com.hometv.tv.ui.components.LoadingScreen
import com.hometv.tv.ui.components.NavTab
import com.hometv.tv.ui.components.ScreenErrorView
import com.hometv.tv.ui.components.TopNav
import com.hometv.tv.ui.components.rememberFocusRequesters
import com.hometv.tv.ui.components.requestFocusWhenReady
import com.hometv.tv.ui.navigation.AppNavigator
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens

/**
 * (12) Actividad: the top nav with "Actividad" selected, the header and a list of
 * [ActivityRow]s (`activity_row_<i>`); OK opens the title's detail, Back from it restores the row, Back
 * here goes Home with focus on the tab. Empty: the glossary text (focus stays on the tab); page 1
 * failing: the shared error screen; a next page failing: the inline retry row (`activity_retry`).
 */
@Composable
fun ActivityScreen(vm: ActivityViewModel, navigator: AppNavigator, avatar: AvatarUi?, showSettings: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tabs = rememberFocusRequesters<NavTab>()
    val rows = rememberFocusRequesters<Int>()
    val list = rememberLazyListState()
    BackHandler { navigator.backToHome(NavTab.ACTIVITY) }
    LaunchedEffect(vm) { vm.navigation.collect(navigator::handle) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onVisible() }
    // A row takes focus when the list appears on a screen nothing holds focus on (opening it, Back from a
    // detail), or for a new focus request (a reload after Back); a reload while the top nav has focus leaves it.
    var screenHasFocus by remember { mutableStateOf(false) }
    var seenFocusVersion by remember { mutableStateOf(state.focus?.version) }
    LaunchedEffect(state.items.isNotEmpty(), state.focus?.version) {
        if (state.items.isEmpty()) return@LaunchedEffect
        val version = state.focus?.version
        val fresh = version != seenFocusVersion
        seenFocusVersion = version
        if (!fresh && screenHasFocus) return@LaunchedEffect
        // Only a new request names its row; a kept one is stale (an earlier round trip): the last focused row.
        val index = state.focus?.takeIf { fresh }?.index ?: vm.restoreIndex()
        list.scrollToItem(index)
        rows[index].requestFocusWhenReady()
    }
    // Nothing to focus in the body (empty): the "Actividad" tab keeps (or takes) focus.
    LaunchedEffect(state.phase) {
        if (state.phase == ActivityPhase.EMPTY && !screenHasFocus) tabs[NavTab.ACTIVITY].requestFocusWhenReady()
    }
    Column(Modifier.fillMaxSize().background(HomeTvColors.Background).onFocusChanged { screenHasFocus = it.hasFocus }) {
        TopNav(NavTab.ACTIVITY, navigator::openTab, avatar, navigator::switchProfile, tabs, showSettings)
        Text(
            stringResource(R.string.activity_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 4.dp).testTag("activity_header"),
        )
        val error = state.error
        when (state.phase) {
            ActivityPhase.LOADING -> LoadingScreen()
            ActivityPhase.ERROR -> ScreenErrorView(error ?: ScreenError.LoadFailed, vm::retry, onBack = { navigator.backToHome(NavTab.ACTIVITY) })
            ActivityPhase.EMPTY -> Text(
                stringResource(R.string.activity_empty),
                style = MaterialTheme.typography.titleMedium,
                color = HomeTvColors.Muted,
                modifier = Modifier.padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 24.dp).testTag("activity_empty"),
            )
            ActivityPhase.CONTENT -> LazyColumn(
                state = list,
                contentPadding = PaddingValues(horizontal = HomeTvDimens.SafeHorizontal, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().testTag("activity_list"),
            ) {
                itemsIndexed(state.items, key = { _, row -> row.key }) { index, row ->
                    ActivityRow(
                        row,
                        onClick = { vm.onRowClick(row) },
                        modifier = Modifier
                            .focusRequester(rows[index])
                            .testTag("activity_row_$index")
                            .onFocusChanged { if (it.isFocused) vm.onFocused(index) },
                    )
                }
                if (state.pageFailed) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.browse_load_more_failed), style = MaterialTheme.typography.bodyMedium, color = HomeTvColors.Error)
                            ActionButton(stringResource(R.string.action_retry), vm::retry, Modifier.testTag("activity_retry"))
                        }
                    }
                }
            }
        }
    }
}
