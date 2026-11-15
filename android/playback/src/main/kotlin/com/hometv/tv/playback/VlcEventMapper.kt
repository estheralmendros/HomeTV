package com.hometv.tv.playback

import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.core.playback.PlaybackErrorCodes
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.PlayerState

/** libVLC events → `PlayerState` / `PlayerFailure`. */
object VlcEventMapper {
    /** Opening/Buffering(<100) → BUFFERING; Buffering(100)/Playing/Paused → READY; EndReached → ENDED;
     *  Stopped/EncounteredError → IDLE; TimeChanged/LengthChanged/TracksChanged → null (no state change). */
    fun state(event: VlcEvent): PlayerState? = when (event) {
        VlcEvent.Opening -> PlayerState.BUFFERING
        is VlcEvent.Buffering -> if (event.percent >= 100f) PlayerState.READY else PlayerState.BUFFERING
        VlcEvent.Playing, VlcEvent.Paused -> PlayerState.READY
        VlcEvent.EndReached -> PlayerState.ENDED
        VlcEvent.Stopped, VlcEvent.EncounteredError -> PlayerState.IDLE
        is VlcEvent.TimeChanged, is VlcEvent.LengthChanged, VlcEvent.TracksChanged -> null
    }

    fun failure(): PlayerFailure = PlayerFailure(errorCode = PlaybackErrorCodes.VLC_ERROR, engine = PlaybackEngine.VLC)

    /** prd.md decision 15: an `EndReached` this far before the known length is a dropped stream, not an end. */
    const val EARLY_END_MARGIN_MS = 30_000L

    /** True when [lengthMs] > 0 and [positionMs] < [lengthMs] − [EARLY_END_MARGIN_MS]. */
    fun isEarlyEnd(positionMs: Long, lengthMs: Long): Boolean = lengthMs > 0 && positionMs < lengthMs - EARLY_END_MARGIN_MS
}
