package com.hometv.tv.playback

import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.core.playback.PlaybackErrorCodes
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.PlayerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AC-7.5 / AC-7.6. */
class VlcEventMapperTest {
    @Test
    fun `every event maps to a state or to no change`() {
        val expected = listOf<Pair<VlcEvent, PlayerState?>>(
            VlcEvent.Opening to PlayerState.BUFFERING,
            VlcEvent.Buffering(0f) to PlayerState.BUFFERING,
            VlcEvent.Buffering(99.9f) to PlayerState.BUFFERING,
            VlcEvent.Buffering(100f) to PlayerState.READY,
            VlcEvent.Playing to PlayerState.READY,
            VlcEvent.Paused to PlayerState.READY,
            VlcEvent.EndReached to PlayerState.ENDED,
            VlcEvent.Stopped to PlayerState.IDLE,
            VlcEvent.EncounteredError to PlayerState.IDLE,
            VlcEvent.TimeChanged(1) to null,
            VlcEvent.LengthChanged(1) to null,
            VlcEvent.TracksChanged to null,
        )
        // Every VlcEvent subtype is in the table: a new one breaks the exhaustive `when` below at compile time.
        assertEquals(VlcEventKind.entries.toSet(), expected.map { kindOf(it.first) }.toSet())
        expected.forEach { (event, state) -> assertEquals(state, VlcEventMapper.state(event), "$event") }
    }

    private enum class VlcEventKind { OPENING, BUFFERING, PLAYING, PAUSED, STOPPED, END, ERROR, TIME, LENGTH, TRACKS }

    private fun kindOf(event: VlcEvent): VlcEventKind = when (event) {
        VlcEvent.Opening -> VlcEventKind.OPENING
        is VlcEvent.Buffering -> VlcEventKind.BUFFERING
        VlcEvent.Playing -> VlcEventKind.PLAYING
        VlcEvent.Paused -> VlcEventKind.PAUSED
        VlcEvent.Stopped -> VlcEventKind.STOPPED
        VlcEvent.EndReached -> VlcEventKind.END
        VlcEvent.EncounteredError -> VlcEventKind.ERROR
        is VlcEvent.TimeChanged -> VlcEventKind.TIME
        is VlcEvent.LengthChanged -> VlcEventKind.LENGTH
        VlcEvent.TracksChanged -> VlcEventKind.TRACKS
    }

    @Test
    fun `the failure is a VLC one`() {
        assertEquals(PlayerFailure(PlaybackErrorCodes.VLC_ERROR, engine = PlaybackEngine.VLC), VlcEventMapper.failure())
    }

    @Test
    fun `an end more than 30 s before a known length is early`() {
        assertTrue(VlcEventMapper.isEarlyEnd(1_200_000, 5_400_000))
        assertTrue(VlcEventMapper.isEarlyEnd(5_369_999, 5_400_000))
        assertFalse(VlcEventMapper.isEarlyEnd(5_370_000, 5_400_000))
        assertFalse(VlcEventMapper.isEarlyEnd(5_400_000, 5_400_000))
        for (position in listOf(0L, 1_200_000L, 9_999_999L)) {
            assertFalse(VlcEventMapper.isEarlyEnd(position, 0))
            assertFalse(VlcEventMapper.isEarlyEnd(position, -1))
        }
    }
}
