package com.hometv.tv.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.hometv.tv.core.api.ApiErrors
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.playback.MediaItemSpec
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.PlayerState
import com.hometv.tv.core.playback.RendererType
import com.hometv.tv.core.playback.UrlKind
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * [PlayerController] over Media3 ExoPlayer. Keeps the wanted audio/subtitle
 * selection and re-applies it on the first non-empty `Tracks` of every media item (rule 5), then
 * reports the `TrackCheck` input once per item (rule 10) after the overrides took effect. Every
 * `PlaybackException` becomes a pure [PlayerFailure]; `urlKind` comes from the most recent
 * `AnalyticsListener.onLoadError` (cleared on each new media item). While a `PlayerView` shows the
 * player, a Media3 [MediaSession] over a [RemoteCommandPlayer] receives headset / Bluetooth / media
 * controller commands and hands them to the listener (headset and Bluetooth play/pause).
 */
@OptIn(UnstableApi::class)
class ExoPlayerController(private val player: ExoPlayer) : PlayerController {
    private var listener: PlayerController.Listener? = null
    private var spec: MediaItemSpec? = null
    private var wantedAudio: Int? = null
    private var wantedSubtitle: String? = null
    private var checkPending = false
    private var lastLoad: LoadRecord? = null
    private var mediaSession: MediaSession? = null

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            if (tracks.groups.isEmpty()) return
            val changed = applySelection(tracks)
            if (!changed && checkPending) {
                checkPending = false
                listener?.onTracksChanged(ExoTrackMapper.checkInput(tracks, fileHasVideo = true, expectedAudio = wantedAudio != null))
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            listener?.onStateChanged(stateOf(playbackState))
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener?.onIsPlayingChanged(isPlaying)
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            listener?.onDurationChanged(durationMs)
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onFailure(failureOf(error, lastLoad, spec?.url))
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            lastLoad = LoadRecord(loadEventInfo.uri.toString(), mediaLoadData.trackType)
        }
    }

    init {
        player.addListener(playerListener)
        player.addAnalyticsListener(analytics)
    }

    override val positionMs: Long get() = player.currentPosition
    override val durationMs: Long? get() = player.duration.takeIf { it != C.TIME_UNSET && it >= 0 }
    override val bufferedPositionMs: Long get() = player.bufferedPosition
    override val isPlaying: Boolean get() = player.isPlaying
    override val state: PlayerState get() = stateOf(player.playbackState)

    /** The id of the live `MediaSession`, null while no `PlayerView` shows the player (tests). */
    @androidx.annotation.VisibleForTesting
    internal val sessionId: String? get() = mediaSession?.id

    /** Shows this player in [view] (the Player screen's `PlayerView`); the ExoPlayer never leaves this class. */
    fun attach(view: PlayerView) {
        if (view.player !== player) view.player = player
        if (mediaSession == null) {
            mediaSession = MediaSession.Builder(view.context, RemoteCommandPlayer(player) { listener })
                .setId("hometv-player-${sessionIds.incrementAndGet()}")
                .setCallback(RemoteOnlyCallback)
                .build()
        }
    }

    fun detach(view: PlayerView) {
        if (view.player === player) view.player = null
        releaseSession()
    }

    private fun releaseSession() {
        mediaSession?.release()
        mediaSession = null
    }

    override fun setListener(listener: PlayerController.Listener?) {
        this.listener = listener
    }

    override fun setMediaItem(item: MediaItemSpec, startPositionMs: Long) {
        spec = item
        lastLoad = null
        checkPending = true
        player.setMediaItem(MediaItemBuilder.build(item), startPositionMs)
    }

    override fun prepare() = player.prepare()

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun stop() = player.stop()

    override fun release() {
        releaseSession()
        player.removeListener(playerListener)
        player.removeAnalyticsListener(analytics)
        player.release()
    }

    override fun selectAudio(serverIndex: Int?) {
        wantedAudio = serverIndex
        applySelection(player.currentTracks)
    }

    override fun selectSubtitle(trackId: String?) {
        wantedSubtitle = trackId
        applySelection(player.currentTracks)
    }

    /** Applies the wanted overrides to [tracks]; true when the parameters changed (a new `Tracks` follows). */
    private fun applySelection(tracks: Tracks): Boolean {
        val current = spec ?: return false
        if (tracks.groups.isEmpty()) return false
        val wanted = ExoTrackMapper.parameters(player.trackSelectionParameters, tracks, wantedAudio, wantedSubtitle, current)
        if (wanted == player.trackSelectionParameters) return false
        player.trackSelectionParameters = wanted
        return true
    }

    /** The URI and track type of the most recent load error. */
    data class LoadRecord(val uri: String, val trackType: Int)

    /** Every controller (AVRCP, the system, apps) gets only the [RemoteCommandPlayer] commands and no custom ones. */
    private object RemoteOnlyCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(RemoteCommandPlayer.ALLOWED_COMMANDS)
                .build()
    }

    companion object {
        /** MediaSession ids must be unique within the process (one per Player screen). */
        private val sessionIds = AtomicLong()

        fun stateOf(playbackState: Int): PlayerState = when (playbackState) {
            Player.STATE_BUFFERING -> PlayerState.BUFFERING
            Player.STATE_READY -> PlayerState.READY
            Player.STATE_ENDED -> PlayerState.ENDED
            else -> PlayerState.IDLE
        }

        /**
         * `urlKind`: `file` scheme or a text load → SUBTITLE; `/api/hls/` → HLS (checked before
         * the media item URL, which is the HLS playlist in HLS mode); the direct stream URL → STREAM.
         */
        fun urlKind(load: LoadRecord?, streamUrl: String?): UrlKind {
            if (load == null) return UrlKind.OTHER
            val uri = load.uri
            return when {
                uri.startsWith("file:") || load.trackType == C.TRACK_TYPE_TEXT -> UrlKind.SUBTITLE
                pathOf(uri).startsWith("/api/hls/") -> UrlKind.HLS
                streamUrl != null && uri == streamUrl -> UrlKind.STREAM
                else -> UrlKind.OTHER
            }
        }

        fun failureOf(error: PlaybackException, load: LoadRecord?, streamUrl: String?): PlayerFailure {
            val http = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            val renderer = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
            val rendererMime = renderer?.rendererFormat?.sampleMimeType
            val rendererType = renderer?.let {
                when {
                    rendererMime?.startsWith("video/") == true -> RendererType.VIDEO
                    rendererMime?.startsWith("audio/") == true -> RendererType.AUDIO
                    else -> RendererType.OTHER
                }
            }
            val bodyCode = http?.let { e ->
                (ApiErrors.fromResponse(e.responseCode, e.responseBody.takeIf { it.isNotEmpty() }?.decodeToString(), null) as? ApiException.Http)?.code
            }
            return PlayerFailure(
                errorCode = error.errorCode,
                rendererType = rendererType,
                rendererMime = rendererMime,
                httpStatus = http?.responseCode,
                urlKind = urlKind(load, streamUrl),
                bodyCode = bodyCode,
            )
        }

        private fun pathOf(uri: String): String = uri.substringAfter("://", uri).let { rest ->
            val slash = rest.indexOf('/')
            if (slash < 0) "" else rest.substring(slash).substringBefore('?')
        }
    }
}
