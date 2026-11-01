package com.hometv.tv.testing

import com.hometv.tv.core.api.apiCall
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.data.AppEvents
import com.hometv.tv.core.data.CatalogRepository
import com.hometv.tv.core.data.InMemorySessionStore
import com.hometv.tv.core.data.ProfileRepository
import com.hometv.tv.core.data.RatingsRepository
import com.hometv.tv.core.data.Session
import com.hometv.tv.core.data.SessionGuard
import com.hometv.tv.core.data.WatchRepository
import com.hometv.tv.core.data.WatchlistRepository
import com.hometv.tv.data.RatingPrompts
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Real core repositories over a [ScriptedApi] and an in-memory session (the wiring of `AppContainer`). */
class TestRepos(
    val api: ScriptedApi = ScriptedApi(),
    session: Session = Session(serverUrl = SERVER, profileId = 5, token = "tok", expiresAt = Instant.parse("2026-12-20T00:00:00Z")),
    clock: Clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC),
    /** `AppContainer.appScope` (the "Mi lista" write workers, rating writes, the prompt store); pass a scope on the test dispatcher for virtual time. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
) {
    val store = InMemorySessionStore(session)
    val events = AppEvents()
    val guard = SessionGuard(store, { id -> apiCall { api.createSession(id) } }, events, clock)
    val catalog = CatalogRepository({ api }, guard)
    val watch = WatchRepository({ api }, guard)
    val profiles = ProfileRepository({ api }, store, guard)
    val watchlist = WatchlistRepository({ api }, guard, appScope)
    val ratings = RatingsRepository({ api }, guard, appScope)
    val ratingPrompts = RatingPrompts(store.session, appScope)

    /** Collects every AppEvent into [received] (start it in `backgroundScope`; unconfined, so no test-scheduler step is needed). */
    val received = mutableListOf<AppEvent>()

    fun recordEvents(scope: CoroutineScope) {
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { events.events.collect { received += it } }
    }

    companion object {
        const val SERVER = TEST_SERVER
    }
}
