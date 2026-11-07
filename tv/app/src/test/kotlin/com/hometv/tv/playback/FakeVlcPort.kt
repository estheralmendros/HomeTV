package com.hometv.tv.playback

import android.widget.FrameLayout

/**
 * A scriptable [VlcPlayerPort]: records every call in [calls] (getters included, as `get <name>`), keeps the
 * listener of each `open` and lets the test emit [VlcEvent]s through the current one ([emit]).
 */
class FakeVlcPort : VlcPlayerPort {
    data class Open(val uri: String, val options: List<String>, val slaves: List<String>)

    val calls = mutableListOf<String>()
    val opens = mutableListOf<Open>()
    var listener: ((VlcEvent) -> Unit)? = null
        private set

    /** Names the layouts in [calls] (`attachViews <name>`). */
    val layoutNames = mutableMapOf<FrameLayout, String>()

    var currentTime = 0L
    var length = -1L
    var playing = false
    var seekable = true
    var audioIds = listOf<Int>()
    var selectedAudio = -1
    var spuIds = listOf<Int>()
    var selectedSpu = -1
    var videoTracks = 1

    fun emit(event: VlcEvent) = listener?.invoke(event)

    fun clear() = calls.clear()

    /** Only the commands (no getter reads). */
    fun commands() = calls.filterNot { it.startsWith("get ") }

    override fun open(uri: String, mediaOptions: List<String>, subtitleSlaves: List<String>) {
        opens += Open(uri, mediaOptions, subtitleSlaves)
        calls += "open"
    }

    override fun play() {
        calls += "play"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun release() {
        calls += "release"
    }

    override val timeMs: Long get() = currentTime.also { calls += "get timeMs" }
    override val lengthMs: Long get() = length.also { calls += "get lengthMs" }
    override val isPlaying: Boolean get() = playing.also { calls += "get isPlaying" }
    override val isSeekable: Boolean get() = seekable.also { calls += "get isSeekable" }

    override fun setTime(timeMs: Long) {
        calls += "setTime $timeMs"
    }

    override fun audioTrackIds(): List<Int> = audioIds.also { calls += "get audioTrackIds" }

    override fun selectedAudioTrackId(): Int = selectedAudio.also { calls += "get selectedAudioTrackId" }

    override fun setAudioTrack(id: Int) {
        selectedAudio = id
        calls += "setAudioTrack $id"
    }

    override fun spuTrackIds(): List<Int> = spuIds.also { calls += "get spuTrackIds" }

    override fun selectedSpuTrackId(): Int = selectedSpu.also { calls += "get selectedSpuTrackId" }

    override fun setSpuTrack(id: Int) {
        selectedSpu = id
        calls += "setSpuTrack $id"
    }

    override fun videoTrackCount(): Int = videoTracks.also { calls += "get videoTrackCount" }

    override fun setEventListener(listener: ((VlcEvent) -> Unit)?) {
        this.listener = listener
        calls += if (listener == null) "setEventListener null" else "setEventListener"
    }

    override fun attachViews(layout: FrameLayout) {
        calls += "attachViews ${layoutNames[layout] ?: "?"}"
    }

    override fun detachViews() {
        calls += "detachViews"
    }
}
