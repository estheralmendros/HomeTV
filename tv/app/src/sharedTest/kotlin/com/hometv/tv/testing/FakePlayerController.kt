package com.hometv.tv.testing

import com.hometv.tv.core.playback.MediaItemSpec
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.PlayerState
import com.hometv.tv.core.playback.TrackCheckInput

/**
 * A scriptable [PlayerController] (no ExoPlayer): records every call in [log], exposes the media item
 * and track selection, and lets the test drive the callbacks ([ready], [end], [fail], [tracks]).
 * `play()`/`pause()` report `onIsPlayingChanged` synchronously, as a READY ExoPlayer would.
 */
class FakePlayerController : PlayerController {
    val log = mutableListOf<String>()
    var listener: PlayerController.Listener? = null
        private set
    var item: MediaItemSpec? = null
    var audio: Int? = null
    var subtitle: String? = null
    var released = false
    var stopped = false
    var playWhenReady = false

    override var positionMs: Long = 0
    override var durationMs: Long? = null
    override var bufferedPositionMs: Long = 0
    override var isPlaying: Boolean = false
    override var state: PlayerState = PlayerState.IDLE

    override fun setListener(listener: PlayerController.Listener?) {
        this.listener = listener
    }

    override fun setMediaItem(item: MediaItemSpec, startPositionMs: Long) {
        this.item = item
        positionMs = startPositionMs
        state = PlayerState.IDLE
        log += "setMediaItem ${item.url} @$startPositionMs"
    }

    override fun prepare() {
        stopped = false
        state = PlayerState.BUFFERING
        log += "prepare"
    }

    override fun play() {
        playWhenReady = true
        log += "play"
        updatePlaying(true)
    }

    override fun pause() {
        playWhenReady = false
        log += "pause"
        updatePlaying(false)
    }

    override fun seekTo(positionMs: Long) {
        this.positionMs = positionMs
        log += "seekTo $positionMs"
    }

    override fun stop() {
        stopped = true
        state = PlayerState.IDLE
        log += "stop"
        updatePlaying(false)
    }

    override fun release() {
        released = true
        log += "release"
    }

    override fun selectAudio(serverIndex: Int?) {
        audio = serverIndex
        log += "audio $serverIndex"
    }

    override fun selectSubtitle(trackId: String?) {
        subtitle = trackId
        log += "subtitle $trackId"
    }

    private fun updatePlaying(playing: Boolean) {
        val now = playing && !stopped && state != PlayerState.ENDED
        if (now != isPlaying) {
            isPlaying = now
            listener?.onIsPlayingChanged(now)
        }
    }

    // ---- Test drivers ----------------------------------------------------------------------

    /** STATE_READY (+ the track report of a healthy file). */
    fun ready(tracksOk: Boolean = true) {
        state = PlayerState.READY
        if (tracksOk) listener?.onTracksChanged(TrackCheckInput(true, true, true, true, true, true))
        listener?.onStateChanged(PlayerState.READY)
        updatePlaying(playWhenReady)
    }

    fun tracks(input: TrackCheckInput) = listener?.onTracksChanged(input)

    fun end() {
        positionMs = durationMs ?: positionMs
        state = PlayerState.ENDED
        updatePlaying(false)
        listener?.onStateChanged(PlayerState.ENDED)
    }

    fun fail(failure: PlayerFailure) {
        state = PlayerState.IDLE
        updatePlaying(false)
        listener?.onFailure(failure)
    }

    fun clearLog() = log.clear()
}
