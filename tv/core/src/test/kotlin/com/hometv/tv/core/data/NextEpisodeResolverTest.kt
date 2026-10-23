package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.EpisodeDetail
import com.hometv.tv.core.playback.F
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** prd.md §9 D12: the next episode skips episodes in the same multi-episode file, ≤ 10 hops. */
class NextEpisodeResolverTest {
    private val fetched = mutableListOf<Long>()

    private fun episode(id: Long, fileIds: List<Long>, next: Long?) = EpisodeDetail(
        id = id, show_id = 1, show_title = "Friends", season_number = 1, episode_number = id.toInt(),
        media_files = fileIds.map { F.file(it) }, next_episode_id = next,
    )

    private fun resolver(episodes: Map<Long, EpisodeDetail>, fail: Set<Long> = emptySet()) = NextEpisodeResolver({ id ->
        fetched += id
        if (id in fail) throw ApiException.Unreachable(IOException("down"))
        episodes[id] ?: throw ApiException.Http(404, "not_found", null, JsonObject(emptyMap()), null)
    })

    @Test
    fun `skips the episode that shares the playing file and returns the next one`() = runTest {
        // E01 plays Friends.S01E01E02.mkv (file 7), linked to E01 and E02.
        val r = resolver(mapOf(2L to episode(2, listOf(7), next = 3), 3L to episode(3, listOf(8), next = 4)))
        val result = r.resolve(nextEpisodeId = 2, playingFileId = 7)
        assertEquals(3L, (result as NextEpisodeResult.Found).episode.id)
        assertEquals(listOf(2L, 3L), fetched)
    }

    @Test
    fun `a plain next episode is returned after one request`() = runTest {
        val result = resolver(mapOf(2L to episode(2, listOf(9), next = 3))).resolve(2, playingFileId = 7)
        assertEquals(2L, (result as NextEpisodeResult.Found).episode.id)
        assertEquals(listOf(2L), fetched)
    }

    @Test
    fun `no next episode when the skipped candidate is the last one or there is none`() = runTest {
        assertEquals(NextEpisodeResult.None, resolver(mapOf(2L to episode(2, listOf(7), next = null))).resolve(2, 7))
        assertEquals(NextEpisodeResult.None, resolver(emptyMap()).resolve(null, 7))
        assertEquals(listOf(2L), fetched)
    }

    @Test
    fun `11 consecutive same-file episodes stop after 10 hops and count as the last episode`() = runTest {
        val chain = (2L..12L).associateWith { episode(it, listOf(7), next = it + 1) }
        assertEquals(NextEpisodeResult.None, resolver(chain).resolve(2, 7))
        assertEquals((2L..11L).toList(), fetched)
    }

    @Test
    fun `a failed hop is a failed prefetch`() = runTest {
        val r = resolver(mapOf(2L to episode(2, listOf(7), next = 3)), fail = setOf(3))
        assertTrue(r.resolve(2, 7) is NextEpisodeResult.Failed)
        val gone = resolver(emptyMap()).resolve(5, 7)
        assertEquals(404, ((gone as NextEpisodeResult.Failed).cause as ApiException.Http).status)
    }
}
