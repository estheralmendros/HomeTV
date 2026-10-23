package com.hometv.tv.data

import com.hometv.tv.core.api.ApiJson
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.Fixtures
import com.hometv.tv.core.api.HomeResponse
import com.hometv.tv.core.api.ShowSummary
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `filesDir/home_cache/{profileId}.json` (prd3 §3.6, Persistence). */
class HomeCacheTest {
    private val dir: File = Files.createTempDirectory("home_cache").toFile()
    private val cache = HomeCache(dir, Dispatchers.Unconfined)
    private val home = ApiJson.decodeFromString(HomeResponse.serializer(), Fixtures.home)
    private val payload = HomePayload(
        home = home,
        films = listOf(FilmSummary(12, "Dune", 2021), FilmSummary(13, "Alien", 1979)),
        shows = listOf(ShowSummary(7, "The Office", 2005, unwatched_episode_count = 150)),
    )

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `round trip keeps the polymorphic home rows and episode media files`() = runTest {
        cache.write(5, payload)
        val read = cache.read(5)!!
        assertEquals(payload, read)
        val episode = read.home.continue_watching[1] as EpisodeSummary
        assertEquals(listOf(91L, 92L), episode.media_files.map { it.id })
        assertTrue(File(dir, "5.json").isFile)
        assertEquals(listOf("5.json"), dir.list()!!.toList(), "no temp file left behind")
    }

    @Test
    fun `a missing or corrupt file reads as no cache`() = runTest {
        assertNull(cache.read(9))
        File(dir, "9.json").writeText("{ not json")
        assertNull(cache.read(9))
    }

    @Test
    fun `remove drops the item from every row of every profile`() = runTest {
        cache.write(5, payload)
        cache.write(6, payload)
        cache.remove("film", 12)
        cache.remove("show", 7)
        listOf(5L, 6L).forEach { profile ->
            val read = cache.read(profile)!!
            assertTrue(read.home.continue_watching.none { it.key == "film:12" })
            assertTrue(read.home.recently_added.none { it.key == "show:7" })
            assertTrue(read.home.continue_watching.none { it is EpisodeSummary && it.show_id == 7L }, "episodes of a gone show go too")
            assertEquals(listOf(13L), read.films.map { it.id })
            assertEquals(emptyList<Long>(), read.shows.map { it.id })
        }
    }

    @Test
    fun `clear deletes every profile's cache`() = runTest {
        cache.write(5, payload)
        cache.write(6, payload)
        cache.clear()
        assertNull(cache.read(5))
        assertNull(cache.read(6))
    }
}
