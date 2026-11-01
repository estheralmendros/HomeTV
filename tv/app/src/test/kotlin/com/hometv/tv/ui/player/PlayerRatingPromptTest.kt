package com.hometv.tv.ui.player

import com.hometv.tv.core.api.RatingPrompt
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.PlaybackErrorCodes
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.UrlKind
import com.hometv.tv.data.PendingPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * ratings prd3 FR-T6 / T-AC-1, 2, 3, 5a: the player holds the latest `rating_prompt` of its session
 * (every reporter, the released PUT) and hands it to `RatingPrompts` once, on exit or when cleared.
 */
class PlayerRatingPromptTest : PlayerTestBase() {
    private val dune = RatingPrompt("film", 12, "Dune")
    private val twoAudio = F.file(1, audio = listOf(F.audio(1, default = true), F.audio(2, codec = "dts", channels = 6, language = "spa")))

    private fun pending() = repos.ratingPrompts.pending.value

    @Test
    fun `a film end whose final PUT carries the prompt offers it after the exit`() = playerTest {
        api.onProgressPrompt = { body -> if (body.position_seconds == 7200.0) dune else null }
        val vm = startDirect()
        advance(10_000)
        assertNull(pending(), "a periodic report carried nothing")
        controller.end()
        runCurrent()
        assertEquals(listOf(PlayerEffect.Exit), exits)
        assertEquals(PendingPrompt(5, dune), pending())
        clear(vm)
        assertEquals(PendingPrompt(5, dune), repos.ratingPrompts.take(5))
        assertNull(pending(), "offered once")
    }

    @Test
    fun `a prompt from a periodic report is never offered while playing, only on exit with Back`() = playerTest {
        api.onProgressPrompt = { dune }
        val vm = startDirect()
        advance(10_000)
        assertNull(pending())
        vm.onBack() // hides the overlay
        vm.onBack()
        runCurrent()
        assertEquals(listOf(PlayerEffect.Exit), exits)
        assertEquals(PendingPrompt(5, dune), pending())
    }

    @Test
    fun `a final PUT answering after the exit offers its prompt at once`() = playerTest {
        val gate = CompletableDeferred<Unit>()
        var finalReport = false
        api.onProgressPrompt = { if (finalReport) dune.also { gate.await() } else null }
        val vm = startDirect()
        controller.positionMs = 6_600_000
        finalReport = true
        vm.onBack()
        vm.onBack()
        runCurrent()
        assertEquals(listOf(PlayerEffect.Exit), exits)
        assertNull(pending())
        gate.complete(Unit)
        runCurrent()
        assertEquals(PendingPrompt(5, dune), pending())
    }

    @Test
    fun `a prompt received before an HLS audio switch is still offered after the exit`() = playerTest {
        var promptNext = true
        api.onProgressPrompt = { if (promptNext) dune.also { promptNext = false } else null }
        val vm = startFilm(hls("S1"), twoAudio)
        advance(10_000)
        answerWith(hls("S2", audio = 2, action = "aac"))
        vm.onSelectAudio(2)
        runCurrent()
        controller.ready()
        runCurrent()
        advance(10_000)
        assertNull(pending())
        vm.onBack()
        vm.onBack()
        runCurrent()
        assertEquals(PendingPrompt(5, dune), pending())
    }

    @Test
    fun `a prompt received before a recovery is still offered after the exit`() = playerTest {
        var promptNext = true
        api.onProgressPrompt = { if (promptNext) dune.also { promptNext = false } else null }
        val vm = startHls("S1")
        advance(10_000)
        // The HLS session expired: a recovery request, a new attachment and a new reporter.
        answerWith(hls("S2"))
        controller.fail(PlayerFailure(PlaybackErrorCodes.IO_BAD_HTTP_STATUS, httpStatus = 404, urlKind = UrlKind.HLS, bodyCode = "session_not_found"))
        runCurrent()
        assertEquals(2, posts().size, "the recovery POST")
        controller.ready()
        runCurrent()
        advance(10_000)
        assertNull(pending())
        vm.onBack()
        vm.onBack()
        runCurrent()
        assertEquals(listOf(PlayerEffect.Exit), exits)
        assertEquals(PendingPrompt(5, dune), pending())
    }

    @Test
    fun `clearing the player without an exit hands the prompt off too`() = playerTest {
        api.onProgressPrompt = { dune }
        val vm = startDirect()
        advance(10_000)
        clear(vm)
        runCurrent()
        assertEquals(PendingPrompt(5, dune), pending())
    }

    @Test
    fun `no prompt, nothing offered`() = playerTest {
        val vm = startDirect()
        advance(10_000)
        controller.end()
        runCurrent()
        clear(vm)
        assertNull(pending())
    }
}
