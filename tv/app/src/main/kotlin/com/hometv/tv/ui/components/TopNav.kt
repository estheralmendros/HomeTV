package com.hometv.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.hometv.tv.R
import com.hometv.tv.core.api.Avatar
import com.hometv.tv.data.ProfileAvatar
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvDimens
import com.hometv.tv.ui.theme.HomeTvFocus

/** The top nav tabs in display order: Search (a lens icon, [icon]) at the left end. */
enum class NavTab(@param:StringRes val label: Int, @param:DrawableRes val icon: Int? = null) {
    SEARCH(R.string.nav_search, R.drawable.ic_search),
    HOME(R.string.nav_home),
    FILMS(R.string.nav_films),
    SHOWS(R.string.nav_shows),
    WATCHLIST(R.string.nav_watchlist),
    ACTIVITY(R.string.nav_activity),
    SETTINGS(R.string.nav_settings),
    ;

    companion object {
        /** The tabs a profile sees: "Ajustes" only for admins. */
        fun visible(showSettings: Boolean): List<NavTab> = if (showSettings) entries else entries - SETTINGS
    }
}

/** The signed-in profile's avatar on the right of the top nav (opens the Profile Picker). */
data class AvatarUi(val initial: String, val color: String?) {
    companion object {
        /** [ProfileAvatar.initial]. */
        fun initial(name: String): String = ProfileAvatar.initial(name)

        /** [ProfileAvatar.color]. */
        fun color(avatarId: String?, avatars: List<Avatar>?): String? = ProfileAvatar.color(avatarId, avatars)

        fun of(name: String, avatarId: String?, avatars: List<Avatar>?): AvatarUi = AvatarUi(initial(name), color(avatarId, avatars))
    }
}

fun parseColor(hex: String?): Color? = hex?.let { runCatching { Color(it.toColorInt()) }.getOrNull() }

/** tv-material TabRow + the profile avatar; Up from the first content row lands here, Down returns. */
@Composable
fun TopNav(
    selected: NavTab,
    onTab: (NavTab) -> Unit,
    avatar: AvatarUi?,
    onAvatar: () -> Unit,
    requesters: FocusRequesters<NavTab>,
    showSettings: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    val tabs = NavTab.visible(showSettings)
    // The current screen's tab, or Inicio when it is not on the bar (Settings of a profile just demoted,
    // before the screen leaves): never a pill on the wrong tab or an entry to a detached requester.
    val current = selected.takeIf { it in tabs } ?: NavTab.HOME
    Row(modifier.fillMaxWidth().padding(horizontal = HomeTvDimens.SafeHorizontal, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // While the bar has focus the pill follows the focused tab (tv-material draws the focused tab's
        // label for the pill); when focus leaves, it returns to the current screen's tab.
        var focusedTab by remember { mutableStateOf<NavTab?>(null) }
        val pillTab = focusedTab?.takeIf { it in tabs } ?: current
        // Entering the bar with the D-pad (Up from the content) lands on the current screen's tab, not the
        // geometrically nearest one. Only directional entry is redirected: a programmatic requestFocus (Back
        // from Browse / Search / Settings focuses that screen's tab on Home) also enters the group, with
        // FocusDirection.Enter, and must keep its own target.
        TabRow(
            selectedTabIndex = tabs.indexOf(pillTab),
            modifier = Modifier
                .focusProperties {
                    onEnter = {
                        if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) {
                            requesters[current].tryRequestFocus()
                        }
                    }
                }
                .onFocusChanged { if (!it.hasFocus) focusedTab = null }
                .focusGroup()
                .testTag("nav_tabs"),
        ) {
            tabs.forEach { tab ->
                Tab(
                    selected = tab == pillTab,
                    onFocus = {
                        focusedTab = tab
                        onFocused()
                    },
                    onClick = { onTab(tab) },
                    modifier = Modifier.focusRequester(requesters[tab]).testTag("tab_${tab.name}"),
                ) {
                    // One fixed height for every tab, content centered: the lens icon and the text labels sit
                    // in the middle of the pill (it spans the row's height, set by the tallest tab).
                    Box(Modifier.height(TAB_HEIGHT).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        val icon = tab.icon
                        if (icon != null) {
                            // The label becomes the icon's description: the tab has no visible text.
                            Icon(painterResource(icon), contentDescription = stringResource(tab.label), modifier = Modifier.size(24.dp))
                        } else {
                            Text(stringResource(tab.label), style = MaterialTheme.typography.labelLarge)
                        }
                    }
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
fun SectionTitle(text: String, modifier: Modifier = Modifier, startPadding: Dp = HomeTvDimens.SafeHorizontal) {
    // [startPadding]: the safe-area margin for full-width rows; 0 inside a container that already has it.
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(start = startPadding, top = 16.dp, bottom = 8.dp))
}

val RowSpacing = Arrangement.spacedBy(16.dp)

/** Height of every top-nav tab (the pill's height). */
private val TAB_HEIGHT = 36.dp
