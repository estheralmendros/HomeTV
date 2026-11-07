package com.hometv.tv.testing

import com.hometv.tv.core.api.ApiClients
import com.hometv.tv.core.data.InMemorySessionStore
import com.hometv.tv.core.data.Session
import com.hometv.tv.core.playback.AudioRouteCaps
import com.hometv.tv.core.playback.CodecInventory
import com.hometv.tv.core.playback.Mimes
import com.hometv.tv.core.playback.VideoDecoder
import com.hometv.tv.data.HomeCache
import com.hometv.tv.di.AppConfig
import com.hometv.tv.di.AppContainer
import com.hometv.tv.playback.CodecSource
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/** A device with hardware H.264 + HEVC (1080p), AC3 decoder, stereo route. */
class FakeCodecs(
    var inventory: CodecInventory = CodecInventory(
        video = listOf(VideoDecoder(Mimes.VIDEO_H264, true, 1920, 1080), VideoDecoder(Mimes.VIDEO_HEVC, true, 1920, 1080)),
        audioDecoderMimes = setOf(Mimes.AUDIO_AC3, Mimes.AUDIO_OPUS, Mimes.AUDIO_FLAC, Mimes.AUDIO_VORBIS),
    ),
    var route: AudioRouteCaps = AudioRouteCaps(emptySet(), 2),
) : CodecSource {
    override suspend fun inventory(): CodecInventory = inventory

    override fun audioRoute(): AudioRouteCaps = route
}

/** An AppContainer over a [ScriptedApi] (no network, no Android context). */
fun testContainer(
    api: ScriptedApi,
    scope: CoroutineScope,
    session: Session = Session(serverUrl = TEST_SERVER, profileId = 5, token = "tok", expiresAt = java.time.Instant.parse("2026-12-20T00:00:00Z")),
    clock: java.time.Clock = java.time.Clock.systemUTC(),
    /** The calendar's zone: the [clock]'s by default, so dates never depend on the host's time zone. */
    zone: java.time.ZoneId = clock.zone,
    cacheIo: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    /** The EXO controller of each Player screen. */
    player: () -> com.hometv.tv.core.playback.PlayerController = { FakePlayerController() },
    /** The VLC controller of each Player screen (built only for files that play on libVLC). */
    vlcPlayer: () -> com.hometv.tv.core.playback.PlayerController = { FakePlayerController() },
    /** `AppContainer.vlcAvailability`: false keeps every file on ExoPlayer. */
    vlcAvailable: Boolean = false,
    /** `AppContainer.enginePrefs` (the device preference "Usar siempre VLC (experimental)"). */
    enginePrefs: com.hometv.tv.data.EnginePrefs = com.hometv.tv.data.InMemoryEnginePrefs(),
    /** The licence notice text (production: `res/raw/third_party_notices.txt`). */
    notices: String = "THIRD PARTY NOTICES",
): AppContainer = AppContainer(
    store = InMemorySessionStore(session),
    homeCache = HomeCache(Files.createTempDirectory("home_cache").toFile(), cacheIo),
    appScope = scope,
    config = AppConfig(versionName = "0.1.0", allowDebugCleartext = true),
    clientsFactory = { url -> ApiClients(url, OkHttpClient(), OkHttpClient(), api) },
    validationClient = OkHttpClient(),
    codecs = FakeCodecs(),
    clock = clock,
    zone = { zone },
    playerControllers = { engine, _ -> if (engine == com.hometv.tv.core.playback.PlaybackEngine.EXO) player() else vlcPlayer() },
    vlcAvailability = com.hometv.tv.playback.VlcAvailability { vlcAvailable },
    subtitleCache = com.hometv.tv.playback.SubtitleFileCache(Files.createTempDirectory("subtitles").toFile()),
    enginePrefs = enginePrefs,
    notices = { notices },
)
