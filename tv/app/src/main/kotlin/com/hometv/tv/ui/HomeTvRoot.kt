package com.hometv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.hometv.tv.di.AppContainer
import com.hometv.tv.ui.activity.ActivityScreen
import com.hometv.tv.ui.browse.BrowseScreen
import com.hometv.tv.ui.components.AvatarUi
import com.hometv.tv.ui.components.LocalAppImageLoader
import com.hometv.tv.ui.components.LocalArtwork
import com.hometv.tv.ui.components.NavTab
import com.hometv.tv.ui.detail.FilmDetailScreen
import com.hometv.tv.ui.detail.ShowDetailScreen
import com.hometv.tv.ui.home.HomeScreen
import com.hometv.tv.ui.navigation.AppNavigator
import com.hometv.tv.ui.navigation.Library
import com.hometv.tv.ui.navigation.Route
import com.hometv.tv.ui.person.PersonScreen
import com.hometv.tv.ui.player.PlayerScreen
import com.hometv.tv.ui.profiles.ProfilePickerScreen
import com.hometv.tv.ui.ratings.RatingPromptHost
import com.hometv.tv.ui.search.SearchScreen
import com.hometv.tv.ui.settings.SettingsScreen
import com.hometv.tv.ui.setup.SetupScreen
import com.hometv.tv.ui.text.LocalUiFormat
import com.hometv.tv.ui.text.ResourcesStringResolver
import com.hometv.tv.ui.text.UiFormat
import com.hometv.tv.ui.theme.HomeTvColors
import com.hometv.tv.ui.theme.HomeTvTheme
import com.hometv.tv.ui.watchlist.WatchlistScreen

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/**
 * Theme + type-safe NavHost + global events. `AppEvent.AuthExpired` (renewal answered 404
 * `profile_not_found`) clears the back stack to the Profile Picker with the expired banner.
 * `AppEvent.ServerUnreachable` needs no handling here: the foreground call that triggered it also
 * throws, and that screen shows the Connection Error (shown once). `AppEvent.ItemGone` is handled by
 * `HomeViewModel` (rows) and the container (Home cache). [RatingPromptHost] (after the NavHost) shows
 * the rating prompts (ratings prd3 FR-T7).
 */
@Composable
fun HomeTvRoot(container: AppContainer, onExit: () -> Unit, keyDispatch: KeyDispatch = remember { KeyDispatch() }) {
    val context = LocalContext.current
    val uiFormat = remember { UiFormat(ResourcesStringResolver.spanish(context)) }
    val session by container.store.session.collectAsStateWithLifecycle()
    val imageLoader = remember(session.serverUrl) { container.imageLoader(context) }
    HomeTvTheme {
        CompositionLocalProvider(
            LocalAppContainer provides container,
            LocalUiFormat provides uiFormat,
            LocalKeyDispatch provides keyDispatch,
            LocalArtwork provides container::artworkUrl,
            LocalAppImageLoader provides imageLoader,
        ) {
            AppNavHost(container, onExit)
        }
    }
}

@Composable
private fun AppNavHost(container: AppContainer, onExit: () -> Unit) {
    val nav = rememberNavController()
    val navigator = remember(nav) { AppNavigator(nav, container, onExit) }
    val start = remember { container.startDestination() }
    val profile by container.account.profile.collectAsStateWithLifecycle()
    val info by container.account.info.collectAsStateWithLifecycle()
    val avatar = profile?.let { p -> AvatarUi.of(p.name, p.avatar_id, info?.avatars) }
    // "Ajustes" is an admin-only tab (prd3 §3 step 1); hidden while the profile is still unknown.
    val showSettings = profile?.is_admin == true

    val expired by container.sessionExpired.collectAsStateWithLifecycle()
    LaunchedEffect(expired) {
        if (expired) {
            navigator.authExpired()
            container.consumeSessionExpired()
        }
    }
    val session by container.store.session.collectAsStateWithLifecycle()
    // The top-nav avatar after a cold start (no Picker this time): `GET /api/me` + `/api/info` in the background.
    // If this fails (server unreachable), `HomeViewModel` retries via `AccountState.ensureLoaded` after its next fresh load.
    LaunchedEffect(session.token != null) { if (session.token != null && profile == null) container.account.refresh() }

    NavHost(nav, startDestination = start, modifier = Modifier.fillMaxSize().background(HomeTvColors.Background)) {
        composable<Route.Setup> { entry ->
            val route = entry.toRoute<Route.Setup>()
            SetupScreen(
                vm = viewModel { container.setupViewModel() },
                onConnected = navigator::serverConnected,
                onBack = { if (route.initial) navigator.exit() else navigator.back() },
            )
        }
        composable<Route.Profiles> { entry ->
            val route = entry.toRoute<Route.Profiles>()
            ProfilePickerScreen(
                vm = viewModel { container.profilesViewModel(route.expired) },
                onSignedIn = navigator::signedIn,
                onBack = { if (route.switching) navigator.back() else navigator.exit() },
            )
        }
        composable<Route.Home> { entry ->
            val focusTab by entry.savedStateHandle.getStateFlow<String?>(AppNavigator.FOCUS_TAB, null).collectAsStateWithLifecycle()
            HomeScreen(
                vm = viewModel { container.homeViewModel() },
                navigator = navigator,
                avatar = avatar,
                showSettings = showSettings,
                focusTab = focusTab?.let { name -> NavTab.entries.firstOrNull { it.name == name } },
                onFocusTabConsumed = { entry.savedStateHandle[AppNavigator.FOCUS_TAB] = null },
            )
        }
        composable<Route.Browse> { entry ->
            val library = Library.fromWire(entry.toRoute<Route.Browse>().library)
            BrowseScreen(viewModel { container.browseViewModel(library) }, library, navigator, avatar, showSettings)
        }
        composable<Route.Search> {
            SearchScreen(viewModel { container.searchViewModel() }, navigator, avatar, showSettings)
        }
        composable<Route.Watchlist> {
            WatchlistScreen(viewModel { container.watchlistViewModel() }, navigator, avatar, showSettings)
        }
        composable<Route.Activity> {
            ActivityScreen(viewModel { container.activityViewModel() }, navigator, avatar, showSettings)
        }
        composable<Route.Settings> {
            SettingsScreen(viewModel { container.settingsViewModel() }, navigator, avatar, showSettings)
        }
        composable<Route.FilmDetail> { entry ->
            val id = entry.toRoute<Route.FilmDetail>().id
            FilmDetailScreen(viewModel { container.filmDetailViewModel(id) }, navigator)
        }
        composable<Route.ShowDetail> { entry ->
            val id = entry.toRoute<Route.ShowDetail>().id
            ShowDetailScreen(viewModel { container.showDetailViewModel(id) }, navigator)
        }
        composable<Route.Person> { entry ->
            val id = entry.toRoute<Route.Person>().id
            PersonScreen(viewModel { container.personViewModel(id) }, navigator)
        }
        composable<Route.Player> { entry ->
            val route = entry.toRoute<Route.Player>()
            PlayerScreen(viewModel { container.playerViewModel(route) }, onExit = { navigator.exitPlayer(route.launchId) })
        }
    }
    // The rating prompt opens over whatever screen is current (never the Player, Setup or the Picker).
    RatingPromptHost(container, nav)
}
