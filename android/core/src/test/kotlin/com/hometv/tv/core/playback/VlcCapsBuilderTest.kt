package com.hometv.tv.core.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AC-1.7: the VLC caps claim every container and codec name the server probe can emit. */
class VlcCapsBuilderTest {
    private val caps = VlcCapsBuilder.build()

    @Test
    fun `every container the server _container returns literally is claimed`() {
        for (container in listOf("mkv", "webm", "mp4", "avi", "ts")) assertTrue(container in caps.containers, container)
    }

    @Test
    fun `the listed ffprobe tokens are claimed`() {
        for (container in listOf("mpeg", "mpegvideo", "asf", "flv", "ogg", "mov", "matroska", "m2ts", "3gp", "nut", "rm", "dv", "wtv")) {
            assertTrue(container in caps.containers, container)
        }
        for (codec in listOf("h264", "hevc", "mpeg4", "msmpeg4v3", "mpeg2video", "vc1", "wmv3", "theora", "prores")) {
            assertTrue(codec in caps.video_codecs, codec)
        }
        for (codec in listOf("aac", "mp3", "ac3", "eac3", "dts", "truehd", "flac", "opus", "vorbis", "wmav2", "pcm_s16le", "pcm_bluray")) {
            assertTrue(codec in caps.audio_codecs, codec)
        }
    }

    @Test
    fun `never an HLS session, any audio track, 8 channels`() {
        assertFalse(caps.hls)
        assertTrue(caps.can_select_audio_track)
        assertEquals(8, caps.max_audio_channels)
    }

    @Test
    fun `the lists are fixed and have no duplicates`() {
        assertEquals(VlcCapsBuilder.build(), caps)
        for (list in listOf(caps.containers, caps.video_codecs, caps.audio_codecs)) assertEquals(list.distinct(), list)
        assertEquals(VlcCapsBuilder.CONTAINERS, caps.containers)
        assertEquals(VlcCapsBuilder.VIDEO_CODECS, caps.video_codecs)
        assertEquals(VlcCapsBuilder.AUDIO_CODECS, caps.audio_codecs)
    }
}
