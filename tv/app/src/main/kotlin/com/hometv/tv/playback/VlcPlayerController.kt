package com.hometv.tv.playback

import android.widget.FrameLayout
import com.hometv.tv.core.playback.MediaItemSpec
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.core.playback.PlayerState
import kotlin.math.abs

/**
 * [PlayerController] over libVLC through [VlcPlayerPort]. Mirrors `ExoPlayerController`: keeps the wanted
 * audio/subtitle selection and applies it when the track lists arrive, reports the `TrackCheck` input once per
 * media item, maps every libVLC error to `PlayerFailure(VLC_ERROR, engine = VLC)`. The stream URL gets
 * `?token=` here (libVLC can't send a Bearer header) and is never logged. Main thread only.
 *
 * - **Surface first, then open**: libVLC 3 needs its video output attached before it opens the media, so
 *   [prepare] only records the open; it runs once a layout is [attach]ed (now or later).
 * - **Listener callbacks are never synchronous** with a command: what a command changes ([prepare] → BUFFERING,
 *   [stop] → IDLE, [pause] → not playing) is visible in the getters at once and reported through [post]
 *   (dropped after [release]); a failed creation reports `onStateChanged(IDLE)` + `onFailure` through [post] too.
 *   libVLC's own events arrive on the main looper from its event loop, never inside a command, and are
 *   forwarded as they arrive. (ExoPlayer reports some command-caused state changes inline; the ViewModel only
 *   needs this stricter contract for the failure.)
 * - **One generation per open**: each open (and [stop], and an error) starts a new generation; events of a
 *   replaced or stopped media are dropped.
 * - `isPlaying` = the last event was `Playing` && play intent && READY (false while buffering, as with Exo).
 */
class VlcPlayerController(
    /** Creates the port at the first open or attach (production: `{ LibVlcPort(libVlc.get()) }`). May throw `VlcUnavailableException`. */
    private val portFactory: () -> VlcPlayerPort,
    private val token: () -> String?,
    /** Log seam (production: `Log.i("HomeTvPlayer", …)`); never receives a URL or the token. */
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    /** Main-thread post seam (production: `Handler(Looper.getMainLooper())::post`). */
    private val post: (() -> Unit) -> Unit,
) : PlayerController {
    private var listener: PlayerController.Listener? = null
    private var port: VlcPlayerPort? = null

    /** The port factory threw: every later port call is skipped and the factory is never called again. */
    private var failed = false
    private var released = false
    private var layout: FrameLayout? = null

    private var spec: MediaItemSpec? = null
    private var wantedAudio: Int? = null
    private var wantedSubtitle: String? = null
    private var checkPending = false

    private var playWhenReady = false

    /** [prepare] was called and the media isn't open yet (waits for a layout). */
    private var openPending = false

    /** A media is open in the port (until [stop], an error or `Stopped`). */
    private var opened = false
    private var generation = 0

    /** The first `Playing`/`Paused` of the current open was seen. */
    private var started = false
    private var openStartMs = 0L

    /** A start position not applied yet: the next open's `:start-time`, or a seek made before the first `Playing`. */
    private var pendingStartMs: Long? = null
    private var position = 0L
    private var length = -1L
    private var eventPlaying = false
    private var currentState = PlayerState.IDLE
    private var playing = false

    /** What the listener was told last (callbacks fire only on change, and a stale posted value is never reported). */
    private var reportedState = PlayerState.IDLE
    private var reportedPlaying = false
    private var reportedDuration: Long? = null
    private var flushPosted = false

    override val positionMs: Long get() = position
    override val durationMs: Long? get() = length.takeIf { it > 0 }
    override val bufferedPositionMs: Long get() = position
    override val isPlaying: Boolean get() = playing
    override val state: PlayerState get() = currentState

    override fun setListener(listener: PlayerController.Listener?) {
        this.listener = listener
    }

    /** Shows the video in [layout] (the Player screen's `VLCVideoLayout`). Idempotent for the same layout. */
    fun attach(layout: FrameLayout) {
        if (released || failed || layout === this.layout) return
        val p = ensurePort() ?: return
        if (this.layout != null) p.detachViews()
        p.attachViews(layout)
        this.layout = layout
        if (openPending) open(p)
    }

    /** No-op unless [layout] is the attached one. Playback state is untouched. */
    fun detach(layout: FrameLayout) {
        if (layout !== this.layout) return
        this.layout = null
        if (!released && !failed) port?.detachViews()
    }

    override fun setMediaItem(item: MediaItemSpec, startPositionMs: Long) {
        if (released) return
        spec = item
        pendingStartMs = startPositionMs
        position = startPositionMs
        length = -1
        reportedDuration = null
        checkPending = true
    }

    override fun prepare() {
        if (released) return
        if (failed) {
            postFailure()
            return
        }
        val p = ensurePort() ?: return
        openPending = true
        update(PlayerState.BUFFERING, inline = false)
        if (layout != null) open(p)
    }

    override fun play() {
        if (released || failed) return
        playWhenReady = true
        if (opened && currentState != PlayerState.ENDED) port?.play()
        update(currentState, inline = false)
    }

    override fun pause() {
        if (released || failed) return
        playWhenReady = false
        if (opened) port?.pause()
        update(currentState, inline = false)
    }

    override fun seekTo(positionMs: Long) {
        if (released || failed) return
        val p = port
        when {
            p == null || !opened || !started -> {
                // Applied as the next open's start-time, or at the first `Playing` of this one.
                pendingStartMs = positionMs
                position = positionMs
            }
            currentState == PlayerState.ENDED -> {
                // libVLC can't seek an ended media: reopen at the target.
                pendingStartMs = positionMs
                position = positionMs
                openPending = true
                update(PlayerState.BUFFERING, inline = false)
                if (layout != null) open(p)
            }
            !p.isSeekable -> Unit // e.g. an AVI without an index: ignored, the position is kept
            else -> {
                p.setTime(positionMs)
                position = positionMs
            }
        }
    }

    override fun stop() {
        if (released || failed) return
        if (opened) port?.stop()
        closeMedia()
        openPending = false
        update(PlayerState.IDLE, inline = false)
    }

    override fun release() {
        if (released) return
        released = true
        port?.let { p ->
            p.setEventListener(null)
            if (layout != null) p.detachViews()
            p.release()
        }
        port = null
        layout = null
        opened = false
        currentState = PlayerState.IDLE
        playing = false
    }

    override fun selectAudio(serverIndex: Int?) {
        wantedAudio = serverIndex
        applySelection()
    }

    override fun selectSubtitle(trackId: String?) {
        wantedSubtitle = trackId
        applySelection()
    }

    // ---- Internals ---------------------------------------------------------------------------

    private fun ensurePort(): VlcPlayerPort? {
        if (failed || released) return null
        port?.let { return it }
        return try {
            portFactory().also { port = it }
        } catch (e: Throwable) {
            log("libVLC player unavailable", e)
            failed = true
            closeMedia()
            currentState = PlayerState.IDLE
            playing = false
            postFailure()
            null
        }
    }

    private fun open(p: VlcPlayerPort) {
        val s = spec ?: return
        openPending = false
        val gen = ++generation
        val start = pendingStartMs ?: position
        pendingStartMs = null
        openStartMs = start
        position = start
        started = false
        eventPlaying = false
        opened = true
        p.setEventListener { event -> if (gen == generation) onEvent(event) }
        try {
            p.open(VlcUrls.withToken(s.url, token()), VlcOptions.media(start, startPaused = !playWhenReady), s.subtitles.map { it.fileUri })
        } catch (e: RuntimeException) {
            log("libVLC open failed", e)
            closeMedia()
            currentState = PlayerState.IDLE
            playing = false
            postFailure()
        }
    }

    /** The open media is gone: its later events are dropped. */
    private fun closeMedia() {
        opened = false
        started = false
        eventPlaying = false
        generation++
    }

    private fun onEvent(event: VlcEvent) {
        val p = port ?: return
        when (event) {
            is VlcEvent.TimeChanged -> if (started || pendingStartMs == null) position = event.timeMs
            is VlcEvent.LengthChanged -> {
                length = event.lengthMs
                if (durationMs != reportedDuration) {
                    reportedDuration = durationMs
                    listener?.onDurationChanged(durationMs)
                }
            }
            VlcEvent.TracksChanged -> applySelection()
            VlcEvent.EncounteredError -> error()
            VlcEvent.EndReached -> if (VlcEventMapper.isEarlyEnd(position, length)) {
                log("early end at ${position / 1000} of ${length / 1000}", null)
                error()
            } else {
                eventPlaying = false
                update(PlayerState.ENDED, inline = true)
            }
            VlcEvent.Playing, VlcEvent.Paused -> {
                eventPlaying = event == VlcEvent.Playing
                firstStart(p, event)
                update(PlayerState.READY, inline = true)
                applySelection()
                if (event == VlcEvent.Playing) reportTracks(p)
            }
            VlcEvent.Stopped -> {
                closeMedia()
                update(PlayerState.IDLE, inline = true)
            }
            VlcEvent.Opening, is VlcEvent.Buffering -> VlcEventMapper.state(event)?.let { update(it, inline = true) }
        }
    }

    /**
     * The first `Playing`/`Paused` of an open: a seek made since the open (`recover()`'s `prepare(); seekTo(p); play()`)
     * is applied when it differs from the start-time by more than 1 s, and the play intent wins over what libVLC
     * did (`:start-paused` is the main mechanism; this is the safety net, and resumes a start-paused input when
     * `play()` came before libVLC applied the pause).
     */
    private fun firstStart(p: VlcPlayerPort, event: VlcEvent) {
        if (started) return
        started = true
        val target = pendingStartMs
        pendingStartMs = null
        if (target != null && abs(target - openStartMs) > SEEK_TOLERANCE_MS && p.isSeekable) {
            p.setTime(target)
            position = target
        }
        if (event == VlcEvent.Playing && !playWhenReady) p.pause()
        if (event == VlcEvent.Paused && playWhenReady) p.play()
    }

    private fun error() {
        closeMedia()
        currentState = PlayerState.IDLE
        playing = false
        if (reportedPlaying) {
            reportedPlaying = false
            listener?.onIsPlayingChanged(false)
        }
        reportedState = PlayerState.IDLE
        listener?.onStateChanged(PlayerState.IDLE)
        listener?.onFailure(VlcEventMapper.failure())
    }

    private fun applySelection() {
        val p = port ?: return
        val s = spec ?: return
        if (!opened || failed || released) return
        VlcTrackMapper.audioTrackId(p.audioTrackIds(), wantedAudio, s)?.let { id ->
            if (id != p.selectedAudioTrackId()) p.setAudioTrack(id)
        }
        val spu = VlcTrackMapper.spuTrackId(p.spuTrackIds(), wantedSubtitle, s)
        if (spu != p.selectedSpuTrackId()) p.setSpuTrack(spu)
    }

    /** Once per media item, at the first `Playing` with tracks, after the selection was applied. */
    private fun reportTracks(p: VlcPlayerPort) {
        if (!checkPending) return
        val video = p.videoTrackCount()
        val audio = p.audioTrackIds()
        if (video <= 0 && audio.isEmpty()) return
        checkPending = false
        listener?.onTracksChanged(VlcTrackMapper.checkInput(video, audio, p.selectedAudioTrackId(), expectedAudio = wantedAudio != null))
    }

    /** Sets the state and recomputes `isPlaying`; reports the change now (events) or through [post] (commands). */
    private fun update(state: PlayerState, inline: Boolean) {
        currentState = state
        playing = eventPlaying && playWhenReady && state == PlayerState.READY
        if (inline) {
            flush()
        } else if (!flushPosted) {
            flushPosted = true
            post {
                flushPosted = false
                if (!released) flush()
            }
        }
    }

    private fun flush() {
        if (currentState != reportedState) {
            reportedState = currentState
            listener?.onStateChanged(currentState)
        }
        if (playing != reportedPlaying) {
            reportedPlaying = playing
            listener?.onIsPlayingChanged(playing)
        }
    }

    /** `onStateChanged(IDLE)` + `onFailure(VLC_ERROR)` through [post], dropped when it runs after [release]. */
    private fun postFailure() {
        post {
            if (!released) {
                reportedState = PlayerState.IDLE
                reportedPlaying = false
                listener?.onStateChanged(PlayerState.IDLE)
                listener?.onFailure(VlcEventMapper.failure())
            }
        }
    }

    private companion object {
        /** A pending seek closer than this to the open's start-time is already there. */
        const val SEEK_TOLERANCE_MS = 1_000L
    }
}
