package com.hometv.tv.ui.player

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.AudioTrack
import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.api.EpisodeDetail
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.PlaybackRequest
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.ProgressReport
import com.hometv.tv.core.api.ProgressRequest
import com.hometv.tv.core.api.RatingPrompt
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.data.AppEventSink
import com.hometv.tv.core.data.CatalogRepository
import com.hometv.tv.core.data.InFlightPlayback
import com.hometv.tv.core.data.KeepAliveResult
import com.hometv.tv.core.data.NextEpisodeResolver
import com.hometv.tv.core.data.Origin
import com.hometv.tv.core.data.PlaybackProgress
import com.hometv.tv.core.data.PlaybackRepository
import com.hometv.tv.core.data.RenewOutcome
import com.hometv.tv.core.data.SessionGuard
import com.hometv.tv.core.data.SubtitleFetch
import com.hometv.tv.core.data.WatchRepository
import com.hometv.tv.core.playback.CapsForFile
import com.hometv.tv.core.playback.ClientCapsBuilder
import com.hometv.tv.core.playback.FailureContext
import com.hometv.tv.core.playback.MediaItemSpec
import com.hometv.tv.core.playback.MessageKey
import com.hometv.tv.core.playback.PlaybackDecision
import com.hometv.tv.core.playback.PlaybackErrorMapper
import com.hometv.tv.core.playback.PlaybackMode
import com.hometv.tv.core.playback.PlaybackPlan
import com.hometv.tv.core.playback.PlaybackPlanner
import com.hometv.tv.core.playback.PlayerAction
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.core.playback.PlayerError
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.PlayerState
import com.hometv.tv.core.playback.PreparingEstimate
import com.hometv.tv.core.playback.ProgressReporter
import com.hometv.tv.core.playback.ProgressSnapshot
import com.hometv.tv.core.playback.SeekAccumulator
import com.hometv.tv.core.playback.SideloadedSubtitle
import com.hometv.tv.core.playback.SwitchFailure
import com.hometv.tv.core.playback.TrackCheck
import com.hometv.tv.core.playback.TrackCheckInput
import com.hometv.tv.core.playback.TrackRules
import com.hometv.tv.core.playback.UnsupportedReason
import com.hometv.tv.core.playback.VersionGroup
import com.hometv.tv.core.playback.VersionSelector
import com.hometv.tv.core.util.Format
import com.hometv.tv.core.util.ServerUrl
import com.hometv.tv.data.PlayerLaunches
import com.hometv.tv.data.RatingPrompts
import com.hometv.tv.playback.CodecSource
import com.hometv.tv.playback.SubtitleStore
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.Route
import com.hometv.tv.ui.text.PreparingText
import com.hometv.tv.ui.text.UiFormat
import com.hometv.tv.ui.text.UiText
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the Player needs from the container (faked in JVM tests: [controller] is a fake, [appScope] a test scope). */
class PlayerDeps(
    val catalog: CatalogRepository,
    val playback: PlaybackRepository,
    val watch: WatchRepository,
    val guard: SessionGuard,
    val nextEpisodes: NextEpisodeResolver,
    val events: AppEventSink,
    /** Process-lifetime scope: final progress PUT, HLS DELETE, orphan-request observers (prd3 §8 Lifecycle). */
    val appScope: CoroutineScope,
    val codecs: CodecSource,
    val serverUrl: () -> String?,
    val controller: PlayerController,
    val subtitles: SubtitleStore,
    /** The app's prompt store: the session's last `rating_prompt` is handed to it on exit (ratings prd3 FR-T6). No default. */
    val ratingPrompts: RatingPrompts,
    val nowMs: () -> Long = { SystemClock.uptimeMillis() },
    val io: CoroutineDispatcher = Dispatchers.IO,
    val log: (String, Throwable?) -> Unit = { msg, e -> runCatching { Log.w("HomeTvPlayer", msg, e) } },
)

/**
 * (9) Player (prd3 §8–§10): the Player session state machine of §8 (rows 1–41), progress reporting,
 * HLS keep-alive and the 30 min paused release (D9), D-pad seeking, audio/subtitle switching
 * (direct ↔ HLS), recovery after stream failures (401 renewal, HLS 404, decoder/container fallbacks),
 * the next-episode countdown (D11, D12), the multi-part prompt (D10, D13) and the end screens.
 *
 * Session ownership rules: each attached playback response is an [Attachment] (its HLS session,
 * if any, and its [ProgressReporter]); [finishSession] sends its final PUT and DELETE once
 * (idempotent per attachment), a hand-over (`previous_session_id`) finishes it without DELETE, and a
 * POST whose await is abandoned becomes the orphan request, observed in `appScope` (it survives
 * `onCleared()`) and DELETEd when it carries a session (row 33). Every request first awaits that orphan.
 */
class PlayerViewModel(
    private val route: Route.Player,
    private val launches: PlayerLaunches,
    private val deps: PlayerDeps,
) : ViewModel(), PlayerController.Listener {
    private enum class Kind { START, RECOVERY, HLS_AUDIO_SWITCH, DIRECT_AUDIO_SWITCH, RESTORE_AUDIO, RELEASED_AUDIO, NEXT_EPISODE, NEXT_PART, OTHER_VERSION, CODEC_RETRY }

    private data class RequestSpec(
        val file: MediaFile,
        val startSec: Double,
        val audioIndex: Int?,
        val previousSessionId: String?,
        val kind: Kind,
        val play: Boolean,
        val audioIndexRetried: Boolean = false,
        /** NEXT_EPISODE / NEXT_PART: the remaining versions to try on `unsupported`. */
        val fallbacks: List<MediaFile> = emptyList(),
        val nextEpisode: EpisodeDetail? = null,
        /** Keep the last frame with a spinner instead of the full-screen loading UI. */
        val keepFrame: Boolean = false,
    )

    /** One attached playback response (prd3 §8 Lifecycle `attachmentKey`). */
    private class Attachment(val key: Long, val file: MediaFile, val plan: PlaybackPlan.Play, val caps: ClientCaps, val startMs: Long) {
        lateinit var reporter: ProgressReporter
        var putDone = false

        /** DELETEd or handed over: never keep-alived, DELETEd or recovered again. */
        var released = false
        var sessionLost = false
        var reachedReady = false
        val itemSubtitles = mutableSetOf<String>()
        val mode: PlaybackMode get() = plan.mode
        val sessionId: String? get() = plan.sessionId
        val holdsSession: Boolean get() = plan.sessionId != null && !released
    }

    private class Item(val type: String, val id: Long, var files: List<MediaFile>, var progress: Progress?) {
        var film: FilmDetail? = null
        var showTitle: String? = null
        var season = 0
        var episode = 0
        var episodeTitle: String? = null
        var nextEpisodeId: Long? = null
        var detailLoaded = false
    }

    /** Per-file exclusions and retry flags (kept for the rest of that file's playback, §10). */
    private class FileHistory {
        val video = mutableSetOf<String>()
        val audio = mutableSetOf<String>()
        val containers = mutableSetOf<String>()
        var videoRetried = false
        var audioRetried = false
        var containerRetried = false
    }

    private sealed interface NextTarget {
        data class Episode(val episode: EpisodeDetail, val plan: List<MediaFile>) : NextTarget

        data class Part(val file: MediaFile) : NextTarget
    }

    private val controller = deps.controller
    private val sessions = HlsSessionOwner(deps.appScope) { id -> deps.playback.deleteHlsSession(id) }

    /** The player behind the `PlayerView` (an `ExoPlayerController` in the app, a fake in tests). */
    val playerController: PlayerController get() = controller
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val _effects = Channel<PlayerEffect>(Channel.BUFFERED)
    val effects: Flow<PlayerEffect> = _effects.receiveAsFlow()

    /** The PRD session state (exposed for tests and the screen). */
    var session: SessionState = SessionState.Loading
        private set

    private lateinit var item: Item
    private var file: MediaFile? = null
    private var att: Attachment? = null
    private var nextKey = 1L
    private var switchBase: Attachment? = null
    private var switchAudioIndex: Int? = null
    private var pending: RequestSpec? = null

    /** The file of the most recent request (the subject of an error screen before anything attached). */
    private var requestedFile: MediaFile? = null
    private var preparing: PreparingEstimate? = null
    private var loadJob: Job? = null
    private var itemJob: Job? = null
    private var inFlight: InFlightPlayback? = null
    private var capsFF: CapsForFile = CapsForFile { ClientCaps(emptyList(), emptyList(), emptyList(), 6, true, true) }
    private val history = HashMap<Long, FileHistory>()
    private val refused = mutableSetOf<Long>()
    private var lastRenewalAtMs: Long? = null
    private var lastRecoveryAtMs: Long? = null

    // Tracks.
    private var selectedAudioIndex: Int? = null
    private var selectedSubtitleId: String? = null
    private var subtitleDefaulted = false
    private var pendingSubtitle: String? = null
    private val unavailableSubtitles = mutableSetOf<String>()
    private val unavailableAudio = mutableSetOf<Int>()
    private val subtitleJobs = HashMap<String, Job>()
    private var trackCheckArmed = false
    private var subtitleRebuild = false

    // Timers.
    private var keepAliveJob: Job? = null
    private var pauseTimerJob: Job? = null
    private var tickJob: Job? = null
    private var overlayJob: Job? = null
    private var seekJob: Job? = null
    private var countdownJob: Job? = null
    private var toastJob: Job? = null
    private var noticeJob: Job? = null
    private var releasedPutJob: Job? = null
    private val seek = SeekAccumulator()

    // Released / background.
    private var savedPositionMs = 0L
    private var releasePutPositionMs: Long? = null
    private var resumeOnStart: (() -> Unit)? = null
    private var idleAfterResolve = false

    // Next episode / part.
    private val nextResolution = NextEpisodeResolution(viewModelScope, deps.nextEpisodes, deps.log)
    private val next: NextState get() = nextResolution.state
    private var countdownTarget: NextTarget? = null
    private var countdownSeconds: Int? = null
    private var resolvingForButton = false

    // UI.
    private var overlay = OverlayMode.HIDDEN
    private var dialog: PlayerDialog? = null
    private var screenError: PlayerScreenUi.Error? = null
    private var endScreen: PlayerScreenUi.EndScreen? = null
    private var errorSpec: RequestSpec? = null
    private var errorPositionSec = 0.0
    private var toast: UiText? = null
    private var notice: UiText? = null
    private var noticePending = false
    private var focusPlayPause = 0
    private var exitSent = false

    /** The session's latest `rating_prompt` (kept across attachments: audio switches, recoveries). */
    private val sessionPrompt = AtomicReference<RatingPrompt?>(null)
    private val promptHandedOff = AtomicBoolean(false)

    /** The profile that is watching (a late answer is offered for it; the host drops it under another one). */
    private val profileId: Long? = deps.guard.currentProfileId()
    private var releasedController = false
    private var cleared = false
    private var otherVersions: List<MediaFile> = emptyList()

    init {
        if (!launches.isActive(route.launchId)) {
            // Process death: Navigation restored the route; pop with no request (§8 Lifecycle).
            exit()
        } else {
            controller.setListener(this)
            startItem()
        }
    }

    // ---- Item load and the first request ----------------------------------------------------

    private fun startItem() {
        session = SessionState.Loading
        screenError = null
        render()
        itemJob?.cancel()
        itemJob = viewModelScope.launch {
            val loaded = try {
                loadItem()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                onItemLoadError(e)
                return@launch
            }
            item = loaded
            refreshCaps()
            val choice = chooseStart(loaded) ?: run {
                showError(PlaybackErrorMapper.forUnsupported(UnsupportedReason.PROBE_FAILED, null, false), null)
                return@launch
            }
            noticePending = choice.second.differentVersionNotice
            val play = !startPaused
            startPaused = false
            send(RequestSpec(choice.first, choice.second.positionSeconds, null, null, Kind.START, play = play))
        }
    }

    private suspend fun loadItem(): Item {
        if (route.itemType == "film") {
            val film = deps.catalog.film(route.itemId)
            return Item("film", film.id, film.media_files, film.progress).apply {
                this.film = film
                detailLoaded = true
            }
        }
        val payload = launches.payload(route.launchId)?.takeIf { it.id == route.itemId && it.media_files.isNotEmpty() }
        if (payload != null) {
            val item = Item("episode", payload.id, payload.media_files, payload.progress).also { it.fill(payload) }
            // §3.5: `GET /api/episodes/{id}` (for `next_episode_id`) runs in parallel and does not delay the start.
            viewModelScope.launch { loadEpisodeDetail(item) }
            return item
        }
        val episode = deps.catalog.episode(route.itemId)
        return Item("episode", episode.id, episode.media_files, episode.progress).also { it.fill(episode) }
    }

    private suspend fun loadEpisodeDetail(target: Item): Boolean = try {
        val detail = deps.catalog.episode(target.id, Origin.Background)
        target.fill(detail)
        render()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        deps.log("episode detail failed", e)
        false
    }

    private fun Item.fill(summary: EpisodeSummary) {
        showTitle = summary.show_title
        season = summary.season_number
        episode = summary.episode_number
        episodeTitle = summary.title
    }

    private fun Item.fill(detail: EpisodeDetail) {
        showTitle = detail.show_title
        season = detail.season_number
        episode = detail.episode_number
        episodeTitle = detail.title
        nextEpisodeId = detail.next_episode_id
        detailLoaded = true
        if (files.isEmpty()) files = detail.media_files
        if (progress == null) progress = detail.progress
    }

    /** The file and start position (§8 Resume, D10/D11); null when no file is readable. */
    private fun chooseStart(item: Item): Pair<MediaFile, com.hometv.tv.core.playback.Start>? {
        val files = item.files
        val chosen = route.mediaFileId?.let { id -> files.firstOrNull { it.id == id } }
        if (item.type == "film") {
            val choice = if (chosen != null) null else VersionSelector.defaultChoice(files, capsFF, item.progress)
            val target = chosen ?: choice?.file ?: return null
            val start = if (choice?.startAtZero == true) com.hometv.tv.core.playback.Start(0.0, false)
            else VersionSelector.startFor(target, files, item.progress, route.fromStart)
            return target to start
        }
        val target = chosen ?: VersionSelector.select(files, capsFF, item.progress, VersionGroup.Standalone) ?: return null
        return target to VersionSelector.startFor(target, files, item.progress, route.fromStart)
    }

    private fun onItemLoadError(e: ApiException) {
        val gone = e is ApiException.Http && e.status == 404 && e.code == "not_found"
        if (gone) deps.events.emit(AppEvent.ItemGone(if (route.itemType == "film") "film" else "episode", route.itemId))
        if (e is ApiException.ProfileGone) return // HomeTvRoot opens the Picker.
        val message: UiText = when (val error = ScreenError.from(e, deps.serverUrl())) {
            is ScreenError.Connection -> if (error.tls) UiText.Res(R.string.setup_error_tls) else UiText.Res(R.string.error_connection, listOf(error.host))
            ScreenError.ItemGone -> UiText.Res(R.string.item_gone)
            ScreenError.SignIn -> UiText.Res(R.string.error_sign_in)
            is ScreenError.Unexpected -> UiText.Res(R.string.error_unexpected, listOf(error.code))
            else -> UiText.Res(R.string.error_load_failed)
        }
        val actions = if (gone) listOf(PlayerAction.BACK) else listOf(PlayerAction.RETRY, PlayerAction.BACK)
        session = SessionState.Error
        errorSpec = null
        screenError = PlayerScreenUi.Error(message, null, actions)
        render()
    }

    // ---- Requests ---------------------------------------------------------------------------

    private suspend fun refreshCaps() {
        val inventory = deps.codecs.inventory()
        val route = deps.codecs.audioRoute()
        capsFF = CapsForFile { f ->
            val h = history[f.id]
            ClientCapsBuilder.build(inventory, route, f, h?.video.orEmpty(), h?.audio.orEmpty(), h?.containers.orEmpty())
        }
    }

    private fun historyOf(f: MediaFile) = history.getOrPut(f.id) { FileHistory() }

    /** Sends a `POST .../playback` (through `Loading`, §8): the `preparing` loop, row 33 orphans, attach. */
    private fun send(spec: RequestSpec) {
        loadJob?.cancel()
        pending = spec
        requestedFile = spec.file
        preparing = null
        screenError = null
        endScreen = null
        if (spec.kind == Kind.DIRECT_AUDIO_SWITCH) {
            switchBase = att
            switchAudioIndex = spec.audioIndex
            basePaused = session == SessionState.Paused
        } else {
            switchBase = null
            stopTicker()
        }
        session = SessionState.Loading
        render()
        loadJob = viewModelScope.launch {
            sessions.awaitOrphans()
            refreshCaps() // the audio route is read fresh for every request
            val caps = capsFF.capsFor(spec.file)
            val body = PlaybackRequest(caps, spec.audioIndex, spec.startSec, spec.previousSessionId)
            val switching = spec.kind == Kind.DIRECT_AUDIO_SWITCH
            // Row 40: the switch is a background caller (a renewal IOException is a toast); row 36: retries at the direct position.
            val origin = if (switching) Origin.Background else Origin.Foreground
            val retryStart: (() -> Double)? = if (switching) ({ controller.positionMs / 1000.0 }) else null
            deps.playback.requestPlayback(spec.file.id, body, origin, retryStart).collect { progress ->
                when (progress) {
                    is PlaybackProgress.Sent -> inFlight = progress.request
                    is PlaybackProgress.Preparing -> {
                        inFlight = null
                        preparing = PlaybackErrorMapper.preparingEstimate(progress.estimatedSeconds)
                        pending = spec.copy(previousSessionId = null)
                        render()
                    }
                    is PlaybackProgress.Success -> onResponse(spec, progress.response, caps)
                    is PlaybackProgress.Failure -> {
                        inFlight = null
                        onRequestFailure(spec, progress.error)
                    }
                    PlaybackProgress.PreparingTimeout -> {
                        inFlight = null
                        onPreparingTimeout(spec)
                    }
                }
            }
        }
    }

    /** Abandons the await of the current request: no further retries; a POST on the wire becomes the orphan (row 33). */
    private fun abandonRequest() {
        loadJob?.cancel()
        loadJob = null
        itemJob?.cancel()
        inFlight?.let(sessions::adopt)
        inFlight = null
        preparing = null
    }

    private suspend fun onResponse(spec: RequestSpec, response: PlaybackResponse, caps: ClientCaps) {
        val plan = PlaybackPlanner.plan(response, deps.serverUrl().orEmpty(), spec.file)
        if (plan is PlaybackPlan.Unsupported) {
            inFlight = null
            deps.log("unsupported ${plan.wireReason}: ${plan.detail}", null)
            onUnsupported(spec, plan)
            return
        }
        attach(spec, plan as PlaybackPlan.Play, caps)
    }

    private fun onUnsupported(spec: RequestSpec, plan: PlaybackPlan.Unsupported) {
        when (spec.kind) {
            Kind.DIRECT_AUDIO_SWITCH -> {
                spec.audioIndex?.let(unavailableAudio::add)
                showToast(UiText.Res(R.string.audio_track_unplayable))
                endSwitch()
            }
            Kind.HLS_AUDIO_SWITCH -> {
                // §9.5: toast, then recover the previous track (selectedAudioIndex unchanged), previous_session_id = null.
                spec.audioIndex?.let(unavailableAudio::add)
                showToast(UiText.Res(R.string.audio_track_unplayable))
                send(spec.copy(audioIndex = selectedAudioIndex, previousSessionId = null, kind = Kind.RESTORE_AUDIO))
            }
            Kind.RELEASED_AUDIO -> {
                spec.audioIndex?.let(unavailableAudio::add)
                showToast(UiText.Res(R.string.audio_track_unplayable))
                session = SessionState.Released
                render()
            }
            Kind.NEXT_EPISODE, Kind.NEXT_PART -> {
                val nextFile = spec.fallbacks.firstOrNull()
                if (nextFile != null) {
                    // The server stopped the old session at the first request whatever its outcome (Rev 4).
                    val start = spec.nextEpisode?.let { VersionSelector.startFor(nextFile, it.media_files, it.progress) }
                    noticePending = start?.differentVersionNotice ?: false
                    send(spec.copy(file = nextFile, startSec = start?.positionSeconds ?: 0.0, previousSessionId = null, fallbacks = spec.fallbacks.drop(1)))
                } else if (spec.kind == Kind.NEXT_EPISODE && spec.nextEpisode != null) {
                    showEndScreenUnplayable(spec.nextEpisode, plan.reason, spec.file)
                } else {
                    refused += spec.file.id
                    showError(PlaybackErrorMapper.forUnsupported(plan.reason, spec.file, hasOtherVersion(spec.file)), null)
                }
            }
            else -> {
                refused += spec.file.id
                showError(PlaybackErrorMapper.forUnsupported(plan.reason, spec.file, hasOtherVersion(spec.file)), null)
            }
        }
    }

    private fun onRequestFailure(spec: RequestSpec, e: ApiException) {
        deps.log("playback request failed", e)
        if (spec.kind == Kind.DIRECT_AUDIO_SWITCH) {
            when (val failure = PlaybackErrorMapper.forSwitchError(e)) {
                is SwitchFailure.Toast -> {
                    if (failure.message == MessageKey.AUDIO_TRACK_UNPLAYABLE) spec.audioIndex?.let(unavailableAudio::add)
                    showToast(messageText(failure.message))
                }
                SwitchFailure.AuthExpired -> Unit // the guard emitted AuthExpired: HomeTvRoot opens the Picker
            }
            endSwitch()
            return
        }
        when (val decision = PlaybackErrorMapper.forRequestError(e, hasOtherVersion(spec.file), spec.audioIndexRetried)) {
            PlaybackDecision.RetryWithoutAudioIndex -> send(spec.copy(audioIndex = null, audioIndexRetried = true, previousSessionId = null))
            PlaybackDecision.AuthExpired -> Unit
            is PlaybackDecision.ShowError -> {
                if (decision.refuseVersion) refused += spec.file.id
                if (decision.itemGone) deps.events.emit(AppEvent.ItemGone(item.type, item.id))
                showError(decision.error, spec.copy(previousSessionId = null), spec.startSec)
            }
            else -> showError(PlayerError(MessageKey.PLAYBACK_FAILED, actions = listOf(PlayerAction.RETRY, PlayerAction.BACK)), spec.copy(previousSessionId = null), spec.startSec)
        }
    }

    private fun onPreparingTimeout(spec: RequestSpec) {
        if (spec.kind == Kind.DIRECT_AUDIO_SWITCH) {
            showToast(messageText(MessageKey.AUDIO_PREPARING_TIMEOUT))
            endSwitch()
            return
        }
        showError(PlaybackErrorMapper.forPreparingTimeout(), spec.copy(previousSessionId = null), spec.startSec)
    }

    // ---- Attach (row 32) --------------------------------------------------------------------

    private suspend fun attach(spec: RequestSpec, plan: PlaybackPlan.Play, caps: ClientCaps) {
        val base = switchBase
        var play = spec.play
        var startMs = (spec.startSec * 1000).toLong()
        if (spec.kind == Kind.DIRECT_AUDIO_SWITCH && base != null) {
            val duration = effectiveDurationMs(base)
            val nearEnd = controller.state == PlayerState.ENDED || (duration != null && duration - controller.positionMs <= SWITCH_END_MARGIN_MS)
            if (nearEnd) {
                // Row 39: the switch is abandoned and the response goes to row 33.
                inFlight = null
                plan.sessionId?.let(::deleteSession)
                endSwitch()
                return
            }
        }
        // A new file starts on its default subtitle (TrackRules.defaultSubtitle); the same file keeps the selection.
        if (spec.file.id != file?.id) {
            subtitleDefaulted = false
            selectedSubtitleId = null
        }
        val justDefaulted = !subtitleDefaulted
        if (justDefaulted) {
            subtitleDefaulted = true
            selectedSubtitleId = plan.defaultSubtitleId
        }
        if (selectedSubtitleId != null && plan.subtitles.none { it.track.id == selectedSubtitleId }) selectedSubtitleId = null
        // The default text subtitle is fetched before prepare() with a 3 s budget (Playback Flow rule 3).
        val wanted = plan.subtitles.firstOrNull { it.track.id == selectedSubtitleId && it.track.isText }
        val wantedUrl = wanted?.url
        if (justDefaulted && wanted != null && wantedUrl != null && deps.subtitles.get(spec.file.id, wanted.track.id) == null) {
            val result = withTimeoutOrNull(DEFAULT_SUBTITLE_BUDGET_MS) { deps.playback.fetchSubtitle(wantedUrl) }
            if (result is SubtitleFetch.Ready) storeSubtitle(spec.file.id, wanted.track.id, result.bytes)
        }
        // From here on nothing suspends: the response is attached.
        inFlight = null
        if (spec.kind == Kind.DIRECT_AUDIO_SWITCH && base != null) {
            startMs = controller.positionMs
            play = !basePaused
            basePaused = false
            finishSession(base) // row 32: the switchBase's final PUT runs first
        }
        switchBase = null
        switchAudioIndex = null
        selectedAudioIndex = plan.audioStreamIndex
        plan.audioStreamIndex?.let(unavailableAudio::remove)
        file = spec.file
        val a = Attachment(nextKey++, spec.file, plan, caps, startMs)
        a.reporter = ProgressReporter(viewModelScope, send = { putAndRemember(it) }, snapshot = { snapshot(a) }, log = { deps.log("progress", it) })
        att = a
        pending = null
        preparing = null
        resumeOnStart = null
        releasePutPositionMs = null
        loadMedia(a, startMs)
        if (play) controller.play() else controller.pause()
        session = if (play) SessionState.Playing else SessionState.Paused
        if (play) {
            startTicker()
            scheduleOverlayHide()
        } else {
            overlay = OverlayMode.FULL
            focusPlayPause++
            startHeldTimers()
        }
        selectedSubtitleId?.let { id ->
            val track = plan.subtitles.firstOrNull { it.track.id == id }
            if (track != null && track.track.isText && id !in a.itemSubtitles) startSubtitleLoop(id)
        }
        render()
    }

    /** setMediaItem + overrides + prepare (the overrides are re-applied once the new `Tracks` arrive, rule 5). */
    private fun loadMedia(a: Attachment, startMs: Long, rebuildForSubtitle: Boolean = false) {
        val spec = mediaItemSpec(a)
        a.itemSubtitles.clear()
        a.itemSubtitles += spec.subtitles.map { it.trackId }
        controller.setMediaItem(spec, startMs)
        controller.selectAudio(selectedAudioIndex)
        controller.selectSubtitle(selectedSubtitleId?.takeIf { it in a.itemSubtitles || isImage(a, it) })
        trackCheckArmed = true
        subtitleRebuild = rebuildForSubtitle
        controller.prepare()
    }

    private fun mediaItemSpec(a: Attachment): MediaItemSpec {
        val cached = a.plan.subtitles.filter { it.track.isText }.mapNotNull { planned ->
            deps.subtitles.get(a.file.id, planned.track.id)?.let { uri ->
                SideloadedSubtitle(planned.track.id, uri, planned.track.language, planned.track.title, planned.track.forced)
            }
        }
        return MediaItemSpec(
            url = a.plan.url,
            mimeType = a.plan.mimeType,
            subtitles = cached,
            audioTrackIndices = a.file.audio_tracks.map { it.index },
            imageSubtitleIds = a.plan.subtitles.filter { it.track.isImage && !it.track.isExternal }.map { it.track.id },
        )
    }

    private fun isImage(a: Attachment, id: String) = a.plan.subtitles.any { it.track.id == id && it.track.isImage }

    // ---- Session ownership ------------------------------------------------------------------

    /** Final PUT (once) and DELETE of a held HLS session (once), both in `appScope`. */
    private fun finishSession(a: Attachment, delete: Boolean = true) {
        if (!a.putDone) {
            a.putDone = true
            a.reporter.finish(deps.appScope)
        } else {
            a.reporter.stop()
        }
        val sessionId = a.sessionId
        if (delete && a.holdsSession && sessionId != null) {
            a.released = true
            deleteSession(sessionId)
        }
    }

    /** Row 14: the session goes as `previous_session_id`: final PUT, no DELETE, never touched again. */
    private fun handOver(a: Attachment): String? {
        val id = a.sessionId?.takeIf { a.holdsSession && !a.sessionLost }
        finishSession(a, delete = id == null)
        a.released = true
        return id
    }

    private fun deleteSession(sessionId: String) = sessions.delete(sessionId)

    private fun snapshot(a: Attachment): ProgressSnapshot? {
        if (!::item.isInitialized) return null
        val position = if (session == SessionState.Released || session == SessionState.Background) savedPositionMs else controller.positionMs
        return ProgressSnapshot(item.type, item.id, a.file.id, position / 1000.0, effectiveDurationMs(a)?.let { it / 1000.0 })
    }

    private fun effectiveDurationMs(a: Attachment?): Long? {
        a ?: return null
        a.plan.durationSeconds?.let { return (it * 1000).toLong() }
        return controller.durationMs
    }

    // ---- Keep-alive and the 30 min pause timer (D9) -----------------------------------------

    private fun startHeldTimers() {
        val a = att ?: return
        if (a.mode != PlaybackMode.HLS || !a.holdsSession) return
        startKeepAlive()
        pauseTimerJob?.cancel()
        pauseTimerJob = viewModelScope.launch {
            delay(PAUSE_RELEASE_MS)
            releasePaused()
        }
    }

    private fun startKeepAlive() {
        val a = att ?: return
        if (a.mode != PlaybackMode.HLS || !a.holdsSession || keepAliveJob?.isActive == true) return
        val sessionId = a.sessionId ?: return
        keepAliveJob = viewModelScope.launch {
            while (true) {
                delay(KEEP_ALIVE_MS)
                if (!a.holdsSession) return@launch
                if (deps.playback.keepAliveHlsSession(sessionId) == KeepAliveResult.SESSION_LOST) {
                    a.sessionLost = true
                    return@launch
                }
            }
        }
    }

    private fun stopHeldTimers() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        pauseTimerJob?.cancel()
        pauseTimerJob = null
    }

    /** Row 3: 30 min of HLS pause → PUT + DELETE, `player.stop()`, overlay stays, paused. */
    private fun releasePaused() {
        val a = att ?: return
        if (session != SessionState.Paused) return
        stopHeldTimers()
        savedPositionMs = controller.positionMs
        finishSession(a)
        releasePutPositionMs = savedPositionMs
        controller.stop()
        session = SessionState.Released
        overlay = OverlayMode.FULL
        render()
    }

    // ---- Controller callbacks ---------------------------------------------------------------

    override fun onTracksChanged(check: TrackCheckInput) {
        if (subtitleRebuild) {
            subtitleRebuild = false
            trackCheckArmed = false
            return
        }
        if (!trackCheckArmed) return
        trackCheckArmed = false
        val f = file ?: return
        val failure = TrackCheck.check(check.copy(fileHasVideo = f.video != null, expectedAudio = selectedAudioIndex != null))
        PlaybackErrorMapper.forTrackFailure(failure, failureContext())?.let(::handleDecision)
    }

    override fun onStateChanged(state: PlayerState) {
        val a = att
        when (state) {
            PlayerState.READY -> if (a != null && !a.reachedReady) {
                a.reachedReady = true
                if (noticePending) {
                    noticePending = false
                    notice = UiText.Res(R.string.player_different_version)
                    noticeJob?.cancel()
                    noticeJob = viewModelScope.launch {
                        delay(NOTICE_MS)
                        notice = null
                        render()
                    }
                }
            }
            PlayerState.ENDED -> onEnded()
            else -> Unit
        }
        render()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val a = switchBase ?: att
        if (a != null && !a.putDone) a.reporter.onPlayingChanged(isPlaying)
        render()
    }

    override fun onDurationChanged(durationMs: Long?) = render()

    // The media session's commands (headset, Bluetooth AVRCP, media controllers; prd3 §8) are the
    // remote's Play / Pause keys and the overlay's 10 s seek buttons.
    override fun onRemotePlay() {
        onPlay()
        onShowOverlay()
    }

    override fun onRemotePause() = onPause()

    override fun onRemoteSeek(forward: Boolean) = onSeekButton(forward)

    override fun onFailure(failure: PlayerFailure) {
        val attached = session == SessionState.Playing || session == SessionState.Paused || switchBase != null
        if (!attached) return
        if (switchBase != null) abandonSwitch() // row 41
        deps.log("player failure $failure", null)
        errorPositionSec = recoveryPositionMs() / 1000.0
        val decision = PlaybackErrorMapper.forPlayerFailure(failure, failureContext())
        if (decision is PlaybackDecision.SubtitleUnavailable) {
            onSubtitleLoadFailure()
            return
        }
        handleDecision(decision)
    }

    private fun failureContext(): FailureContext {
        val a = att
        val f = a?.file ?: file
        val h = f?.let(::historyOf)
        return FailureContext(
            mode = a?.mode ?: PlaybackMode.DIRECT,
            nowMs = deps.nowMs(),
            videoCodec = f?.video?.codec,
            audioCodec = f?.audio_tracks?.firstOrNull { it.index == selectedAudioIndex }?.codec,
            audioAction = a?.plan?.audioAction,
            container = f?.container,
            audioRetried = h?.audioRetried == true,
            videoRetried = h?.videoRetried == true,
            containerRetried = h?.containerRetried == true,
            lastRenewalAtMs = lastRenewalAtMs,
            lastRecoveryAtMs = lastRecoveryAtMs,
            hasOtherVersion = f != null && hasOtherVersion(f),
        )
    }

    private fun recoveryPositionMs(): Long {
        val a = att ?: return savedPositionMs
        return if (a.reachedReady) controller.positionMs else a.startMs
    }

    private fun handleDecision(decision: PlaybackDecision) {
        val a = att ?: return
        val f = a.file
        val wasPlaying = session == SessionState.Playing
        when (decision) {
            PlaybackDecision.RenewAndRecover -> {
                lastRenewalAtMs = deps.nowMs()
                val position = recoveryPositionMs()
                viewModelScope.launch {
                    when (val outcome = deps.guard.renew()) {
                        RenewOutcome.Renewed -> recover(a, position, wasPlaying)
                        RenewOutcome.ProfileGone -> deps.events.emit(AppEvent.AuthExpired)
                        is RenewOutcome.Unreachable -> failAttached(a, connectionError(outcome.cause))
                    }
                }
            }
            PlaybackDecision.Recover -> {
                lastRecoveryAtMs = deps.nowMs()
                recover(a, recoveryPositionMs(), wasPlaying)
            }
            is PlaybackDecision.RetryExcludingVideo -> retryExcluding(a, wasPlaying) { historyOf(f).apply { video += decision.codec; videoRetried = true } }
            is PlaybackDecision.RetryExcludingAudio -> retryExcluding(a, wasPlaying) { historyOf(f).apply { audio += decision.codec; audioRetried = true } }
            is PlaybackDecision.RetryExcludingContainer -> retryExcluding(a, wasPlaying) { historyOf(f).apply { containers += decision.container; containerRetried = true } }
            is PlaybackDecision.ShowError -> {
                if (decision.refuseVersion) refused += f.id
                if (decision.itemGone) deps.events.emit(AppEvent.ItemGone(item.type, item.id))
                failAttached(a, decision.error)
            }
            PlaybackDecision.SubtitleUnavailable -> onSubtitleLoadFailure()
            PlaybackDecision.AuthExpired -> deps.events.emit(AppEvent.AuthExpired)
            else -> failAttached(a, PlayerError(MessageKey.PLAYBACK_FAILED, actions = listOf(PlayerAction.RETRY_FROM_HERE, PlayerAction.BACK)))
        }
    }

    private fun connectionError(cause: ApiException) = PlayerError(
        if ((cause as? ApiException.Unreachable)?.isTls == true) MessageKey.TLS_ERROR else MessageKey.CONNECTION_ERROR,
        actions = listOf(PlayerAction.RETRY_FROM_HERE, PlayerAction.BACK),
    )

    /** A full-screen error replaces the attached media: its session is finished at once (an error screen holds no slot). */
    private fun failAttached(a: Attachment, error: PlayerError) {
        errorPositionSec = recoveryPositionMs() / 1000.0
        stopHeldTimers()
        stopTicker()
        finishSession(a)
        controller.stop()
        showError(error, null, errorPositionSec)
    }

    /** Recovery at [positionMs] with the same file, `selectedAudioIndex` and subtitle selection (§10). */
    private fun recover(a: Attachment, positionMs: Long, play: Boolean) {
        if (a.mode == PlaybackMode.DIRECT && !a.putDone) {
            controller.prepare()
            controller.seekTo(positionMs)
            if (play) controller.play()
            return
        }
        stopHeldTimers()
        finishSession(a)
        send(RequestSpec(a.file, positionMs / 1000.0, selectedAudioIndex, null, Kind.RECOVERY, play, keepFrame = true))
    }

    private fun retryExcluding(a: Attachment, play: Boolean, record: () -> Unit) {
        record()
        val position = recoveryPositionMs()
        stopHeldTimers()
        finishSession(a)
        send(RequestSpec(a.file, position / 1000.0, selectedAudioIndex, null, Kind.CODEC_RETRY, play, keepFrame = true))
    }

    // ---- End of an item (rows 15–19) --------------------------------------------------------

    private fun onEnded() {
        if (switchBase != null) abandonSwitch() // rows 37/39
        val a = att ?: return
        if (session != SessionState.Playing && session != SessionState.Paused) return
        stopTicker()
        stopHeldTimers()
        overlay = OverlayMode.HIDDEN
        dialog = null
        // PUT(end): the final PUT of this attachment; a later finishSession() only DELETEs.
        if (!a.putDone) {
            a.putDone = true
            a.reporter.finish(deps.appScope, atEnd = true)
        }
        if (item.type == "film") {
            val nextPart = if (VersionSelector.isLastPart(a.file, item.files)) null else VersionSelector.nextPart(item.files, capsFF, a.file)
            if (nextPart == null) {
                finishSession(a)
                exit()
            } else {
                startCountdown(NextTarget.Part(nextPart), PART_PROMPT_SECONDS)
            }
            return
        }
        when (val n = next) {
            is NextState.Found -> if (n.plan.isEmpty()) {
                finishSession(a)
                showEndScreenUnplayable(n.episode, null, null)
            } else {
                startCountdown(NextTarget.Episode(n.episode, n.plan), EPISODE_COUNTDOWN_SECONDS)
            }
            NextState.None -> {
                finishSession(a)
                exit()
            }
            else -> {
                session = SessionState.EndedResolving
                startKeepAlive()
                render()
                if (n != NextState.Running) resolveNext()
            }
        }
    }

    /** Prefetch / resolution with `NextEpisodeResolver` (D12); a failure is retried once (at `STATE_ENDED`). */
    private fun resolveNext() {
        if (item.type != "episode" || next == NextState.Running) return
        val target = item
        val current = object : NextEpisodeResolution.Current {
            override suspend fun ensureDetail(): Boolean = target.detailLoaded || loadEpisodeDetail(target)

            override fun nextEpisodeId(): Long? = target.nextEpisodeId

            override suspend fun plan(episode: EpisodeDetail): List<MediaFile> {
                refreshCaps()
                return VersionSelector.candidates(episode.media_files, capsFF, VersionGroup.Standalone, episode.progress)
            }
        }
        nextResolution.start(current, file, keepRetrying = { session == SessionState.EndedResolving }, onResolved = ::onNextResolved)
    }

    private fun onNextResolved() {
        val n = next
        if (resolvingForButton) {
            resolvingForButton = false
            onNextButtonResolved(n)
            return
        }
        if (session != SessionState.EndedResolving) {
            render()
            return
        }
        val a = att
        when (n) {
            is NextState.Found -> if (n.plan.isEmpty()) {
                stopHeldTimers()
                a?.let(::finishSession)
                showEndScreenUnplayable(n.episode, null, null)
            } else if (idleAfterResolve) {
                idleAfterResolve = false
                countdownTarget = NextTarget.Episode(n.episode, n.plan)
                countdownSeconds = null
                session = SessionState.EndedIdle
                render()
            } else {
                startCountdown(NextTarget.Episode(n.episode, n.plan), EPISODE_COUNTDOWN_SECONDS)
            }
            NextState.None -> {
                // Row 18 (and row 23 after ON_STOP): no next episode left → exit, as row 15.
                stopHeldTimers()
                a?.let(::finishSession)
                exit()
            }
            else -> {
                stopHeldTimers()
                a?.let(::finishSession)
                showEndScreen(UiText.Res(R.string.next_load_failed), null, canRetry = true)
            }
        }
    }

    private fun startCountdown(target: NextTarget, seconds: Int) {
        countdownTarget = target
        countdownSeconds = seconds
        session = SessionState.Countdown
        startKeepAlive()
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while ((countdownSeconds ?: 0) > 0) {
                delay(1_000)
                countdownSeconds = (countdownSeconds ?: 1) - 1
                render()
            }
            startNextTarget(handOverHeld = true)
        }
        render()
    }

    /** Rows 20 and 25: start the next episode/part; a held session goes as `previous_session_id` (row 14). */
    private fun startNextTarget(handOverHeld: Boolean) {
        val target = countdownTarget ?: return
        countdownJob?.cancel()
        countdownJob = null
        countdownSeconds = null
        stopHeldTimers()
        val a = att
        val previous = if (handOverHeld && a != null) handOver(a) else a?.let { finishSession(it); null }
        startTarget(target, previous)
    }

    private fun startTarget(target: NextTarget, previous: String?) {
        when (target) {
            is NextTarget.Episode -> {
                val episode = target.episode
                val nextItem = Item("episode", episode.id, episode.media_files, episode.progress).also { it.fill(episode) }
                beginItem(nextItem)
                val first = target.plan.first()
                val start = VersionSelector.startFor(first, episode.media_files, episode.progress)
                noticePending = start.differentVersionNotice
                send(RequestSpec(first, start.positionSeconds, null, previous, Kind.NEXT_EPISODE, play = true, fallbacks = target.plan.drop(1), nextEpisode = episode))
            }
            is NextTarget.Part -> {
                resetForFile()
                val group = VersionSelector.groupOf(target.file)
                val fallbacks = VersionSelector.candidates(item.files, capsFF, group).filter { it.id != target.file.id }
                send(RequestSpec(target.file, 0.0, null, previous, Kind.NEXT_PART, play = true, fallbacks = fallbacks))
            }
        }
    }

    private fun beginItem(nextItem: Item) {
        item = nextItem
        nextResolution.reset()
        refused.clear()
        history.clear()
        lastRecoveryAtMs = null
        lastRenewalAtMs = null
        resetForFile()
        countdownTarget = null
    }

    private fun resetForFile() {
        selectedAudioIndex = null
        selectedSubtitleId = null
        subtitleDefaulted = false
        unavailableSubtitles.clear()
        unavailableAudio.clear()
        noticePending = false
        notice = null
        subtitleJobs.values.forEach { it.cancel() }
        subtitleJobs.clear()
        pendingSubtitle = null
    }

    private fun showEndScreenUnplayable(episode: EpisodeDetail, reason: UnsupportedReason?, refusedFile: MediaFile?) {
        val why = reason ?: unplayableReason(episode.media_files)
        val sample = refusedFile ?: episode.media_files.firstOrNull { it.probe_error == null && it.video != null }
        val reasonText = PlaybackErrorMapper.forUnsupported(why, sample, false).let { e -> e.reason?.let { messageText(it, e.args) } }
        val code = Format.episodeCode(episode.season_number, episode.episode_number)
        val title = UiText.Joined(listOf(UiText.Value(code), UiText.Value(Format.episodeTitle(episode.episode_number, episode.title))), R.string.separator_space)
        val detail = UiText.Joined(listOfNotNull(title, reasonText), R.string.separator_newline)
        showEndScreen(UiText.Res(R.string.next_unplayable), detail, canRetry = false)
    }

    private fun unplayableReason(files: List<MediaFile>): UnsupportedReason {
        val readable = files.filter { it.probe_error == null }
        return when {
            readable.isEmpty() -> UnsupportedReason.PROBE_FAILED
            readable.all { it.video == null } -> UnsupportedReason.NO_VIDEO_STREAM
            else -> UnsupportedReason.VIDEO_CODEC_UNSUPPORTED
        }
    }

    private fun showEndScreen(message: UiText, detail: UiText?, canRetry: Boolean) {
        stopTicker()
        countdownJob?.cancel()
        session = SessionState.EndScreen
        endScreen = PlayerScreenUi.EndScreen(message, detail, canRetry)
        render()
    }

    // ---- Errors -----------------------------------------------------------------------------

    private fun showError(error: PlayerError, retry: RequestSpec?, positionSec: Double = errorPositionSec) {
        stopTicker()
        stopHeldTimers()
        errorSpec = retry
        errorPositionSec = positionSec
        dialog = null
        overlay = OverlayMode.HIDDEN
        session = SessionState.Error
        screenError = PlayerScreenUi.Error(messageText(error.message, error.args), error.reason?.let { messageText(it, error.args) }, error.actions)
        render()
    }

    private fun messageText(key: MessageKey, args: List<String> = emptyList()): UiText = when (key) {
        MessageKey.REASON_VIDEO_CODEC_UNSUPPORTED -> if (args.isEmpty()) UiText.Res(R.string.reason_unknown) else UiText.Res(UiFormat.messageRes(key), args)
        MessageKey.CONNECTION_ERROR -> UiText.Res(R.string.error_connection, listOf(deps.serverUrl()?.let(ServerUrl::host) ?: deps.serverUrl().orEmpty()))
        MessageKey.PLAYBACK_UNSUPPORTED -> UiText.Res(UiFormat.messageRes(key))
        else -> UiText.Res(UiFormat.messageRes(key), args)
    }

    fun onErrorAction(action: PlayerAction) {
        if (session != SessionState.Error) return
        when (action) {
            PlayerAction.RETRY -> {
                val spec = errorSpec
                if (spec == null || !::item.isInitialized) startItem() else send(spec)
            }
            PlayerAction.RETRY_FROM_HERE -> {
                val f = file ?: return startItem()
                send(RequestSpec(f, errorPositionSec, selectedAudioIndex, null, Kind.RECOVERY, play = true))
            }
            PlayerAction.TRY_OTHER_VERSION -> openOtherVersions()
            PlayerAction.BACK -> leave()
        }
    }

    private fun hasOtherVersion(f: MediaFile): Boolean = otherVersionsFor(f).isNotEmpty()

    private fun otherVersionsFor(f: MediaFile): List<MediaFile> {
        if (!::item.isInitialized) return emptyList()
        return VersionSelector.candidates(item.files, capsFF, VersionSelector.groupOf(f), progressNow(f)).filter { it.id != f.id && it.id !in refused }
    }

    private fun progressNow(f: MediaFile) = Progress(position_seconds = errorPositionSec, duration_seconds = f.duration_seconds, media_file_id = f.id)

    private fun openOtherVersions() {
        val f = requestedFile ?: file ?: return
        otherVersions = otherVersionsFor(f)
        if (otherVersions.isEmpty()) return
        dialog = PlayerDialog.Versions(otherVersions.map { it.label.ifBlank { it.file_name } })
        render()
    }

    fun onVersionChosen(index: Int) {
        val chosen = otherVersions.getOrNull(index) ?: return
        val current = requestedFile ?: file ?: return
        dialog = null
        val start = VersionSelector.startFor(chosen, item.files, progressNow(current))
        noticePending = start.differentVersionNotice
        send(RequestSpec(chosen, start.positionSeconds, null, null, Kind.OTHER_VERSION, play = true))
    }

    // ---- Audio switch (§9.5, rows 34–41) ----------------------------------------------------

    fun onSelectAudio(index: Int) {
        dialog = null
        val a = att
        val track = (a?.file ?: file)?.audio_tracks?.firstOrNull { it.index == index }
        if (a == null || track == null) return render()
        if (session == SessionState.Released) {
            // Row 8: always server-routed, stays paused.
            send(RequestSpec(a.file, savedPositionMs / 1000.0, index, null, Kind.RELEASED_AUDIO, play = false, keepFrame = true))
            return
        }
        if (switchBase != null) {
            if (index == switchAudioIndex) return render()
            abandonSwitch() // row 38: a different track starts a new switch from the direct media
        }
        if (session != SessionState.Playing && session != SessionState.Paused) return render()
        if (index == selectedAudioIndex) return render()
        when (TrackRules.audioSwitchRoute(track, a.mode, a.caps)) {
            com.hometv.tv.core.playback.AudioSwitchRoute.LOCAL -> {
                selectedAudioIndex = index
                controller.selectAudio(index)
                render()
            }
            com.hometv.tv.core.playback.AudioSwitchRoute.SERVER -> if (a.mode == PlaybackMode.DIRECT) {
                send(RequestSpec(a.file, controller.positionMs / 1000.0, index, null, Kind.DIRECT_AUDIO_SWITCH, play = true))
            } else {
                val play = session == SessionState.Playing
                val position = controller.positionMs
                stopHeldTimers()
                stopTicker()
                val previous = handOver(a)
                controller.stop()
                send(RequestSpec(a.file, position / 1000.0, index, previous, Kind.HLS_AUDIO_SWITCH, play, keepFrame = true))
            }
        }
    }

    /** Row 34 / 37 / 38 / 41: drop the switch; the direct media keeps playing on the old track. */
    private fun abandonSwitch() {
        if (switchBase == null) return
        abandonRequest()
        endSwitch()
    }

    private fun endSwitch() {
        val base = switchBase
        loadJob?.cancel()
        loadJob = null
        switchBase = null
        switchAudioIndex = null
        pending = null
        preparing = null
        if (base != null) att = base
        session = if (basePaused) SessionState.Paused else SessionState.Playing
        basePaused = false
        if (session == SessionState.Playing) startTicker()
        render()
    }

    /** The viewer's play/pause intent for the direct media while a switch runs (row 36). */
    private var basePaused = false

    // ---- Subtitles (§9.6–9.7, Playback Flow rule 3) -----------------------------------------

    fun onSelectSubtitle(trackId: String?) {
        dialog = null
        val a = att ?: return render()
        if (trackId == null) {
            selectedSubtitleId = null
            pendingSubtitle = null
            controller.selectSubtitle(null)
            return render()
        }
        val planned = a.plan.subtitles.firstOrNull { it.track.id == trackId } ?: return render()
        selectedSubtitleId = trackId
        if (session == SessionState.Released) {
            // Row 9: stored and fetched to the cache; applied by the next recovery.
            if (planned.track.isText && deps.subtitles.get(a.file.id, trackId) == null) startSubtitleLoop(trackId, attachWhenReady = false)
            return render()
        }
        when {
            planned.track.isImage -> controller.selectSubtitle(trackId)
            trackId in a.itemSubtitles -> controller.selectSubtitle(trackId)
            deps.subtitles.get(a.file.id, trackId) != null -> rebuildForSubtitle(a, trackId)
            else -> startSubtitleLoop(trackId)
        }
        render()
    }

    private fun startSubtitleLoop(trackId: String, attachWhenReady: Boolean = true) {
        if (subtitleJobs[trackId]?.isActive == true) return
        val a = att ?: return
        val url = a.plan.subtitles.firstOrNull { it.track.id == trackId }?.url ?: return
        val fileId = a.file.id
        subtitleJobs[trackId] = viewModelScope.launch {
            deps.playback.subtitleLoop(url) { selectedSubtitleId == trackId && !cleared }.collect { result ->
                when (result) {
                    is SubtitleFetch.Pending -> {
                        pendingSubtitle = trackId
                        render()
                    }
                    is SubtitleFetch.Ready -> {
                        if (pendingSubtitle == trackId) pendingSubtitle = null
                        storeSubtitle(fileId, trackId, result.bytes)
                        val current = att
                        val attached = session == SessionState.Playing || session == SessionState.Paused
                        if (attachWhenReady && attached && current != null && current.file.id == fileId && selectedSubtitleId == trackId) {
                            rebuildForSubtitle(current, trackId)
                        }
                        render()
                    }
                    is SubtitleFetch.Unavailable -> subtitleUnavailable(fileId, trackId)
                    is SubtitleFetch.Unreachable -> Unit
                }
            }
        }
    }

    private suspend fun storeSubtitle(fileId: Long, trackId: String, bytes: ByteArray) {
        try {
            withContext(deps.io) { deps.subtitles.put(fileId, trackId, bytes) }
        } catch (e: java.io.IOException) {
            deps.log("subtitle cache write failed", e)
        }
    }

    /** "Attaching later": one media-item rebuild at the current position, same session; overrides re-applied. */
    private fun rebuildForSubtitle(a: Attachment, trackId: String) {
        selectedSubtitleId = trackId
        loadMedia(a, controller.positionMs, rebuildForSubtitle = true)
    }

    private fun subtitleUnavailable(fileId: Long, trackId: String) {
        unavailableSubtitles += trackId
        if (pendingSubtitle == trackId) pendingSubtitle = null
        deps.subtitles.forget(fileId, trackId)
        viewModelScope.launch(deps.io) { deps.subtitles.remove(fileId, trackId) } // the file delete runs off the main thread
        showToast(UiText.Res(R.string.player_subtitles_unavailable))
        if (selectedSubtitleId == trackId) {
            selectedSubtitleId = null
            controller.selectSubtitle(null)
        }
        render()
    }

    /** §10 side-loaded subtitle failure: drop it, rebuild without it at the current position; no playback request. */
    private fun onSubtitleLoadFailure() {
        val a = att ?: return
        val trackId = selectedSubtitleId ?: a.itemSubtitles.firstOrNull() ?: return
        val position = controller.positionMs
        subtitleUnavailable(a.file.id, trackId)
        loadMedia(a, position)
        if (session == SessionState.Playing) controller.play()
        render()
    }

    // ---- Next episode / next part buttons ---------------------------------------------------

    fun onNext() {
        if (switchBase != null) abandonSwitch() // row 38
        if (!::item.isInitialized) return
        val a = att ?: return
        val allowed = session == SessionState.Playing || session == SessionState.Paused || session == SessionState.Released
        if (!allowed) return
        overlay = OverlayMode.HIDDEN
        if (item.type == "film") {
            val nextPart = VersionSelector.nextPart(item.files, capsFF, a.file) ?: return
            goNext(NextTarget.Part(nextPart))
            return
        }
        when (val n = next) {
            is NextState.Found -> onNextButtonResolved(n)
            NextState.None -> onNextButtonResolved(n)
            else -> {
                resolvingForButton = true
                render()
                if (n != NextState.Running) {
                    nextResolution.clearFailures()
                    resolveNext()
                }
            }
        }
    }

    private fun onNextButtonResolved(n: NextState) {
        when (n) {
            is NextState.Found -> if (n.plan.isEmpty()) {
                stopHeldTimers()
                att?.let(::finishSession)
                controller.stop()
                showEndScreenUnplayable(n.episode, null, null)
            } else {
                goNext(NextTarget.Episode(n.episode, n.plan))
            }
            NextState.None -> {
                showToast(UiText.Res(R.string.player_last_episode))
                render()
            }
            else -> {
                nextResolution.forget()
                showToast(UiText.Res(R.string.next_load_failed))
            }
        }
    }

    /** Rows 10 and 14: the current progress is reported, a held session is handed over (none when released). */
    private fun goNext(target: NextTarget) {
        val a = att ?: return
        stopHeldTimers()
        stopTicker()
        countdownTarget = target
        val previous = if (session == SessionState.Released) {
            releasedFinalPut()
            null
        } else {
            handOver(a)
        }
        countdownTarget = null
        startTarget(target, previous)
    }

    fun onPlayNow() {
        when (session) {
            SessionState.Countdown -> startNextTarget(handOverHeld = true)
            SessionState.EndedIdle -> startNextTarget(handOverHeld = false)
            else -> Unit
        }
    }

    fun onEndScreenRetry() {
        if (session != SessionState.EndScreen || endScreen?.canRetry != true) return
        // Row 28: resolve again; a countdown then starts with no session held.
        endScreen = null
        nextResolution.prepareLastAttempt()
        session = SessionState.EndedResolving
        render()
        resolveNext()
    }

    // ---- Playback controls ------------------------------------------------------------------

    fun onPlayPause() {
        touch()
        when (session) {
            SessionState.Playing -> pause()
            SessionState.Paused, SessionState.Released -> play()
            SessionState.Loading -> if (switchBase != null) {
                if (controller.isPlaying) pause() else play()
            }
            else -> Unit
        }
    }

    fun onPlay() {
        touch()
        if (session == SessionState.Paused || session == SessionState.Released || (switchBase != null && !controller.isPlaying)) play()
    }

    fun onPause() {
        touch()
        if (session == SessionState.Playing || (switchBase != null && controller.isPlaying)) pause()
    }

    /** Row 1 (and row 36 for the direct media during a switch). */
    private fun pause() {
        val a = switchBase ?: att ?: return
        controller.pause()
        if (!a.putDone) a.reporter.onPaused()
        overlay = OverlayMode.FULL
        focusPlayPause++
        overlayJob?.cancel()
        if (switchBase != null) {
            basePaused = true
        } else {
            session = SessionState.Paused
            stopTicker()
            startHeldTimers()
        }
        render()
    }

    /** Rows 2 and 6. */
    private fun play() {
        val a = att ?: return
        if (session == SessionState.Released) {
            send(RequestSpec(a.file, savedPositionMs / 1000.0, selectedAudioIndex, null, Kind.RECOVERY, play = true, keepFrame = true))
            return
        }
        if (switchBase != null) {
            basePaused = false
            controller.play()
            render()
            return
        }
        stopHeldTimers()
        if (a.mode == PlaybackMode.HLS && a.sessionLost) {
            // A keep-alive 404: resuming goes straight to the recovery path (§8 HLS keep-alive).
            recover(a, controller.positionMs, play = true)
            return
        }
        controller.play()
        session = SessionState.Playing
        startTicker()
        scheduleOverlayHide()
        render()
    }

    fun onShowOverlay() {
        if (!videoVisible()) return
        overlay = OverlayMode.FULL
        focusPlayPause++
        touch()
        render()
    }

    /** Any input inside the overlay restarts the 5 s auto-hide. */
    fun onOverlayInput() = touch()

    private fun touch() {
        if (overlay == OverlayMode.FULL) scheduleOverlayHide()
    }

    private fun scheduleOverlayHide() {
        overlayJob?.cancel()
        if (overlay == OverlayMode.HIDDEN) return
        overlayJob = viewModelScope.launch {
            delay(if (overlay == OverlayMode.PROGRESS) PROGRESS_HIDE_MS else OVERLAY_HIDE_MS)
            val playing = session == SessionState.Playing || (switchBase != null && controller.isPlaying)
            if (playing && dialog == null) {
                overlay = OverlayMode.HIDDEN
                render()
            }
        }
    }

    private fun videoVisible() = session == SessionState.Playing || session == SessionState.Paused || session == SessionState.Released || switchBase != null

    /** Left/Right key-down (D-pad while hidden, the focused progress bar, or the rewind/forward media keys). */
    fun onSeekKey(forward: Boolean, repeatCount: Int) {
        if (!videoVisible()) return
        val a = switchBase ?: att ?: return
        val base = if (session == SessionState.Released) savedPositionMs else controller.positionMs
        seek.onKeyDown(forward, repeatCount, deps.nowMs(), base, effectiveDurationMs(a))
        if (overlay == OverlayMode.HIDDEN) overlay = OverlayMode.PROGRESS
        overlayJob?.cancel()
        seekJob?.cancel()
        seekJob = viewModelScope.launch {
            delay(SeekAccumulator.COMMIT_DELAY_MS)
            seek.poll(deps.nowMs())?.let(::commitSeek)
        }
        render()
    }

    fun onSeekKeyUp() {
        seek.onKeyUp()?.let {
            seekJob?.cancel()
            commitSeek(it)
        }
    }

    /** A 10 s step from the overlay's ⏪10 / 10⏩ buttons (one press = one committed seek). */
    fun onSeekButton(forward: Boolean) {
        touch()
        onSeekKey(forward, 0)
    }

    private fun commitSeek(targetMs: Long) {
        if (session == SessionState.Released) {
            // Row 7: the saved position moves; the debounced PUT carries it, no playback request.
            savedPositionMs = targetMs
            releasedPutJob?.cancel()
            releasedPutJob = viewModelScope.launch {
                delay(ProgressReporter.SEEK_DEBOUNCE_MS)
                releasedFinalPut(deps.appScope)
            }
        } else {
            val a = switchBase ?: att ?: return
            controller.seekTo(targetMs)
            if (!a.putDone) a.reporter.onSeekCommitted()
        }
        scheduleOverlayHide()
        render()
    }

    /** The PUT of the saved position while released (rows 7 and 11), only when it changed since the last one. */
    private fun releasedFinalPut(scope: CoroutineScope = deps.appScope) {
        val a = att ?: return
        if (releasePutPositionMs == savedPositionMs) return
        releasePutPositionMs = savedPositionMs
        val duration = effectiveDurationMs(a)?.let { it / 1000.0 } ?: return
        val report = ProgressRequest(item.type, item.id, a.file.id, (savedPositionMs / 1000.0).coerceIn(0.0, duration), duration)
        scope.launch {
            try {
                withTimeoutOrNull(FINAL_TIMEOUT_MS) { putAndRemember(report) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.log("progress", e)
            }
        }
    }

    // ---- Dialogs ----------------------------------------------------------------------------

    fun onOpenTracks() {
        val a = att ?: return
        if (!videoVisible()) return
        overlayJob?.cancel()
        val converted = a.plan.audioConverted
        val audio = a.file.audio_tracks.map { track: AudioTrack ->
            val current = track.index == selectedAudioIndex
            TrackOption(
                key = track.index.toString(),
                label = UiText.Value(TrackRules.audioLabel(track, converted = converted && track.index == selectedAudioIndex, unavailable = track.index in unavailableAudio)),
                selected = current,
            )
        }
        val subtitles = listOf(TrackOption(OFF_KEY, UiText.Res(R.string.subtitles_off), selectedSubtitleId == null)) +
            a.plan.subtitles.map { planned ->
                val id = planned.track.id
                TrackOption(id, UiText.Value(TrackRules.subtitleLabel(planned.track, unavailable = id in unavailableSubtitles)), id == selectedSubtitleId, pending = id == pendingSubtitle)
            }
        dialog = PlayerDialog.Tracks(TrackDialogUi(audio, subtitles))
        render()
    }

    fun onDialogDismiss() {
        dialog = null
        scheduleOverlayHide()
        render()
    }

    // ---- Back, lifecycle, exit --------------------------------------------------------------

    /** The Back table of prd3 (player rows) and rows 11, 13, 21, 26, 29, 34, 35. */
    fun onBack() {
        when {
            dialog != null -> onDialogDismiss()
            switchBase != null -> abandonSwitch() // row 34 (the switch chip is the most specific transient UI)
            (session == SessionState.Playing || session == SessionState.Paused || session == SessionState.Released) && overlay != OverlayMode.HIDDEN -> {
                overlay = OverlayMode.HIDDEN
                overlayJob?.cancel()
                render()
            }
            else -> leave()
        }
    }

    /** The cancel button of the switch chip / the Preparing screen. */
    fun onCancelLoading() {
        if (switchBase != null) abandonSwitch() else if (session == SessionState.Loading) leave()
    }

    /** Stop and exit from any state, releasing whatever is held exactly once. */
    private fun leave() {
        countdownJob?.cancel()
        if (session == SessionState.EndedResolving) nextResolution.cancel()
        stopHeldTimers()
        stopTicker()
        when (session) {
            SessionState.Loading -> {
                abandonRequest() // row 35
                att?.let(::finishSession)
            }
            SessionState.Released -> releasedFinalPut() // row 11: no DELETE
            else -> att?.let(::finishSession)
        }
        exit()
    }

    private fun exit() {
        session = SessionState.Exited
        render()
        handOffPrompt()
        if (exitSent) return
        exitSent = true
        _effects.trySend(PlayerEffect.Exit)
        releaseController()
    }

    private fun releaseController() {
        if (releasedController) return
        releasedController = true
        controller.setListener(null)
        controller.release()
    }

    /** Activity `ON_STOP` (rows 4, 12, 22, 23, 27, 31). */
    fun onStop() {
        when (session) {
            SessionState.Playing, SessionState.Paused -> {
                if (switchBase != null) {
                    stopForSwitch()
                } else {
                    backgroundAttached()
                }
            }
            SessionState.Loading -> if (switchBase != null) stopForSwitch() else {
                val spec = pending
                abandonRequest()
                stopTicker()
                session = SessionState.Background
                // Row 31: record the request and re-send it on ON_START, paused, previous_session_id = null.
                resumeOnStart = if (spec != null) {
                    { send(spec.copy(previousSessionId = null, play = false, keepFrame = false)) }
                } else {
                    { startItemPaused() }
                }
                controller.pause()
            }
            SessionState.Countdown -> {
                countdownJob?.cancel()
                stopHeldTimers()
                att?.let(::finishSession)
                countdownSeconds = null
                session = SessionState.EndedIdle
            }
            SessionState.EndedResolving -> {
                stopHeldTimers()
                att?.let(::finishSession)
                idleAfterResolve = true
            }
            else -> Unit
        }
        render()
    }

    private var startPaused = false

    private fun startItemPaused() {
        startPaused = true
        startItem()
    }

    private fun backgroundAttached() {
        val a = att ?: return
        val wasDirect = a.mode == PlaybackMode.DIRECT
        controller.pause()
        savedPositionMs = controller.positionMs
        if (!a.putDone) a.reporter.onPlayingChanged(false)
        stopHeldTimers()
        stopTicker()
        finishSession(a)
        controller.stop()
        session = SessionState.Background
        resumeOnStart = if (wasDirect) {
            { recoverDirectPaused(a) }
        } else {
            { send(RequestSpec(a.file, savedPositionMs / 1000.0, selectedAudioIndex, null, Kind.RECOVERY, play = false, keepFrame = true)) }
        }
    }

    /** Row 31 with a `switchBase`: the switch is abandoned, then the direct attachment is handled as row 4. */
    private fun stopForSwitch() {
        abandonRequest()
        val base = switchBase ?: return
        switchBase = null
        switchAudioIndex = null
        pending = null
        att = base
        controller.pause()
        savedPositionMs = controller.positionMs
        stopTicker()
        finishSession(base)
        controller.stop()
        session = SessionState.Background
        resumeOnStart = {
            recoverDirectPaused(base)
            showToast(UiText.Res(R.string.audio_change_cancelled))
        }
    }

    /** Direct recovery (row 5): new attachment key, `prepare()` + `seekTo(saved)`, paused, no request. */
    private fun recoverDirectPaused(old: Attachment) {
        val a = Attachment(nextKey++, old.file, old.plan, old.caps, savedPositionMs)
        a.reporter = ProgressReporter(viewModelScope, send = { putAndRemember(it) }, snapshot = { snapshot(a) }, log = { deps.log("progress", it) })
        att = a
        session = SessionState.Paused
        loadMedia(a, savedPositionMs)
        controller.seekTo(savedPositionMs)
        controller.pause()
        overlay = OverlayMode.FULL
        focusPlayPause++
        render()
    }

    /** Activity `ON_START` (rows 5, 12, 24, 27). */
    fun onStart() {
        if (session != SessionState.Background) return
        val resume = resumeOnStart ?: return
        resumeOnStart = null
        viewModelScope.launch {
            deps.guard.ensureFresh()
            resume()
        }
    }

    override fun onCleared() {
        cleared = true
        // Row 30: finishSession() for the current key; in Loading also row 33 for a late response.
        when (session) {
            SessionState.Loading -> {
                abandonRequest()
                switchBase?.let(::finishSession)
                att?.let(::finishSession)
            }
            SessionState.Released -> releasedFinalPut()
            else -> att?.let(::finishSession)
        }
        stopHeldTimers()
        releaseController()
        handOffPrompt()
        super.onCleared()
    }

    // ---- Rating prompt (ratings prd3 FR-T6) ------------------------------------------------

    /** Every progress PUT of the session (all reporters, the released PUT): keeps the answer's prompt. */
    private suspend fun putAndRemember(req: ProgressRequest): ProgressReport =
        deps.watch.putProgress(req).also { r -> r.rating_prompt?.let(::rememberPrompt) }

    /** The latest prompt wins; one answering after the hand-off (a late final PUT) is offered at once. */
    private fun rememberPrompt(prompt: RatingPrompt) {
        sessionPrompt.set(prompt)
        if (promptHandedOff.get()) deps.ratingPrompts.offer(profileId, prompt)
    }

    /** Once per session (exit or clear, whichever comes first): the root host shows it after the player is gone. */
    private fun handOffPrompt() {
        if (promptHandedOff.compareAndSet(false, true)) sessionPrompt.get()?.let { deps.ratingPrompts.offer(profileId, it) }
    }

    // ---- Ticker, toasts, render -------------------------------------------------------------

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                onTick()
            }
        }
    }

    private fun stopTicker() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun onTick() {
        val a = att
        if (a != null && session == SessionState.Playing && ::item.isInitialized && item.type == "episode" && next == NextState.NotStarted) {
            val duration = effectiveDurationMs(a)
            if (duration != null && duration - controller.positionMs <= PREFETCH_WINDOW_MS) resolveNext()
        }
        render()
    }

    private fun showToast(text: UiText) {
        toast = text
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(TOAST_MS)
            toast = null
            render()
        }
        render()
    }

    private fun render() {
        val a = switchBase ?: att
        val s = session
        val screen: PlayerScreenUi = when (s) {
            SessionState.Loading -> when {
                switchBase != null -> PlayerScreenUi.Video
                preparing != null -> PlayerScreenUi.Loading(preparing)
                pending?.keepFrame == true && att != null -> PlayerScreenUi.Video
                else -> PlayerScreenUi.Loading(null)
            }
            SessionState.Countdown, SessionState.EndedIdle -> countdownUi()?.let { PlayerScreenUi.Countdown(it) } ?: PlayerScreenUi.Video
            SessionState.EndScreen -> endScreen ?: PlayerScreenUi.Video
            SessionState.Error -> screenError ?: PlayerScreenUi.Video
            SessionState.Exited -> PlayerScreenUi.Loading(null)
            else -> PlayerScreenUi.Video
        }
        val released = s == SessionState.Released || s == SessionState.Background
        val position = when {
            released -> savedPositionMs
            a != null -> controller.positionMs
            else -> 0L
        }
        val pendingSeek = seek.pendingTargetMs
        val switchChip = if (switchBase != null) {
            preparing?.let { UiText.Value(PreparingText(it, R.string.player_audio_preparing)) } ?: UiText.Res(R.string.player_audio_switching)
        } else null
        val basePlaying = controller.isPlaying
        _state.value = PlayerUiState(
            session = s,
            screen = screen,
            title = titleText(),
            subtitle = subtitleText(),
            overlay = if (screen == PlayerScreenUi.Video) overlay else OverlayMode.HIDDEN,
            positionMs = position,
            durationMs = effectiveDurationMs(a),
            bufferedMs = if (released) position else controller.bufferedPositionMs,
            pendingSeekMs = pendingSeek,
            playing = s == SessionState.Playing || (switchBase != null && basePlaying),
            spinner = (s == SessionState.Loading && switchBase == null && screen == PlayerScreenUi.Video) ||
                (s == SessionState.Playing && controller.state == PlayerState.BUFFERING) || s == SessionState.EndedResolving || resolvingForButton,
            nextButton = nextButton(),
            chip = pendingSubtitle?.let { UiText.Res(R.string.player_subtitles_preparing) } ?: notice,
            switchChip = switchChip,
            toast = toast,
            dialog = dialog,
            keepScreenOn = s == SessionState.Playing || (s == SessionState.Loading && (switchBase == null || basePlaying)),
            focusPlayPause = focusPlayPause,
        )
    }

    private fun nextButton(): NextKind? {
        if (!::item.isInitialized) return null
        val f = (switchBase ?: att)?.file ?: return null
        return if (item.type == "film") {
            NextKind.PART.takeIf { !VersionSelector.isLastPart(f, item.files) }
        } else {
            NextKind.EPISODE.takeIf { item.nextEpisodeId != null && next != NextState.None }
        }
    }

    private fun titleText(): UiText? {
        if (!::item.isInitialized) return null
        if (item.type == "episode") return item.showTitle?.let(UiText::Data)
        val title = item.film?.title ?: return null
        val part = file?.part_number ?: return UiText.Data(title)
        val total = VersionSelector.group(item.files).parts.size
        return UiText.Res(R.string.player_title_part, listOf(title, Format.count(part), Format.count(total)))
    }

    private fun subtitleText(): UiText? {
        if (!::item.isInitialized || item.type != "episode" || item.season == 0 && item.episode == 0) return null
        return UiText.Joined(listOf(UiText.Value(Format.episodeCode(item.season, item.episode)), UiText.Value(Format.episodeTitle(item.episode, item.episodeTitle))))
    }

    private fun countdownUi(): NextUi? = when (val target = countdownTarget) {
        is NextTarget.Episode -> NextUi(
            kind = NextKind.EPISODE,
            code = Format.episodeCode(target.episode.season_number, target.episode.episode_number),
            title = UiText.Value(Format.episodeTitle(target.episode.episode_number, target.episode.title)),
            still = target.episode.still?.small ?: target.episode.show_backdrop?.small,
            partNumber = null,
            seconds = if (session == SessionState.Countdown) countdownSeconds else null,
        )
        is NextTarget.Part -> NextUi(NextKind.PART, null, null, null, target.file.part_number, if (session == SessionState.Countdown) countdownSeconds else null)
        null -> null
    }

    companion object {
        const val OFF_KEY = "off"
        const val KEEP_ALIVE_MS = 30_000L
        const val PAUSE_RELEASE_MS = 30 * 60_000L
        const val TICK_MS = 1_000L
        const val OVERLAY_HIDE_MS = 5_000L
        const val PROGRESS_HIDE_MS = 3_000L
        const val TOAST_MS = 4_000L
        const val NOTICE_MS = 5_000L
        const val PREFETCH_WINDOW_MS = 30_000L
        const val EPISODE_COUNTDOWN_SECONDS = 10
        const val PART_PROMPT_SECONDS = 5
        const val DEFAULT_SUBTITLE_BUDGET_MS = 3_000L
        const val SWITCH_END_MARGIN_MS = 2_000L
        const val FINAL_TIMEOUT_MS = 10_000L
        const val ORPHAN_WAIT_MS = 45_000L
    }
}
