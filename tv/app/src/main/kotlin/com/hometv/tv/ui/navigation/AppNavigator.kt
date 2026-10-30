package com.hometv.tv.ui.navigation

import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import com.hometv.tv.di.AppContainer
import com.hometv.tv.ui.components.NavTab

/** All navigation of the app (Back table, top nav, player launches), shared by the screens. */
class AppNavigator(
    private val nav: NavHostController,
    private val container: AppContainer,
    private val exitApp: () -> Unit,
) {
    fun handle(request: NavRequest) = when (request) {
        is NavRequest.Detail -> openDetail(request.type, request.id)
        is NavRequest.Play -> play(request.request)
        is NavRequest.Browse -> openTab(if (request.library == Library.FILMS) NavTab.FILMS else NavTab.SHOWS)
        is NavRequest.Person -> openPerson(request.id)
    }

    /** (10) Person; Back pops it (default NavHost handling, like the detail screens). */
    fun openPerson(id: Long) = nav.navigate(Route.Person(id))

    fun openDetail(type: String, id: Long) {
        when (type) {
            "film" -> nav.navigate(Route.FilmDetail(id))
            "show" -> nav.navigate(Route.ShowDetail(id))
        }
    }

    /**
     * Pushes the Player: registers the launch (and the Home episode payload) in
     * `AppContainer.playerLaunches` and marks Home and the detail screens stale (watch state changes
     * while playing; each reloads when it is visible again).
     */
    fun play(request: PlayRequest) {
        val launchId = container.playerLaunches.register(request.payload)
        container.homeRefresh.markStale()
        container.detailRefresh.markStale()
        nav.navigate(Route.Player(launchId, request.itemType, request.itemId, request.mediaFileId, request.fromStart))
    }

    /** Leaves the Player (the Player screen calls this on exit). */
    fun exitPlayer(launchId: String) {
        container.playerLaunches.finish(launchId)
        val current = nav.currentBackStackEntry ?: return
        val isThisPlayer = current.destination.hasRoute<Route.Player>() && current.toRoute<Route.Player>().launchId == launchId
        if (isThisPlayer) nav.popBackStack()
    }

    /** Top-nav tabs: Browse/Search/Mi lista/Settings sit directly above Home, so Back always returns to Home. */
    fun openTab(tab: NavTab) {
        val route: Route = when (tab) {
            NavTab.HOME -> return backToHome(null)
            NavTab.FILMS -> Route.Browse(Library.FILMS.wire)
            NavTab.SHOWS -> Route.Browse(Library.SHOWS.wire)
            NavTab.WATCHLIST -> Route.Watchlist
            NavTab.SEARCH -> Route.Search
            NavTab.SETTINGS -> Route.Settings
        }
        nav.navigate(route) {
            popUpTo<Route.Home>()
            launchSingleTop = true
        }
    }

    /** Back from Browse / Search / Mi lista / Settings: Home with focus on the corresponding tab. */
    fun backToHome(tab: NavTab?) {
        val home = runCatching { nav.getBackStackEntry<Route.Home>() }.getOrNull()
        if (home == null) {
            nav.navigate(Route.Home) { popUpTo(nav.graph.id) { inclusive = true } }
            return
        }
        home.savedStateHandle[FOCUS_TAB] = tab?.name
        nav.popBackStack<Route.Home>(inclusive = false)
    }

    fun back() {
        if (!nav.popBackStack()) exitApp()
    }

    fun exit() = exitApp()

    fun switchProfile() = nav.navigate(Route.Profiles(switching = true))

    fun changeServer() = nav.navigate(Route.Setup(initial = false))

    /** After Setup: a new server (or no session) restarts at the Picker; the same server opens it as a switch. */
    fun serverConnected(changed: Boolean) {
        if (changed || container.store.session.value.token == null) {
            nav.navigate(Route.Profiles()) { popUpTo(nav.graph.id) { inclusive = true } }
        } else {
            nav.navigate(Route.Profiles(switching = true)) { popUpTo<Route.Setup> { inclusive = true } }
        }
    }

    fun signedIn() = nav.navigate(Route.Home) { popUpTo(nav.graph.id) { inclusive = true } }

    /** `AppEvent.AuthExpired`: the only path to the Picker, with the "Tu sesión ha caducado…" banner. */
    fun authExpired() = nav.navigate(Route.Profiles(expired = true)) { popUpTo(nav.graph.id) { inclusive = true } }

    companion object {
        const val FOCUS_TAB = "focus_tab"
    }
}
