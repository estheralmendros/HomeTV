package com.hometv.tv.di

import android.content.Context
import com.hometv.tv.BuildConfig
import com.hometv.tv.core.api.ApiClients
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.core.playback.SubtitleLook
import com.hometv.tv.data.EnginePrefs
import com.hometv.tv.data.HomeCache
import com.hometv.tv.data.InMemoryEnginePrefs
import com.hometv.tv.playback.CodecSource
import com.hometv.tv.playback.SubtitleFileCache
import com.hometv.tv.playback.SubtitleStore
import com.hometv.tv.playback.VlcAvailability
import com.hometv.tv.ui.settings.SettingsPolicy
import java.io.File
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** The TV app's container: the shared graph with the shared-TV Ajustes policy, created by [com.hometv.tv.HomeTvApplication]. */
class AppContainer(
    store: SessionStore,
    homeCache: HomeCache,
    appScope: CoroutineScope,
    config: AppConfig,
    clientsFactory: (serverUrl: String) -> ApiClients,
    validationClient: OkHttpClient,
    codecs: CodecSource,
    clock: Clock = Clock.systemUTC(),
    zone: () -> ZoneId = ZoneId::systemDefault,
    playerControllers: (engine: PlaybackEngine, streamClient: OkHttpClient, look: () -> SubtitleLook) -> PlayerController =
        { _, _, _ -> error("No player in this container") },
    vlcAvailability: VlcAvailability = VlcAvailability { false },
    subtitleCache: SubtitleStore = SubtitleFileCache(File(System.getProperty("java.io.tmpdir"), "hometv-subtitles")),
    enginePrefs: EnginePrefs = InMemoryEnginePrefs(),
    notices: suspend () -> String = { "" },
    settingsPolicy: SettingsPolicy = SettingsPolicy.TV,
) : SharedContainer(
    store, homeCache, appScope, config, clientsFactory, validationClient, codecs, clock, zone, playerControllers,
    vlcAvailability, subtitleCache, enginePrefs, notices, settingsPolicy,
) {
    companion object {
        /** Production wiring. */
        fun create(context: Context): AppContainer {
            val app = context.applicationContext
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val parts = androidParts(app, appScope, BuildConfig.VERSION_NAME, BuildConfig.DEBUG, displayHeightPx = { app.resources.displayMetrics.heightPixels })
            return AppContainer(
                store = parts.store,
                homeCache = parts.homeCache,
                appScope = appScope,
                config = parts.config,
                clientsFactory = parts.clientsFactory,
                validationClient = parts.validationClient,
                codecs = parts.codecs,
                playerControllers = parts.playerControllers,
                vlcAvailability = parts.vlcAvailability,
                subtitleCache = parts.subtitleCache,
                enginePrefs = parts.enginePrefs,
                notices = parts.notices,
            )
        }
    }
}
