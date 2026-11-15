package com.hometv.tv.ui.player

import com.hometv.tv.R
import com.hometv.tv.core.api.VideoInfo
import com.hometv.tv.core.playback.F
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Next episode: same-file skips, matching versions and autoplay. */
class PlayerNextEpisodeTest : PlayerTestBase() {
    private val friends = F.file(7, duration = 2640.0, fileName = "Friends.S01E01E02.mkv")

    @Test
    fun `a multi-episode file skips the same-file episode and counts down to the one after`() = playerTest {
        episodes(
            episodeDetail(101, listOf(friends), next = 102, season = 1, episode = 1),
            episodeDetail(102, listOf(friends), next = 103, season = 1, episode = 2),
            episodeDetail(103, listOf(F.file(8, duration = 1320.0)), next = 104, season = 1, episode = 3, title = "The One with the Thumb"),
        )
        answerWith(direct(fileId = 7, duration = 2640.0))
        val vm = vm(itemType = "episode", itemId = 101)
        runCurrent()
        controller.ready()
        controller.positionMs = 2_620_000
        advance(1_000)
        assertEquals(listOf("GET /api/episodes/101", "GET /api/episodes/102", "GET /api/episodes/103"), api.calls.filter { it.startsWith("GET /api/episodes") })
        controller.end()
        runCurrent()
        val next = (vm.state.value.screen as PlayerScreenUi.Countdown).next
        assertEquals(com.hometv.tv.core.util.EpisodeCode(1, 3), next.code)
        answerWith(direct(fileId = 8, duration = 1320.0))
        vm.onPlayNow()
        runCurrent()
        assertEquals("POST /api/media-files/8/playback", posts().last())
    }

    @Test
    fun `when the same-file episode is the last one there is no countdown and the player exits`() = playerTest {
        episodes(episodeDetail(101, listOf(friends), next = 102), episodeDetail(102, listOf(friends), next = null))
        answerWith(direct(fileId = 7, duration = 2640.0))
        val vm = vm(itemType = "episode", itemId = 101)
        runCurrent()
        controller.ready()
        controller.end()
        runCurrent()
        assertEquals(SessionState.Exited, vm.session)
        assertEquals(listOf(PlayerEffect.Exit), exits)
    }

    @Test
    fun `eleven consecutive same-file episodes stop after 10 hops and count as the last one`() = playerTest {
        val chain = (101L..112L).map { id -> episodeDetail(id, listOf(friends), next = id + 1) }
        episodes(*chain.toTypedArray())
        answerWith(direct(fileId = 7, duration = 2640.0))
        val vm = vm(itemType = "episode", itemId = 101)
        runCurrent()
        controller.ready()
        controller.end()
        runCurrent()
        assertEquals(10, api.calls.count { it.startsWith("GET /api/episodes/") && it != "GET /api/episodes/101" })
        assertEquals(SessionState.Exited, vm.session)
    }

    @Test
    fun `the next episode starts on a matching playable version at its saved position`() = playerTest {
        codecs.inventory = codecs.inventory.copy(video = codecs.inventory.video.filter { it.mime == "video/avc" })
        val uhd = F.hevc(21, duration = 1320.0)
        val hd = F.file(22, duration = 1325.0)
        episodes(
            episodeDetail(203, listOf(F.file(1, duration = 1320.0)), next = 204),
            episodeDetail(204, listOf(uhd, hd), progress = F.progress(21, position = 300.0, duration = 1320.0)),
        )
        answerWith(direct(duration = 1320.0))
        val vm = vm(itemType = "episode", itemId = 203)
        runCurrent()
        controller.ready()
        controller.end()
        runCurrent()
        answerWith(direct(fileId = 22, duration = 1325.0))
        vm.onPlayNow()
        runCurrent()
        assertEquals("POST /api/media-files/22/playback", posts().last())
        assertEquals(300.0, bodies().last().start_position_seconds)
        controller.ready()
        assertNull(vm.state.value.chip)
    }

    @Test
    fun `a non-matching version starts at 0 with the different-version notice`() = playerTest {
        codecs.inventory = codecs.inventory.copy(video = codecs.inventory.video.filter { it.mime == "video/avc" })
        episodes(
            episodeDetail(203, listOf(F.file(1, duration = 1320.0)), next = 204),
            episodeDetail(204, listOf(F.hevc(21, duration = 1320.0), F.file(22, duration = 900.0)), progress = F.progress(21, position = 300.0, duration = 1320.0)),
        )
        answerWith(direct(duration = 1320.0))
        val vm = vm(itemType = "episode", itemId = 203)
        runCurrent()
        controller.ready()
        controller.end()
        runCurrent()
        answerWith(direct(fileId = 22, duration = 900.0))
        vm.onPlayNow()
        runCurrent()
        assertEquals(0.0, bodies().last().start_position_seconds)
        controller.ready()
        assertEquals(UiText.Res(R.string.player_different_version), vm.state.value.chip)
    }

    @Test
    fun `every candidate answering unsupported ends on the unplayable end screen with the last reason`() = playerTest {
        episodes(
            episodeDetail(203, listOf(F.file(1, duration = 1320.0)), next = 204),
            episodeDetail(204, listOf(F.file(21), F.file(22)), title = "Halloween", episode = 4),
        )
        answerWith(hls("S1", duration = 1320.0))
        val vm = vm(itemType = "episode", itemId = 203)
        runCurrent()
        controller.ready()
        controller.end()
        runCurrent()
        answerWith(unsupported("keyframes_too_sparse"))
        vm.onPlayNow()
        runCurrent()
        assertEquals(listOf("S1", null), bodies().takeLast(2).map { it.previous_session_id })
        val screen = vm.state.value.screen as PlayerScreenUi.EndScreen
        assertEquals(UiText.Res(R.string.next_unplayable), screen.message)
        val detail = screen.detail as UiText.Joined
        assertTrue(detail.parts.contains(UiText.Res(R.string.reason_keyframes_too_sparse)))
        assertEquals(emptyList<String>(), deletes())
    }

    @Test
    fun `the end screen reason of an unplayable next episode names the codec`() = playerTest {
        episodes(
            episodeDetail(203, listOf(F.file(1, duration = 1320.0)), next = 204),
            episodeDetail(204, listOf(F.file(21, video = VideoInfo("av1", 1920, 1080)))),
        )
        answerWith(direct(duration = 1320.0))
        val vm = vm(itemType = "episode", itemId = 203)
        runCurrent()
        controller.ready()
        controller.end()
        runCurrent()
        val detail = (vm.state.value.screen as PlayerScreenUi.EndScreen).detail as UiText.Joined
        assertTrue(detail.parts.contains(UiText.Res(R.string.reason_video_codec_unsupported, listOf("AV1"))))
    }
}
