package com.hometv.tv.playback

import android.widget.FrameLayout

/** libVLC events the controller needs, already decoded (no libVLC types). */
sealed interface VlcEvent {
    data object Opening : VlcEvent
    data class Buffering(val percent: Float) : VlcEvent
    data object Playing : VlcEvent
    data object Paused : VlcEvent
    data object Stopped : VlcEvent
    data object EndReached : VlcEvent
    data object EncounteredError : VlcEvent
    data class TimeChanged(val timeMs: Long) : VlcEvent
    data class LengthChanged(val lengthMs: Long) : VlcEvent

    /** `ESAdded` / `ESDeleted` / `ESSelected`: the track lists may have changed. */
    data object TracksChanged : VlcEvent
}

/**
 * One libVLC `MediaPlayer` (its events arrive on the main looper). Track ids are libVLC ES ids; -1 = disabled.
 * `VlcPlayerController` holds all the logic and talks only to this interface; [LibVlcPort] is the pass-through.
 */
interface VlcPlayerPort {
    /**
     * `Media(libVlc, Uri)`, [mediaOptions] via `addOption`, `setHWDecoderEnabled(true, false)`, each slave as a
     * subtitle slave (`file://` URIs), `mediaPlayer.media = media`, `media.release()`, `mediaPlayer.play()`.
     * The controller calls it only once a video layout is attached.
     */
    fun open(uri: String, mediaOptions: List<String>, subtitleSlaves: List<String>)
    fun play()
    fun pause()
    fun stop()
    fun release()
    val timeMs: Long
    val lengthMs: Long
    val isPlaying: Boolean
    val isSeekable: Boolean
    fun setTime(timeMs: Long)

    /** `getAudioTracks()` ids in libVLC order, without the -1 "Disable" entry (empty when libVLC returns null). */
    fun audioTrackIds(): List<Int>
    fun selectedAudioTrackId(): Int
    fun setAudioTrack(id: Int)

    /** `getSpuTracks()` ids in libVLC order, without -1 (empty when libVLC returns null). */
    fun spuTrackIds(): List<Int>

    /** `getSpuTrack()`: -1 when none is shown. */
    fun selectedSpuTrackId(): Int
    fun setSpuTrack(id: Int)
    fun videoTrackCount(): Int

    /** Replaces the listener; libVLC drops the events still queued for the previous one. */
    fun setEventListener(listener: ((VlcEvent) -> Unit)?)

    /** `attachViews(layout as VLCVideoLayout, null, enableSubtitles = true, useTextureView = false)`, scale `SURFACE_BEST_FIT`. */
    fun attachViews(layout: FrameLayout)
    fun detachViews()
}
