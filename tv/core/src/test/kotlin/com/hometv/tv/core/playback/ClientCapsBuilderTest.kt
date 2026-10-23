package com.hometv.tv.core.playback

import com.hometv.tv.core.api.ApiJson
import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.playback.AudioEncodings.DTS
import com.hometv.tv.core.playback.AudioEncodings.E_AC3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientCapsBuilderTest {
    private fun hw(mime: String, w: Int = 3840, h: Int = 2160) = VideoDecoder(mime, hardware = true, maxWidth = w, maxHeight = h)
    private fun sw(mime: String) = VideoDecoder(mime, hardware = false, maxWidth = 4096, maxHeight = 2304)

    private val streamer = CodecInventory(
        video = listOf(hw(Mimes.VIDEO_H264), hw(Mimes.VIDEO_HEVC), hw(Mimes.VIDEO_VP9), hw(Mimes.VIDEO_AV1)),
        audioDecoderMimes = setOf("audio/mp4a-latm", Mimes.AUDIO_OPUS, Mimes.AUDIO_VORBIS, Mimes.AUDIO_FLAC, Mimes.AUDIO_AC3, Mimes.AUDIO_E_AC3),
    )
    private val chromecastHd = CodecInventory(
        video = listOf(hw(Mimes.VIDEO_H264, 1920, 1080), hw(Mimes.VIDEO_HEVC, 1920, 1080), hw(Mimes.VIDEO_VP9, 1920, 1080)),
        audioDecoderMimes = setOf(Mimes.AUDIO_OPUS, Mimes.AUDIO_VORBIS, Mimes.AUDIO_FLAC),
    )
    private val minimalApi26 = CodecInventory(video = listOf(hw(Mimes.VIDEO_H264, 1920, 1080)), audioDecoderMimes = setOf(Mimes.AUDIO_VORBIS))
    private val softwareHevc = CodecInventory(video = listOf(hw(Mimes.VIDEO_H264), sw(Mimes.VIDEO_HEVC), sw(Mimes.VIDEO_AV1)), audioDecoderMimes = emptySet())

    private val stereo = AudioRouteCaps(emptySet(), 2)
    private val eac3Passthrough = AudioRouteCaps(setOf(E_AC3), 6)

    private fun json(caps: ClientCaps) = ApiJson.encodeToString(ClientCaps.serializer(), caps)

    @Test
    fun `Google TV Streamer`() {
        assertEquals(
            """{"containers":["mp4","mkv","webm","ts"],"video_codecs":["h264","hevc","vp9","av1"],""" +
                """"audio_codecs":["aac","mp3","pcm_s16le","opus","vorbis","flac","ac3","eac3"],"max_audio_channels":6,"hls":true,"can_select_audio_track":true}""",
            json(ClientCapsBuilder.build(streamer, stereo)),
        )
    }

    @Test
    fun `Chromecast HD with EAC3 passthrough`() {
        assertEquals(
            """{"containers":["mp4","mkv","webm","ts"],"video_codecs":["h264","hevc","vp9"],""" +
                """"audio_codecs":["aac","mp3","pcm_s16le","opus","vorbis","flac","eac3"],"max_audio_channels":6,"hls":true,"can_select_audio_track":true}""",
            json(ClientCapsBuilder.build(chromecastHd, eac3Passthrough)),
        )
    }

    @Test
    fun `minimal API 26 device`() {
        assertEquals(
            """{"containers":["mp4","mkv","webm","ts"],"video_codecs":["h264"],""" +
                """"audio_codecs":["aac","mp3","pcm_s16le","vorbis"],"max_audio_channels":6,"hls":true,"can_select_audio_track":true}""",
            json(ClientCapsBuilder.build(minimalApi26, stereo)),
        )
    }

    @Test
    fun `software-only HEVC and AV1 are excluded, h264 is always present`() {
        val caps = ClientCapsBuilder.build(softwareHevc, stereo)
        assertEquals(listOf("h264"), caps.video_codecs)
        assertEquals(listOf("h264"), ClientCapsBuilder.build(CodecInventory(emptyList(), emptySet()), stereo).video_codecs)
    }

    @Test
    fun `mpeg2 and mpeg4 need a hardware decoder, vc1 never`() {
        val inventory = CodecInventory(listOf(hw(Mimes.VIDEO_H264), hw(Mimes.VIDEO_MPEG2), hw(Mimes.VIDEO_MP4V), hw("video/wvc1")), emptySet())
        assertEquals(listOf("h264", "mpeg2video", "mpeg4"), ClientCapsBuilder.build(inventory, stereo).video_codecs)
    }

    @Test
    fun `per-file refinement drops a codec the decoders cannot size`() {
        val uhd = F.hevc(1)
        val fhd = F.file(2, video = F.HEVC.copy(width = 1920, height = 1080))
        assertFalse("hevc" in ClientCapsBuilder.build(chromecastHd, stereo, forFile = uhd).video_codecs)
        assertTrue("hevc" in ClientCapsBuilder.build(chromecastHd, stereo, forFile = fhd).video_codecs)
        assertTrue("hevc" in ClientCapsBuilder.build(streamer, stereo, forFile = uhd).video_codecs)
        // Other codecs are untouched.
        assertEquals(listOf("h264", "vp9"), ClientCapsBuilder.build(chromecastHd, stereo, forFile = uhd).video_codecs)
        // A 4K H.264 file on a 1080p-only decoder drops h264 too (the server then answers unsupported).
        val uhdH264 = F.file(3, video = F.H264.copy(width = 3840, height = 2160))
        assertFalse("h264" in ClientCapsBuilder.build(chromecastHd, stereo, forFile = uhdH264).video_codecs)
        // Unknown size: no refinement.
        assertTrue("hevc" in ClientCapsBuilder.build(chromecastHd, stereo, forFile = F.file(4, video = F.HEVC.copy(width = null, height = null))).video_codecs)
    }

    @Test
    fun `refinement uses the decoder's own size check`() {
        val odd = CodecInventory(listOf(hw(Mimes.VIDEO_H264), VideoDecoder(Mimes.VIDEO_HEVC, true, 4096, 2160) { w, _ -> w % 16 == 0 }), emptySet())
        assertFalse("hevc" in ClientCapsBuilder.build(odd, stereo, forFile = F.file(1, video = F.HEVC.copy(width = 1918, height = 1080))).video_codecs)
        assertTrue("hevc" in ClientCapsBuilder.build(odd, stereo, forFile = F.file(1, video = F.HEVC.copy(width = 1920, height = 1080))).video_codecs)
    }

    @Test
    fun `max_audio_channels clamp`() {
        assertEquals(6, ClientCapsBuilder.build(streamer, AudioRouteCaps(emptySet(), 2)).max_audio_channels)
        assertEquals(6, ClientCapsBuilder.build(streamer, AudioRouteCaps(emptySet(), 6)).max_audio_channels)
        assertEquals(8, ClientCapsBuilder.build(streamer, AudioRouteCaps(emptySet(), 8)).max_audio_channels)
        assertEquals(8, ClientCapsBuilder.build(streamer, AudioRouteCaps(emptySet(), 12)).max_audio_channels)
    }

    @Test
    fun `the audio route changes audio_codecs with the same inventory`() {
        val passthrough = AudioRouteCaps(setOf(E_AC3, DTS, AudioEncodings.AC3, AudioEncodings.DOLBY_TRUEHD), 8)
        val withRoute = ClientCapsBuilder.build(chromecastHd, passthrough).audio_codecs
        val without = ClientCapsBuilder.build(chromecastHd, stereo).audio_codecs
        assertEquals(listOf("aac", "mp3", "pcm_s16le", "opus", "vorbis", "flac", "ac3", "eac3", "dts", "truehd"), withRoute)
        assertEquals(listOf("aac", "mp3", "pcm_s16le", "opus", "vorbis", "flac"), without)
    }

    @Test
    fun `DTS and TrueHD decoders count without passthrough, E-AC3 JOC passthrough counts as eac3`() {
        val decoders = CodecInventory(listOf(hw(Mimes.VIDEO_H264)), setOf(Mimes.AUDIO_DTS, Mimes.AUDIO_TRUEHD))
        assertTrue(ClientCapsBuilder.build(decoders, stereo).audio_codecs.containsAll(listOf("dts", "truehd")))
        val joc = ClientCapsBuilder.build(minimalApi26, AudioRouteCaps(setOf(AudioEncodings.E_AC3_JOC), 6)).audio_codecs
        assertTrue("eac3" in joc)
        assertFalse("ac3" in joc)
    }

    @Test
    fun `exclusions remove exactly that codec or container`() {
        val passthrough = AudioRouteCaps(setOf(DTS), 6)
        val caps = ClientCapsBuilder.build(streamer, passthrough)
        val noDts = ClientCapsBuilder.build(streamer, passthrough, excludeAudio = setOf("dts"))
        assertEquals(caps.audio_codecs - "dts", noDts.audio_codecs)
        assertEquals(caps.video_codecs, noDts.video_codecs)
        val noHevc = ClientCapsBuilder.build(streamer, passthrough, excludeVideo = setOf("hevc"))
        assertEquals(caps.video_codecs - "hevc", noHevc.video_codecs)
        assertEquals(caps.audio_codecs, noHevc.audio_codecs)
        assertEquals(listOf("mp4", "webm", "ts"), ClientCapsBuilder.build(streamer, passthrough, excludeContainers = setOf("mkv")).containers)
    }

    @Test
    fun `Blu-ray m2ts and mts files drop ts, plain ts and mkv keep it`() {
        fun containers(name: String) = ClientCapsBuilder.build(streamer, stereo, forFile = F.file(1, fileName = name, container = "ts")).containers
        assertFalse("ts" in containers("00001.m2ts"))
        assertFalse("ts" in containers("Movie.MTS"))
        assertTrue("ts" in containers("show.ts"))
        assertTrue("ts" in containers("film.mkv"))
    }

    @Test
    fun `cross-check - the Streamer caps equal prd md TV example caps plus av1 and pcm_s16le`() {
        val tvExample = ClientCaps(
            containers = listOf("mp4", "mkv", "webm", "ts"),
            video_codecs = listOf("h264", "hevc", "vp9"),
            audio_codecs = listOf("aac", "ac3", "eac3", "mp3", "opus", "vorbis", "flac"),
            max_audio_channels = 6, hls = true, can_select_audio_track = true,
        )
        val caps = ClientCapsBuilder.build(streamer, stereo)
        assertEquals(tvExample.containers.toSet(), caps.containers.toSet())
        assertEquals(tvExample.video_codecs.toSet() + "av1", caps.video_codecs.toSet())
        assertEquals(tvExample.audio_codecs.toSet() + "pcm_s16le", caps.audio_codecs.toSet())
        assertEquals(tvExample.copy(containers = caps.containers, video_codecs = caps.video_codecs, audio_codecs = caps.audio_codecs), caps)
    }
}
