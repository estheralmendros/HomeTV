package com.hometv.tv.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens
import com.hometv.tv.ui.theme.HomeTvFocus

/** The top navigation tabs (prd3 §3.1): Inicio, Películas, Series, Buscar, Ajustes. */
enum class NavTab(@param:StringRes val label: Int) {
    HOME(R.string.nav_home),
    FILMS(R.string.nav_films),
    SHOWS(R.string.nav_shows),
    SEARCH(R.string.nav_search),
    SETTINGS(R.string.nav_settings),
}

/** The signed-in profile's avatar on the right of the top nav (opens the Profile Picker). */
data class AvatarUi(val initial: String, val color: String?)

fun parseColor(hex: String?): Color? = hex?.let { runCatching { Color(it.toColorInt()) }.getOrNull() }

/** tv-material TabRow + the profile avatar; Up from the first content row lands here, Down returns. */
@Composable
fun TopNav(
    selected: NavTab,
    onTab: (NavTab) -> Unit,
    avatar: AvatarUi?,
    onAvatar: () -> Unit,
    requesters: FocusRequesters<NavTab>,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Entering the bar (Up from the content) lands on the current screen's tab, not the geometrically nearest one.
        TabRow(
            selectedTabIndex = selected.ordinal,
            modifier = Modifier.focusProperties { onEnter = { requesters[selected].tryRequestFocus() } }.focusGroup(),
        ) {
            NavTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selected,
                    onFocus = onFocused,
                    onClick = { onTab(tab) },
                    modifier = Modifier.focusRequester(requesters[tab]).testTag("tab_${tab.name}"),
                ) {
                    Text(stringResource(tab.label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val description = stringResource(R.string.profiles_switch)
        Surface(
            onClick = onAvatar,
            modifier = Modifier.size(40.dp).testTag("nav_avatar").semantics { contentDescription = description }.onFocusChanged { if (it.isFocused) onFocused() },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = parseColor(avatar?.color) ?: HomeTvColors.SurfaceVariant),
            border = ClickableSurfaceDefaults.border(focusedBorder = HomeTvFocus.circleRing),
            scale = ClickableSurfaceDefaults.scale(focusedScale = HomeTvDimens.AVATAR_FOCUSED_SCALE),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(avatar?.initial.orEmpty(), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** A row title above a lazy row. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(start = HomeTvDimens.SafeHorizontal, top = 16.dp, bottom = 8.dp))
}

val RowSpacing = Arrangement.spacedBy(16.dp)
