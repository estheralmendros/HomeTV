package com.hometv.tv.core.playback

/**
 * D-pad seek stepping with a delayed commit. Pure: times come in as parameters.
 *
 * Steps: 10 s for `repeatCount < 5`, 30 s below 15, 60 s beyond. The target is clamped to
 * `[0, duration − 1 s]`, or only to `≥ 0` while the duration is unknown. The first key of a gesture
 * starts from the player position; later keys move the pending target (shown on the progress bar at
 * once). The `player.seekTo` is committed [commitDelayMs] after the last key event ([poll]), or on
 * key-up after a long press ([onKeyUp]), so holding the key produces one seek (one ffmpeg restart in HLS).
 */
class SeekAccumulator(private val commitDelayMs: Long = COMMIT_DELAY_MS) {
    private var target: Long? = null
    private var lastEventMs: Long = 0
    private var longPress = false

    /** The target shown on the progress bar, or null when no seek is pending. */
    val pendingTargetMs: Long? get() = target

    /** Returns the new pending target. */
    fun onKeyDown(forward: Boolean, repeatCount: Int, nowMs: Long, positionMs: Long, durationMs: Long?): Long {
        val base = target ?: positionMs
        val step = stepMs(repeatCount)
        val upper = durationMs?.let { (it - END_MARGIN_MS).coerceAtLeast(0) } ?: Long.MAX_VALUE
        val next = (if (forward) base + step else base - step).coerceIn(0, upper)
        target = next
        lastEventMs = nowMs
        if (repeatCount > 0) longPress = true
        return next
    }

    /** Commits at once after a long press; a short press keeps its timer from the key-down ([poll]). */
    fun onKeyUp(): Long? = if (target != null && longPress) take() else null

    /** When [poll] will commit the pending target, or null. */
    fun commitDueAtMs(): Long? = if (target == null) null else lastEventMs + commitDelayMs

    /** The target to `seekTo` once [commitDelayMs] passed since the last key event, else null. */
    fun poll(nowMs: Long): Long? {
        val due = commitDueAtMs() ?: return null
        return if (nowMs >= due) take() else null
    }

    fun cancel() {
        target = null
        longPress = false
    }

    private fun take(): Long? = target.also { cancel() }

    companion object {
        const val COMMIT_DELAY_MS = 600L
        private const val END_MARGIN_MS = 1_000L

        fun stepMs(repeatCount: Int): Long = when {
            repeatCount < 5 -> 10_000L
            repeatCount < 15 -> 30_000L
            else -> 60_000L
        }
    }
}
