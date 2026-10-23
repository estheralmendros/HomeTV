package com.hometv.tv.playback

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubtitleFileCacheTest {
    private val root: File = Files.createTempDirectory("subtitles").toFile()

    @Test
    fun `files live at subtitles mediaFileId trackId vtt and are returned as file URIs`() {
        val cache = SubtitleFileCache(root)
        assertNull(cache.get(91, "emb-3"))
        val uri = cache.put(91, "emb-3", "WEBVTT\n".toByteArray())
        assertEquals("file://" + File(root, "91/emb-3.vtt").absolutePath, uri)
        assertEquals(uri, cache.get(91, "emb-3"))
        assertEquals("WEBVTT\n", File(root, "91/emb-3.vtt").readText())
        assertFalse(File(root, "91/emb-3.vtt.tmp").exists())
        cache.put(91, "emb-3", "WEBVTT\n\n1\n".toByteArray())
        assertEquals("WEBVTT\n\n1\n", File(root, "91/emb-3.vtt").readText())
        cache.remove(91, "emb-3")
        assertNull(cache.get(91, "emb-3"))
    }

    @Test
    fun `clear empties the cache (app start)`() {
        val cache = SubtitleFileCache(root)
        cache.put(1, "ext-1", ByteArray(10))
        cache.clear()
        assertNull(cache.get(1, "ext-1"))
        assertEquals(0, cache.sizeBytes())
    }

    @Test
    fun `over the cap the oldest files of other media files go first, never the current file's`() {
        val cache = SubtitleFileCache(root, capBytes = 250)
        cache.put(1, "a", ByteArray(100))
        File(root, "1/a.vtt").setLastModified(1_000)
        cache.put(2, "b", ByteArray(100))
        File(root, "2/b.vtt").setLastModified(2_000)
        cache.put(3, "c", ByteArray(100))
        assertNull(cache.get(1, "a"))
        assertTrue(cache.get(2, "b") != null)
        assertTrue(cache.get(3, "c") != null)
        cache.put(3, "d", ByteArray(200))
        assertNull(cache.get(2, "b"))
        assertTrue(cache.get(3, "c") != null && cache.get(3, "d") != null) // over the cap, but the playing file keeps its tracks
    }

    @Test
    fun `track ids are sanitized into file names`() {
        val cache = SubtitleFileCache(root)
        cache.put(5, "../x", ByteArray(1))
        assertTrue(File(root, "5/.._x.vtt").isFile)
    }
}
