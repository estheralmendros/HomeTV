package com.hometv.tv.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.playback.MediaItemSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** prd3 Playback Flow rules 4–5 and 10. */
@RunWith(AndroidJUnit4::class)
class ExoTrackMapperTest {
    private fun group(format: Format, selected: Boolean = false, supported: Boolean = true) = Tracks.Group(
        TrackGroup(format),
        false,
        intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE),
        booleanArrayOf(selected),
    )

    private val video = group(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(), selected = true)
    private val dts = group(Format.Builder().setId("1").setSampleMimeType(MimeTypes.AUDIO_DTS).build(), selected = true)
    private val ac3 = group(Format.Builder().setId("2").setSampleMimeType(MimeTypes.AUDIO_AC3).build())
    private val containerSrt = group(Format.Builder().setId("3").setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setSelectionFlags(C.SELECTION_FLAG_FORCED).build())
    private val pgs = group(Format.Builder().setId("4").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(MimeTypes.APPLICATION_PGS).build())
    private val sideloaded = group(Format.Builder().setId("emb-3").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(MimeTypes.TEXT_VTT).build())
    private val tracks = Tracks(listOf(video, dts, ac3, containerSrt, pgs, sideloaded))
    private val spec = MediaItemSpec("https://x/api/media-files/1/stream", "video/x-matroska", audioTrackIndices = listOf(1, 2), imageSubtitleIds = listOf("emb-4"))

    @Test
    fun `audio groups match the server tracks by ordinal`() {
        assertSame(ac3, ExoTrackMapper.audioGroup(tracks, 2, spec))
        assertSame(dts, ExoTrackMapper.audioGroup(tracks, 1, spec))
        assertNull(ExoTrackMapper.audioGroup(tracks, 9, spec))
        assertNull(ExoTrackMapper.audioGroup(tracks, null, spec))
    }

    @Test
    fun `HLS has a single audio group`() {
        val hls = Tracks(listOf(video, ac3))
        assertSame(ac3, ExoTrackMapper.audioGroup(hls, 5, MediaItemSpec("https://x/api/hls/S1/index.m3u8", MimeTypes.APPLICATION_M3U8)))
    }

    @Test
    fun `side-loaded text matches the Format id, image tracks match by ordinal, container text never`() {
        assertSame(sideloaded, ExoTrackMapper.subtitleGroup(tracks, "emb-3", spec))
        assertSame(pgs, ExoTrackMapper.subtitleGroup(tracks, "emb-4", spec))
        assertNull(ExoTrackMapper.subtitleGroup(tracks, "emb-9", spec))
        val prefixed = Tracks(listOf(group(Format.Builder().setId("1:emb-3").setSampleMimeType(MimeTypes.TEXT_VTT).build())))
        assertTrue(ExoTrackMapper.subtitleGroup(prefixed, "emb-3", spec) != null)
    }

    @Test
    fun `parameters pin the audio group and enable text only with an explicit override`() {
        val base = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        val off = ExoTrackMapper.parameters(base, tracks, audioIndex = 2, subtitleId = null, spec = spec)
        assertTrue(C.TRACK_TYPE_TEXT in off.disabledTrackTypes)
        assertEquals(listOf(ac3.mediaTrackGroup), off.overrides.keys.toList())
        val on = ExoTrackMapper.parameters(off, tracks, audioIndex = 2, subtitleId = "emb-3", spec = spec)
        assertFalse(C.TRACK_TYPE_TEXT in on.disabledTrackTypes)
        assertEquals(setOf(ac3.mediaTrackGroup, sideloaded.mediaTrackGroup), on.overrides.keys)
        // Re-applying the same selection is a no-op (no new Tracks round).
        assertEquals(on, ExoTrackMapper.parameters(on, tracks, audioIndex = 2, subtitleId = "emb-3", spec = spec))
    }

    @Test
    fun `the TrackCheck input reflects supported and selected video and audio`() {
        val input = ExoTrackMapper.checkInput(tracks, fileHasVideo = true, expectedAudio = true)
        assertTrue(input.videoSupported && input.videoSelected && input.audioSupported && input.audioSelected)
        val silent = ExoTrackMapper.checkInput(Tracks(listOf(video, group(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).build(), supported = false))), true, true)
        assertFalse(silent.audioSupported)
        assertFalse(silent.audioSelected)
    }
}
