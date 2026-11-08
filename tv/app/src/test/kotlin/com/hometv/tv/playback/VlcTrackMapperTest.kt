package com.hometv.tv.playback

import com.hometv.tv.core.playback.MediaItemSpec
import com.hometv.tv.core.playback.SideloadedSubtitle
import com.hometv.tv.core.playback.TrackCheckInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** AC-7.3 / AC-7.4: server tracks ↔ libVLC ES ids, by ordinal, with the count guard. */
class VlcTrackMapperTest {
    private val spec = MediaItemSpec(
        "http://s/api/media-files/1/stream",
        null,
        subtitles = listOf(SideloadedSubtitle("s3", "file:///cache/s3.vtt", "spa", null, false)),
        audioTrackIndices = listOf(1, 2),
        imageSubtitleIds = listOf("s2"),
        embeddedSubtitleIds = listOf("s1", "s2"),
    )

    @Test
    fun `audio ids follow the server indices by ordinal`() {
        // ES ids are not ordinals: [5, 7] tells an id from a position.
        assertEquals(7, VlcTrackMapper.audioTrackId(listOf(5, 7), 2, spec))
        assertEquals(5, VlcTrackMapper.audioTrackId(listOf(5, 7), 1, spec))
        assertEquals(2, VlcTrackMapper.audioTrackId(listOf(1, 2), 2, spec))
    }

    @Test
    fun `audio count mismatch, unknown or null index gives null`() {
        assertNull(VlcTrackMapper.audioTrackId(listOf(5), 1, spec))
        assertNull(VlcTrackMapper.audioTrackId(listOf(5, 7, 9), 1, spec))
        assertNull(VlcTrackMapper.audioTrackId(listOf(5, 7), 3, spec))
        assertNull(VlcTrackMapper.audioTrackId(listOf(5, 7), null, spec))
    }

    @Test
    fun `spu ids are the embedded tracks then the slaves`() {
        val ids = listOf(3, 4, 5)
        assertEquals(3, VlcTrackMapper.spuTrackId(ids, "s1", spec))
        assertEquals(4, VlcTrackMapper.spuTrackId(ids, "s2", spec))
        assertEquals(5, VlcTrackMapper.spuTrackId(ids, "s3", spec))
        assertEquals(-1, VlcTrackMapper.spuTrackId(ids, null, spec))
        assertEquals(-1, VlcTrackMapper.spuTrackId(ids, "unknown", spec))
    }

    @Test
    fun `a missing slave disables every subtitle rather than pick a wrong one`() {
        for (id in listOf("s1", "s2", "s3", null)) assertEquals(-1, VlcTrackMapper.spuTrackId(listOf(3, 4), id, spec))
    }

    @Test
    fun `the track check input`() {
        assertEquals(TrackCheckInput(true, true, true, true, true, true), VlcTrackMapper.checkInput(1, listOf(5), 5, expectedAudio = true))
        assertEquals(TrackCheckInput(true, false, false, false, false, false), VlcTrackMapper.checkInput(0, emptyList(), -1, expectedAudio = false))
    }
}
