package com.hometv.tv.ui.player

import com.hometv.tv.core.api.EpisodeDetail
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.PlaybackRequest
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.SubtitleTrack
import com.hometv.tv.core.data.NextEpisodeResolver
import com.hometv.tv.core.data.Origin
import com.hometv.tv.core.data.PlaybackRepository
import com.hometv.tv.core.data.SubtitleFetch
import com.hometv.tv.core.data.SubtitleSource
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.data.PlayerLaunches
import com.hometv.tv.playback.SubtitleFileCache
import com.hometv.tv.testing.FakeCodecs
import com.hometv.tv.testing.FakePlayerController
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.navigation.Route
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * The Player ViewModel over real core repositories, a [ScriptedApi][com.hometv.tv.testing.ScriptedApi]
 * recording every request, a [FakePlayerController] and virtual time (`appScope` = the test's
 * `backgroundScope`, so final requests are observed after `onCleared()`).
 */
abstract class PlayerTestBase {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    protected val repos = TestRepos()
    protected val api get() = repos.api
    protected val controller = FakePlayerController()

    /** The libVLC engine's controller (a second fake); untouched while [vlcAvailable] is false. */
    protected val vlcController = FakePlayerController()

    /** What `deps.controllers(VLC)` returns: [vlcController] unless a test overrides it (e.g. a real `VlcPlayerController`). */
    protected open fun vlcControllerFor(): PlayerController = vlcController

    /** libVLC is usable in this test (the default of [vlcAvailability], and the AC-3.1 end-of-test check). */
    protected var vlcAvailable = false

    /** `PlayerDeps.vlcAvailable`, read by the ViewModel at every request: [vlcAvailable] unless a test swaps it. */
    protected var vlcAvailability: () -> Boolean = { vlcAvailable }

    /** The admin debug switch, read for every request. */
    protected var forceVlc = false

    /** Every `deps.controllers(engine)` call, in order (lazy creation). */
    protected val createdEngines = mutableListOf<PlaybackEngine>()
    protected val launches = PlayerLaunches()
    protected val subtitleDir = Files.createTempDirectory("subtitles").toFile()
    protected val subtitles = SubtitleFileCache(subtitleDir)
    protected val codecs = FakeCodecs()
    protected val subtitleUrls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    protected var onSubtitle: suspend (String) -> SubtitleFetch = { SubtitleFetch.Ready("WEBVTT\n".toByteArray()) }
    protected val exits = mutableListOf<PlayerEffect>()

    /** The profile's style state and its store, over [repos] (their `appScope`: PUTs run at once). */
    protected val account = com.hometv.tv.data.AccountState(repos.profiles, repos.store, repos.appScope)
    protected val subtitleStyles = com.hometv.tv.data.SubtitleStyleStore(account, repos.profiles, repos.store, repos.appScope)
    private val created = mutableListOf<PlayerViewModel>()
    private val clearedVms = mutableSetOf<PlayerViewModel>()

    /**
     * `runTest` on the Main test dispatcher; every ViewModel created by the test is cleared at the end
     * (its tickers run on the same scheduler and would otherwise keep `runTest` busy forever).
     */
    protected fun playerTest(body: suspend TestScope.() -> Unit) = runTest(main.dispatcher) {
        try {
            body()
        } finally {
            created.filter { it !in clearedVms }.forEach(::clear)
        }
        // AC-3.1: without libVLC (and without the debug switch) the VLC controller is never built, so it gets no call.
        if (!vlcAvailable) assertTrue(PlaybackEngine.VLC !in createdEngines && vlcController.log.isEmpty(), "VLC used: $createdEngines")
    }

    /** Playback answers in order; the last one repeats. */
    private val answers = mutableListOf<suspend (PlaybackRequest) -> PlaybackResponse>()

    protected fun answer(vararg responses: suspend (PlaybackRequest) -> PlaybackResponse) {
        answers.clear()
        answers += responses
    }

    protected fun answerWith(vararg responses: PlaybackResponse) {
        answers.clear()
        responses.forEach { r -> answers += { _: PlaybackRequest -> r } }
    }

    init {
        api.onPlayback = { _, body ->
            val next = if (answers.size > 1) answers.removeAt(0) else answers.firstOrNull() ?: error("no playback answer")
            next(body)
        }
    }

    protected fun TestScope.vm(
        itemType: String = "film",
        itemId: Long = 12,
        mediaFileId: Long? = null,
        fromStart: Boolean = false,
        payload: EpisodeSummary? = null,
        launchId: String = launches.register(payload),
    ): PlayerViewModel {
        val playback = PlaybackRepository(
            { api }, repos.guard, backgroundScope,
            SubtitleSource { url ->
                subtitleUrls += url
                onSubtitle(url)
            },
            clockMs = { testScheduler.currentTime },
        )
        val deps = PlayerDeps(
            catalog = repos.catalog,
            playback = playback,
            watch = repos.watch,
            guard = repos.guard,
            nextEpisodes = NextEpisodeResolver({ id -> repos.catalog.episode(id, Origin.Background) }),
            events = repos.events,
            appScope = backgroundScope,
            codecs = codecs,
            serverUrl = { repos.store.session.value.serverUrl },
            controllers = { engine ->
                createdEngines += engine
                if (engine == PlaybackEngine.EXO) controller else vlcControllerFor()
            },
            vlcAvailable = { vlcAvailability() },
            forceVlc = { forceVlc },
            subtitles = subtitles,
            ratingPrompts = repos.ratingPrompts,
            trackChoices = repos.trackChoices,
            trackChoiceMemory = repos.trackChoiceMemory,
            subtitleStyles = subtitleStyles,
            nowMs = { testScheduler.currentTime },
            io = main.dispatcher,
            log = { _, _ -> },
        )
        val vm = PlayerViewModel(Route.Player(launchId, itemType, itemId, mediaFileId, fromStart), launches, deps)
        created += vm
        vm.effects.onEach { exits += it }.launchIn(backgroundScope)
        return vm
    }

    // ---- Fixtures --------------------------------------------------------------------------

    protected fun film(vararg files: MediaFile = arrayOf(F.file(1)), progress: Progress? = null, id: Long = 12) {
        api.onFilm = { FilmDetail(id = id, title = "Dune", media_files = files.toList(), progress = progress) }
    }

    protected fun episodeDetail(
        id: Long,
        files: List<MediaFile>,
        next: Long? = null,
        progress: Progress? = null,
        season: Int = 2,
        episode: Int = (id % 100).toInt(),
        title: String? = "Episode $id",
    ) = EpisodeDetail(
        id = id, show_id = 7, show_title = "The Office", season_number = season, episode_number = episode, title = title,
        media_files = files, next_episode_id = next, progress = progress,
    )

    protected fun episodes(vararg details: EpisodeDetail) {
        val byId = details.associateBy { it.id }
        api.onEpisode = { id -> byId[id] ?: throw com.hometv.tv.core.data.FakeHomeTvApi.httpError(404, "not_found") }
    }

    protected fun direct(fileId: Long = 1, audio: Int? = 1, duration: Double? = 7200.0, subs: List<SubtitleTrack> = emptyList()) =
        PlaybackResponse("direct", url = "/api/media-files/$fileId/stream", audio_stream_index = audio, duration_seconds = duration, subtitle_tracks = subs)

    protected fun hls(sid: String = "S1", audio: Int? = 1, action: String = "copy", duration: Double? = 7200.0, subs: List<SubtitleTrack> = emptyList()) =
        PlaybackResponse("hls", url = "/api/hls/$sid/index.m3u8", session_id = sid, audio_stream_index = audio, audio_action = action, duration_seconds = duration, subtitle_tracks = subs)

    protected fun unsupported(reason: String) = PlaybackResponse("unsupported", reason = reason, detail = "English diagnostic detail")

    // ---- Driving ---------------------------------------------------------------------------

    /** Starts film 12 on [response] and reaches STATE_READY (playing). */
    protected fun TestScope.startFilm(response: PlaybackResponse, vararg files: MediaFile = arrayOf(F.file(1)), progress: Progress? = null): PlayerViewModel {
        film(*files, progress = progress)
        answerWith(response)
        val vm = vm()
        runCurrent()
        controller.ready()
        runCurrent()
        return vm
    }

    protected fun TestScope.startHls(sid: String = "S1", vararg files: MediaFile = arrayOf(F.file(1))) = startFilm(hls(sid), *files)

    protected fun TestScope.startDirect(vararg files: MediaFile = arrayOf(F.file(1))) = startFilm(direct(), *files)

    protected fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    // ---- Assertions helpers ----------------------------------------------------------------

    protected fun posts() = api.calls.filter { it.startsWith("POST /api/media-files/") }

    protected fun bodies(): List<PlaybackRequest> = api.playbackBodies.map { it.second }

    protected fun deletes() = api.calls.filter { it.startsWith("DELETE /api/hls/") }

    protected fun keepAlives() = api.calls.filter { it.endsWith("/keepalive") }

    protected fun puts() = api.calls.filter { it == "PUT /api/progress" }

    protected fun clearCalls() {
        api.calls.clear()
        api.playbackBodies.clear()
        api.progressBodies.clear()
        controller.clearLog()
    }

    protected fun clear(vm: PlayerViewModel) {
        if (!clearedVms.add(vm)) return
        val store = androidx.lifecycle.ViewModelStore()
        store.put("player", vm)
        store.clear()
    }
}
