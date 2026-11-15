package com.hometv.tv.ui.player

import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.data.InFlightPlayback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Orphaned `POST .../playback` requests and the HLS DELETEs, in `appScope` with a timeout. */
class HlsSessionOwnerTest {
    private val deleted = mutableListOf<String>()

    private fun response(sessionId: String?) = Result.success(PlaybackResponse("hls", url = "/api/hls/$sessionId/index.m3u8", session_id = sessionId))

    @Test
    fun `an orphaned POST that answers with a session is DELETEd, in order, and the next request waits for it`() = runTest {
        val owner = HlsSessionOwner(backgroundScope, delete = { deleted += it })
        val first = CompletableDeferred<Result<PlaybackResponse>>()
        val second = CompletableDeferred<Result<PlaybackResponse>>()
        owner.adopt(InFlightPlayback(first))
        owner.adopt(InFlightPlayback(second))
        var waited = false
        launch { owner.awaitOrphans(); waited = true }
        runCurrent()
        second.complete(response("S2"))
        runCurrent()
        assertEquals(emptyList<String>(), deleted, "the second orphan waits for the first")
        assertEquals(false, waited)
        first.complete(response("S1"))
        advanceUntilIdle()
        assertEquals(listOf("S1", "S2"), deleted)
        assertTrue(waited)
    }

    @Test
    fun `a direct answer has nothing to DELETE and a hung orphan stops blocking after 45 s`() = runTest {
        val owner = HlsSessionOwner(backgroundScope, delete = { deleted += it })
        owner.adopt(InFlightPlayback(CompletableDeferred(response(null))))
        owner.awaitOrphans()
        assertEquals(emptyList<String>(), deleted)

        owner.adopt(InFlightPlayback(CompletableDeferred()))
        var waited = false
        launch { owner.awaitOrphans(); waited = true }
        advanceTimeBy(PlayerViewModel.ORPHAN_WAIT_MS - 1)
        assertEquals(false, waited)
        advanceTimeBy(2)
        assertTrue(waited)
    }

    @Test
    fun `a DELETE that hangs is abandoned after the final timeout`() = runTest {
        var cancelled = false
        val owner = HlsSessionOwner(backgroundScope, delete = { try { awaitCancellation() } finally { cancelled = true } })
        owner.delete("S9")
        advanceTimeBy(PlayerViewModel.FINAL_TIMEOUT_MS + 1)
        assertTrue(cancelled)
    }
}
