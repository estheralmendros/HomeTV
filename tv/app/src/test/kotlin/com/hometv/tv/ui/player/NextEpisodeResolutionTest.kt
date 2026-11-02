package com.hometv.tv.ui.player

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.EpisodeDetail
import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.data.NextEpisodeResolver
import com.hometv.tv.core.playback.F
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Next-episode resolution retries, extracted from [PlayerViewModel] (the session behaviour is in `PlayerNextEpisodeTest`). */
class NextEpisodeResolutionTest {
    private var fetches = 0
    private var failing = true
    private val next = EpisodeDetail(id = 2, show_id = 7, show_title = "Dark", season_number = 1, episode_number = 2, media_files = listOf(F.file(20)))

    private fun TestScope.resolution() = NextEpisodeResolution(
        this,
        NextEpisodeResolver({ fetches++; if (failing) throw ApiException.Unreachable(java.io.IOException("down")) else next }),
        log = { _, _ -> },
    )

    private val current = object : NextEpisodeResolution.Current {
        override suspend fun ensureDetail() = true
        override fun nextEpisodeId(): Long = 2
        override suspend fun plan(episode: EpisodeDetail): List<MediaFile> = episode.media_files
    }

    @Test
    fun `a failure is retried while the caller keeps retrying, two attempts at most`() = runTest {
        val r = resolution()
        var resolved = 0
        r.start(current, F.file(10), keepRetrying = { true }) { resolved++ }
        advanceUntilIdle()
        assertEquals(2, fetches)
        assertEquals(NextState.Failed, r.state)
        assertEquals(1, resolved)
    }

    @Test
    fun `no retry when the caller stops retrying (the prefetch)`() = runTest {
        val r = resolution()
        r.start(current, F.file(10), keepRetrying = { false }) {}
        advanceUntilIdle()
        assertEquals(1, fetches)
    }

    @Test
    fun `the end screen retry is one last attempt, and a success is Found with its plan`() = runTest {
        val r = resolution()
        r.start(current, F.file(10), keepRetrying = { true }) {}
        advanceUntilIdle()
        r.prepareLastAttempt()
        r.start(current, F.file(10), keepRetrying = { true }) {}
        advanceUntilIdle()
        assertEquals(3, fetches)
        failing = false
        r.reset()
        r.start(current, F.file(10), keepRetrying = { true }) {}
        advanceUntilIdle()
        assertEquals(NextState.Found(next, next.media_files), r.state)
    }

    @Test
    fun `a running resolution is not started twice`() = runTest {
        val r = resolution()
        failing = false
        r.start(current, F.file(10), keepRetrying = { true }) {}
        r.start(current, F.file(10), keepRetrying = { true }) {}
        advanceUntilIdle()
        assertEquals(1, fetches)
        assertTrue(r.state is NextState.Found)
    }
}
