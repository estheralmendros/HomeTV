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

/** `filesDir/home_cache/{profileId}.json`. */
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
        dir.setWritable(true)
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
    fun `an old cache file that still has next_up reads without failing`() = runTest {
        val legacy = Fixtures.home.replace("\"recently_added\": [", "\"next_up\": [ { \"type\": \"episode\", \"id\": 346, \"show_id\": 7, \"show_title\": \"The Office\", \"season_number\": 2, \"episode_number\": 4 } ], \"recently_added\": [")
        File(dir, "5.json").writeText("""{ "home": $legacy, "films": [], "shows": [] }""")
        val read = cache.read(5)!!
        assertEquals(listOf("film:12", "episode:345"), read.home.continue_watching.map { it.key })
        assertEquals(listOf("show:7"), read.home.recently_added.filterNot { it is com.hometv.tv.core.api.UnknownSummary }.map { it.key })
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
    fun `a failing disk write (e g full or read-only) is logged and swallowed, the old cache stays`() = runTest {
        val logged = mutableListOf<String>()
        val cache = HomeCache(dir, Dispatchers.Unconfined, log = { msg, _ -> logged += msg })
        cache.write(5, payload)
        assertTrue(dir.setWritable(false))
        cache.write(5, payload.copy(films = emptyList()))
        cache.remove("film", 12)
        assertEquals(payload, cache.read(5))
        assertEquals(2, logged.size)
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
