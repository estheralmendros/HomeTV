package com.hometv.tv.core.playback

/** Counts only the time spent READY and playing since the attachment, until the first rendered frame. */
class FirstFrameWatchdog(private val thresholdMs: Long = THRESHOLD_MS) {
    private var armed = true
    private var countedMs = 0L
    private var lastTickMs: Long? = null
    private var lastCounting = false

    /** A new attachment (`setMediaItem`): re-arms and clears the counted time. */
    fun reset() {
        armed = true
        countedMs = 0L
        lastTickMs = null
        lastCounting = false
    }

    /** Disarms until the next [reset]. */
    fun onFirstFrame() {
        armed = false
    }

    /** True once per arm, when the counted READY + playing time reaches the threshold. */
    fun tick(nowMs: Long, state: PlayerState, isPlaying: Boolean): Boolean {
        val counting = state == PlayerState.READY && isPlaying
        val last = lastTickMs
        lastTickMs = nowMs
        val wasCounting = lastCounting
        lastCounting = counting
        if (!armed) return false
        if (counting && wasCounting && last != null) countedMs += nowMs - last
        if (countedMs < thresholdMs) return false
        armed = false
        return true
    }

    companion object {
        const val THRESHOLD_MS = 5_000L
    }
}
