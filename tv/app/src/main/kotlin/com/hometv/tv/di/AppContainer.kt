package com.hometv.tv.di

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.hometv.tv.BuildConfig
import com.hometv.tv.R
import com.hometv.tv.core.api.ApiClientFactory
import com.hometv.tv.core.api.ApiClients
import com.hometv.tv.core.api.Avatar
import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.api.HomeTvApi
import com.hometv.tv.core.api.apiCall
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.data.AppEvents
import com.hometv.tv.core.data.CatalogRepository
import com.hometv.tv.core.data.NextEpisodeResolver
import com.hometv.tv.core.data.OkHttpSubtitleSource
import com.hometv.tv.core.data.Origin
import com.hometv.tv.core.data.PlaybackRepository
import com.hometv.tv.core.data.ProfileRepository
import com.hometv.tv.core.data.RatingsRepository
import com.hometv.tv.core.data.ServerRepository
import com.hometv.tv.core.data.Session
import com.hometv.tv.core.data.SessionGuard
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.data.SubtitleSource
import com.hometv.tv.core.data.WatchRepository
import com.hometv.tv.core.data.WatchlistRepository
import com.hometv.tv.core.playback.CapsForFile
import com.hometv.tv.core.playback.ClientCapsBuilder
import com.hometv.tv.core.playback.EngineHistory
import com.hometv.tv.core.playback.EngineInputs
import com.hometv.tv.core.playback.EngineSelector
import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.core.util.ServerUrl
import com.hometv.tv.data.AccountState
import com.hometv.tv.data.DataStoreEnginePrefs
import com.hometv.tv.data.DataStoreSessionStore
import com.hometv.tv.data.EnginePrefs
import com.hometv.tv.data.HomeCache
import com.hometv.tv.data.DetailRefresh
import com.hometv.tv.data.HomeRefresh
import com.hometv.tv.data.InMemoryEnginePrefs
import com.hometv.tv.data.PlayerLaunches
import com.hometv.tv.data.RatingPrompts
import com.hometv.tv.data.SignOut
import com.hometv.tv.playback.AndroidCodecInventory
import com.hometv.tv.playback.CodecSource
import com.hometv.tv.playback.ExoPlayerController
import com.hometv.tv.playback.LibVlcPort
import com.hometv.tv.playback.LibVlcProvider
import com.hometv.tv.playback.VlcAvailability
import com.hometv.tv.playback.VlcOptions
import com.hometv.tv.playback.VlcPlayerController
import com.hometv.tv.playback.PlayerFactory
import com.hometv.tv.playback.SubtitleFileCache
import com.hometv.tv.playback.SubtitleStore
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.core.playback.VersionCaps
import com.hometv.tv.ui.player.PlayerDeps
import com.hometv.tv.ui.player.PlayerViewModel
import com.hometv.tv.ui.activity.ActivityViewModel
import com.hometv.tv.ui.browse.BrowseSortMemory
import com.hometv.tv.ui.browse.BrowseViewModel
import com.hometv.tv.ui.detail.FilmDetailViewModel
import com.hometv.tv.ui.detail.ShowDetailViewModel
import com.hometv.tv.ui.home.HomeViewModel
import com.hometv.tv.ui.navigation.Library
import com.hometv.tv.ui.navigation.Route
import com.hometv.tv.ui.person.PersonViewModel
import com.hometv.tv.ui.profiles.ProfilesViewModel
import com.hometv.tv.ui.search.SearchViewModel
import com.hometv.tv.ui.settings.SettingsViewModel
import com.hometv.tv.ui.setup.SetupViewModel
import com.hometv.tv.ui.watchlist.WatchlistMemory
import com.hometv.tv.ui.watchlist.WatchlistViewModel
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.videolan.libvlc.LibVLC

data class AppConfig(
    val versionName: String,
    /** Debug builds also accept `http://10.0.2.2:<port>` and `http://localhost:<port>`. */
    val allowDebugCleartext: Boolean,
)

/**
 * Manual DI: one container per process, created by
 * [com.hometv.tv.HomeTvApplication]. Everything Android-specific is injected, so the container
 * also runs on the JVM in tests.
 *
 * Exposes: [store] (loaded before the API client is built: `AuthInterceptor` reads the token
 * synchronously), [clients] (API + stream OkHttp clients and Retrofit, rebuilt when the server URL
 * changes), [api], [guard], the repositories, [events] (global `AppEvent` bus), [appScope]
 * (`SupervisorJob() + Dispatchers.IO`, process lifetime: final progress PUT, HLS DELETE, [watchlist] and [ratings] writes),
 * [codecs] + [capsForFile] (capability hook), [playerLaunches], [homeRefresh], [detailRefresh],
 * [watchlistMemory], [account], [ratings] + [ratingPrompts] (every rating change marks Home stale),
 * [signOut] (Home's "Salir": prompts, account, sort memories and the stored profile),
 * [imageLoader] and one factory function per ViewModel.
 */
class AppContainer(
    val store: SessionStore,
    val homeCache: HomeCache,
    val appScope: CoroutineScope,
    val config: AppConfig,
    private val clientsFactory: (serverUrl: String) -> ApiClients,
    validationClient: OkHttpClient,
    val codecs: CodecSource,
    private val clock: Clock = Clock.systemUTC(),
    /** The calendar's time zone (Actividad's relative times, ages on the Person screen): the device's, read each time; tests pin it. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** One controller per engine and Player screen, on the stream client of the current server (fakes in tests). */
    private val playerControllers: (engine: PlaybackEngine, streamClient: OkHttpClient) -> PlayerController = { _, _ -> error("No player in this container") },
    /** Whether libVLC can run here (never creates LibVLC): read per playback request and by the version tiers. */
    val vlcAvailability: VlcAvailability = VlcAvailability { false },
    /** `cacheDir/subtitles` (side-loaded WebVTT files). */
    val subtitleCache: SubtitleStore = SubtitleFileCache(File(System.getProperty("java.io.tmpdir"), "hometv-subtitles")),
    /** Per-device player preferences ("Usar siempre VLC (experimental)"); a separate file, untouched by sign-out and server changes. */
    val enginePrefs: EnginePrefs = InMemoryEnginePrefs(),
    /** The third-party licence notice (`res/raw/third_party_notices.txt`, read on IO by [create]'s lambda). */
    private val notices: suspend () -> String = { "" },
) {
    val events = AppEvents()

    private val clientsLock = Any()

    @Volatile
    private var current: ApiClients? = null

    /** The clients for the stored server URL (rebuilt with the Coil loader when it changes). */
    fun clients(): ApiClients {
        val url = checkNotNull(store.session.value.serverUrl) { "No server URL stored" }
        current?.takeIf { it.serverUrl == url }?.let { return it }
        synchronized(clientsLock) {
            current?.takeIf { it.serverUrl == url }?.let { return it }
            return clientsFactory(url).also { current = it }
        }
    }

    val api: () -> HomeTvApi = { clients().api }
    val guard = SessionGuard(store, { id -> apiCall { api().createSession(id) } }, events, clock)
    val catalog = CatalogRepository(api, guard)
    val profiles = ProfileRepository(api, store, guard)
    val watch = WatchRepository(api, guard)
    val watchlist = WatchlistRepository(api, guard, appScope)
    val ratings = RatingsRepository(api, guard, appScope, log = { e -> runCatching { android.util.Log.w("HomeTvRatings", "dismiss-prompt failed", e) } })

    /** Rating prompts waiting for the root `RatingPromptHost`. */
    val ratingPrompts = RatingPrompts(store.session, appScope)
    val playback = PlaybackRepository(api, guard, appScope, SubtitleSource { url -> OkHttpSubtitleSource(clients().streamClient).fetch(url) })
    val nextEpisodes = NextEpisodeResolver(fetch = { id -> catalog.episode(id, Origin.Background) })
    val serverRepository = ServerRepository(validationClient)
    val account = AccountState(profiles)
    val homeRefresh = HomeRefresh()
    val detailRefresh = DetailRefresh()
    val browseSorts = BrowseSortMemory()
    val watchlistMemory = WatchlistMemory()
    val playerLaunches = PlayerLaunches()

    /** "Salir" on Home: forgets the profile on this device (declared after everything it resets). */
    val signOut = SignOut(
        store, ratingPrompts, account, browseSorts, watchlistMemory,
        log = { e -> runCatching { android.util.Log.w("HomeTvSession", "sign-out write failed", e) } },
    )

    private val _sessionExpired = MutableStateFlow(false)

    /**
     * Latched `AppEvent.AuthExpired` (the renewal answered 404 `profile_not_found`): `HomeTvRoot`
     * navigates to the Picker and consumes it. A state, so an event emitted before the UI subscribed
     * (e.g. during the first composition) is not lost.
     */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    fun consumeSessionExpired() {
        _sessionExpired.value = false
    }

    init {
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.events.collect { event ->
                when (event) {
                    AppEvent.AuthExpired -> _sessionExpired.value = true
                    // A removed title must not come back from the Home cache, even when Home is not alive.
                    is AppEvent.ItemGone -> homeCache.remove(event.type, event.id)
                    is AppEvent.ServerUnreachable -> Unit
                }
            }
        }
        // A rating saved anywhere changes the card badges: Home reloads on its next visit.
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            ratings.changes.collect { homeRefresh.markStale() }
        }
    }

    /** Launch routing (Screen / Navigation Map). */
    fun startDestination(session: Session = store.session.value): Route = when {
        session.serverUrl == null -> Route.Setup(initial = true)
        session.token == null -> Route.Profiles()
        else -> Route.Home
    }

    /**
     * Activity `ON_START` (also at launch): proactive refresh within 24 h of `expiresAt`, which also
     * silently replaces an expired token for the remembered profile.
     */
    fun onActivityStart(): Job = appScope.launch {
        if (store.session.value.serverUrl != null) guard.ensureFresh()
    }

    /** Per-file refined caps with the current audio route (Film detail, and every playback request of the Player). */
    suspend fun capsForFile(): CapsForFile {
        val inventory = codecs.inventory()
        val route = codecs.audioRoute()
        return CapsForFile { file -> ClientCapsBuilder.build(inventory, route, forFile = file) }
    }

    /**
     * Exo-first version tiers (Film detail's default version): `exo` = [capsForFile] (today's), `any` = the
     * engine caps per file (VLC caps for a file only libVLC plays, or for every file while the device preference
     * "Usar siempre VLC" is on, as in the Player); without libVLC `exo == any`.
     */
    suspend fun versionCaps(): VersionCaps {
        val inputs = EngineInputs(codecs.inventory(), codecs.audioRoute(), vlcAvailable = vlcAvailability.isAvailable(), forceVlc = enginePrefs.forceVlc.value)
        return VersionCaps(exo = capsForFile(), any = EngineSelector.capsForFile(inputs) { EngineHistory() })
    }

    /** The device caps without a file (Settings → Acerca de summary). */
    suspend fun deviceCaps(): ClientCaps = ClientCapsBuilder.build(codecs.inventory(), codecs.audioRoute())

    /** API-relative artwork path → absolute URL on the server (Coil sends the Bearer header; no `?token=`). */
    fun artworkUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val server = store.session.value.serverUrl ?: return null
        return runCatching { ServerUrl.resolve(server, path) }.getOrNull()
    }

    /** A different server: drop the Home cache and the in-memory account (the store already cleared the token). */
    suspend fun onServerChanged() {
        homeCache.clear()
        account.clear()
    }

    /** Owns the memory and disk caches, built once: Coil allows one active DiskCache per directory. */
    private var baseImageLoader: ImageLoader? = null
    private var images: Pair<ApiClients, ImageLoader>? = null

    /**
     * Coil on the API OkHttpClient (Bearer header), 25 % memory cache, 100 MB disk cache in
     * `cacheDir/images`. A server change derives a new loader with `newBuilder()`, which shares the
     * caches of the first one.
     */
    @Synchronized
    fun imageLoader(context: Context): ImageLoader {
        val clients = runCatching { clients() }.getOrNull()
        images?.takeIf { it.first === clients }?.let { return it.second }
        val app = context.applicationContext
        val base = baseImageLoader ?: ImageLoader.Builder(app)
            .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.25).build() }
            .diskCache { DiskCache.Builder().directory(File(app.cacheDir, "images")).maxSizeBytes(100L * 1024 * 1024).build() }
            .crossfade(false)
            .build()
            .also { baseImageLoader = it }
        if (clients == null) return base
        return base.newBuilder().okHttpClient(clients.apiClient).build().also { images = clients to it }
    }

    // ---- ViewModel factories -------------------------------------------------------------

    fun setupViewModel() = SetupViewModel(serverRepository::validate, store, ::onServerChanged, config.allowDebugCleartext)

    fun profilesViewModel(expired: Boolean) = ProfilesViewModel(profiles, store, account::signedIn, expired)

    fun homeViewModel() = HomeViewModel(catalog, homeCache, store, events.events, homeRefresh, account::ensureLoaded, signOut::invoke, ratingPrompts::setHeld)

    fun browseViewModel(library: Library) = BrowseViewModel(library, catalog, store, browseSorts)

    fun searchViewModel() = SearchViewModel(catalog, store)

    fun watchlistViewModel() = WatchlistViewModel(watchlist, store, watchlistMemory)

    /** `/api/info` avatars: the other profiles' colours (ratings block, Actividad). */
    private val avatars: () -> List<Avatar> = { account.info.value?.avatars.orEmpty() }

    fun filmDetailViewModel(id: Long) =
        FilmDetailViewModel(id, catalog, watch, watchlist, ratings, ratingPrompts, store, ::versionCaps, events, homeRefresh, detailRefresh, avatars)

    fun showDetailViewModel(id: Long) = ShowDetailViewModel(id, catalog, watch, watchlist, ratings, ratingPrompts, store, events, homeRefresh, detailRefresh, avatars)

    /** Actividad; relative times use the [clock] in the [zone]. */
    fun activityViewModel() = ActivityViewModel(ratings, store, clock.withZone(zone()), avatars)

    /** The actor screen; "today" (ages) is the [clock]'s date in the [zone]. */
    fun personViewModel(id: Long) = PersonViewModel(id, catalog, store, detailRefresh, today = { LocalDate.now(clock.withZone(zone())) })

    fun settingsViewModel() = SettingsViewModel(
        store, account, profiles, ::deviceCaps, config.versionName,
        enginePrefs = enginePrefs,
        vlcAvailable = vlcAvailability.isAvailable(),
        notices = notices,
    )

    fun playerViewModel(route: Route.Player) = PlayerViewModel(
        route,
        playerLaunches,
        PlayerDeps(
            catalog = catalog,
            playback = playback,
            watch = watch,
            guard = guard,
            nextEpisodes = nextEpisodes,
            events = events,
            appScope = appScope,
            codecs = codecs,
            serverUrl = { store.session.value.serverUrl },
            // Built lazily at the first attach that uses the engine (never before): an ExoPlayer-only screen never
            // builds a libVLC MediaPlayer (nor LibVLC), a VLC-only one never builds an ExoPlayer.
            controllers = { engine -> playerControllers(engine, clients().streamClient) },
            vlcAvailable = vlcAvailability::isAvailable,
            // Read at every playback request: a change in Ajustes applies to the next one.
            forceVlc = { enginePrefs.forceVlc.value },
            subtitles = subtitleCache,
            ratingPrompts = ratingPrompts,
        ),
    )

    companion object {
        /** Production wiring: DataStore loaded synchronously once (a few ms) before any client exists. */
        fun create(context: Context): AppContainer {
            val app = context.applicationContext
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val dataStore = DataStoreSessionStore.dataStore(File(app.filesDir, "datastore/session.preferences_pb"), appScope)
            val deviceStore = DataStoreEnginePrefs.dataStore(File(app.filesDir, "datastore/device.preferences_pb"), appScope)
            // Both files load in parallel: the device file adds no start-up latency.
            val (store, enginePrefs) = runBlocking(Dispatchers.IO) {
                val session = async { DataStoreSessionStore.load(dataStore) }
                val prefs = async { DataStoreEnginePrefs.load(deviceStore) }
                session.await() to prefs.await()
            }
            val userAgent = "HomeTV-Android/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT}; ${Build.MODEL})"
            val base = OkHttpClient()
            val subtitles = SubtitleFileCache(File(app.cacheDir, "subtitles"))
            appScope.launch { subtitles.clear() } // nothing survives a process
            val playerLog: (String, Throwable?) -> Unit = { msg, e -> runCatching { Log.i("HomeTvPlayer", msg, e) } }
            // Nothing is created here: LibVLC loads its native code at the first VLC open (prd2 FR-5).
            val heightPx = app.resources.displayMetrics.heightPixels
            val libVlc = LibVlcProvider(Build.SUPPORTED_ABIS.toList(), { LibVLC(app, VlcOptions.libVlc(heightPx)) }, playerLog)
            val mainHandler = Handler(Looper.getMainLooper())
            return AppContainer(
                store = store,
                homeCache = HomeCache(File(app.filesDir, "home_cache")),
                appScope = appScope,
                config = AppConfig(BuildConfig.VERSION_NAME, allowDebugCleartext = BuildConfig.DEBUG),
                clientsFactory = { url -> ApiClientFactory.create(url, token = { store.session.value.token }, userAgent = userAgent, base = base) },
                validationClient = ApiClientFactory.validationClient(userAgent, base),
                codecs = AndroidCodecInventory(app, appScope),
                playerControllers = { engine, streamClient ->
                    when (engine) {
                        PlaybackEngine.EXO -> ExoPlayerController(PlayerFactory.create(app, streamClient))
                        PlaybackEngine.VLC -> VlcPlayerController(
                            portFactory = { LibVlcPort(libVlc.get()) },
                            token = { store.session.value.token },
                            log = playerLog,
                            post = { block -> mainHandler.post(block) },
                        )
                    }
                },
                vlcAvailability = libVlc.availability,
                subtitleCache = subtitles,
                enginePrefs = enginePrefs,
                notices = { withContext(Dispatchers.IO) { app.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() } } },
            )
        }
    }
}
