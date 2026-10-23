package com.hometv.tv.core.playback

import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.playback.F.audio
import com.hometv.tv.core.playback.F.sub
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackPlannerTest {
    private val server = "https://hometv.tail1234.ts.net"
    private val spa = sub("emb-3", "spa")
    private val engForced = sub("ext-5", "eng", source = "external", forced = true)
    private val pgs = sub("emb-4", "eng", kind = "image")
    private val file = F.file(91, container = "mkv", audio = listOf(audio(1, "eac3", 6, "eng", default = true), audio(2, "ac3", 6, "spa")))

    @Test
    fun `direct - absolute stream url, container MIME, text and image subtitles`() {
        val plan = PlaybackPlanner.plan(
            PlaybackResponse(mode = "direct", url = "/api/media-files/91/stream", audio_stream_index = 1, duration_seconds = 9360.5, subtitle_tracks = listOf(spa, engForced, pgs)),
            server,
            file,
        ) as PlaybackPlan.Play
        assertEquals(PlaybackMode.DIRECT, plan.mode)
        assertEquals("$server/api/media-files/91/stream", plan.url)
        assertEquals("video/x-matroska", plan.mimeType)
        assertNull(plan.sessionId)
        assertEquals(1, plan.audioStreamIndex)
        assertEquals(9360.5, plan.durationSeconds)
        assertEquals(listOf("emb-3", "ext-5", "emb-4"), plan.subtitles.map { it.track.id })
        assertEquals("$server/api/media-files/91/subtitles/emb-3.vtt", plan.subtitles[0].url)
        assertNull(plan.subtitles[2].url)
        // Audio track 1 is English: the forced English track is the default.
        assertEquals("ext-5", plan.defaultSubtitleId)
        assertFalse(plan.audioConverted)
    }

    @Test
    fun `hls - playlist url, session, m3u8 MIME, image subtitles hidden, converted audio`() {
        val plan = PlaybackPlanner.plan(
            PlaybackResponse(
                mode = "hls", url = "/api/hls/5f2c/index.m3u8", session_id = "5f2c", audio_stream_index = 2, audio_action = "aac_5_1",
                duration_seconds = 9360.5, subtitle_tracks = listOf(spa, engForced, pgs.copy(default = true)),
            ),
            server,
            file,
        ) as PlaybackPlan.Play
        assertEquals(PlaybackMode.HLS, plan.mode)
        assertEquals("$server/api/hls/5f2c/index.m3u8", plan.url)
        assertEquals("application/x-mpegURL", plan.mimeType)
        assertEquals("5f2c", plan.sessionId)
        assertEquals(listOf("emb-3", "ext-5"), plan.subtitles.map { it.track.id })
        // Audio track 2 is Spanish: no forced Spanish track, the default PGS is hidden → Off.
        assertNull(plan.defaultSubtitleId)
        assertTrue(plan.audioConverted)
        assertFalse(plan.copy(audioAction = "copy").audioConverted)
    }

    @Test
    fun `duration_seconds null is accepted`() {
        val plan = PlaybackPlanner.plan(PlaybackResponse(mode = "direct", url = "/api/media-files/91/stream", audio_stream_index = null), server, file) as PlaybackPlan.Play
        assertNull(plan.durationSeconds)
        assertNull(plan.audioStreamIndex)
        assertNull(plan.defaultSubtitleId)
    }

    @Test
    fun `unsupported keeps the reason and the detail for logs`() {
        val plan = PlaybackPlanner.plan(PlaybackResponse(mode = "unsupported", reason = "keyframes_too_sparse", detail = "Segment of 45 s"), server, file)
        assertEquals(PlaybackPlan.Unsupported(UnsupportedReason.KEYFRAMES_TOO_SPARSE, "keyframes_too_sparse", "Segment of 45 s"), plan)
        val future = PlaybackPlanner.plan(PlaybackResponse(mode = "unsupported", reason = "needs_dolby_vision"), server, file)
        assertEquals(UnsupportedReason.UNKNOWN, (future as PlaybackPlan.Unsupported).reason)
    }

    @Test
    fun `an unknown mode or a missing url is treated as unsupported`() {
        assertEquals(UnsupportedReason.UNKNOWN, (PlaybackPlanner.plan(PlaybackResponse(mode = "dash", url = "/x"), server, file) as PlaybackPlan.Unsupported).reason)
        assertTrue(PlaybackPlanner.plan(PlaybackResponse(mode = "hls", url = null, session_id = "s"), server, file) is PlaybackPlan.Unsupported)
    }

    @Test
    fun `MIME by container`() {
        assertEquals("video/x-matroska", PlaybackPlanner.mimeForContainer("mkv"))
        assertEquals("video/mp4", PlaybackPlanner.mimeForContainer("mp4"))
        assertEquals("video/webm", PlaybackPlanner.mimeForContainer("webm"))
        assertEquals("video/mp2t", PlaybackPlanner.mimeForContainer("ts"))
        assertNull(PlaybackPlanner.mimeForContainer("avi"))
        assertNull(PlaybackPlanner.mimeForContainer(null))
    }

    @Test
    fun `every reason maps from its wire name`() {
        for (reason in UnsupportedReason.entries.filter { it.wire != null }) assertEquals(reason, UnsupportedReason.fromWire(reason.wire))
    }
}
