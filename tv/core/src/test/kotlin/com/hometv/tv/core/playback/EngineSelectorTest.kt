package com.hometv.tv.core.playback

import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.VideoInfo
import com.hometv.tv.core.playback.PlaybackEngine.EXO
import com.hometv.tv.core.playback.PlaybackEngine.VLC
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The "Engine decision" table of prd.md (AC-1.1..AC-1.6). */
class EngineSelectorTest {
    private fun hw(mime: String, w: Int = 1920, h: Int = 1080) = VideoDecoder(mime, hardware = true, maxWidth = w, maxHeight = h)

    /** Core-local inventory: hardware h264 + hevc, 1920×1080 (`FakeCodecs` lives in `app`). */
    private val inventory = CodecInventory(video = listOf(hw(Mimes.VIDEO_H264), hw(Mimes.VIDEO_HEVC)), audioDecoderMimes = emptySet())
    private val route = AudioRouteCaps(emptySet(), 2)
    private val off = EngineInputs(inventory, route, vlcAvailable = false)
    private val on = off.copy(vlcAvailable = true)

    private val mpeg4Avi = F.file(1, video = VideoInfo("mpeg4", 720, 400), container = "avi", fileName = "film.avi")
    private val h264Avi = F.file(2, container = "avi", fileName = "film.avi")
    private val h264M2ts = F.file(3, container = "ts", fileName = "film.m2ts")
    private val h264Mkv = F.file(4)
    private val hevcUhd = F.file(5, video = VideoInfo("hevc", 3840, 2160))
    private val noVideo = F.file(6, video = null)
    private val probeError = F.file(7, probeError = "ffprobe failed")

    private val tableRows = listOf(mpeg4Avi, h264Avi, h264M2ts, h264Mkv, hevcUhd, noVideo, probeError)
    private val flags = listOf(false to false, true to false, false to true, true to true)

    private fun exo(inputs: EngineInputs, file: MediaFile, history: EngineHistory = EngineHistory()) =
        EngineChoice(EXO, EngineSelector.exoCaps(inputs, file, history))

    @Test
    fun `exoCaps are today's ClientCapsBuilder caps for the file and its exclusions`() {
        val history = EngineHistory(excludeVideo = setOf("hevc"), excludeAudio = setOf("aac"), excludeContainers = setOf("mkv"))
        assertEquals(
            ClientCapsBuilder.build(inventory, route, h264Mkv, setOf("hevc"), setOf("aac"), setOf("mkv")),
            EngineSelector.exoCaps(off, h264Mkv, history),
        )
    }

    @Test
    fun `AC-1_1 libVLC unavailable - every row is EXO with exoCaps, whatever the flags`() {
        for (file in tableRows) {
            for ((force, failed) in flags) {
                val history = EngineHistory(exoFailed = failed)
                assertEquals(exo(off, file, history), EngineSelector.select(off.copy(forceVlc = force), file, history), "${file.id} $force $failed")
            }
        }
    }

    @Test
    fun `AC-1_2 a mpeg4 AVI goes to VLC with the VLC caps`() {
        val choice = EngineSelector.select(on, mpeg4Avi)
        assertEquals(VLC, choice.engine)
        assertEquals(VlcCapsBuilder.build(), choice.caps)
        assertTrue("avi" in choice.caps.containers)
        assertTrue("mpeg4" in choice.caps.video_codecs)
        assertFalse(choice.caps.hls)
        assertEquals(8, choice.caps.max_audio_channels)
    }

    @Test
    fun `AC-1_3 H264 in AVI or M2TS stays on EXO with today's caps (HLS remux)`() {
        for (file in listOf(h264Avi, h264M2ts)) {
            val choice = EngineSelector.select(on, file)
            assertEquals(exo(on, file), choice)
            assertFalse("avi" in choice.caps.containers)
            assertTrue(choice.caps.hls)
        }
        assertFalse("ts" in EngineSelector.select(on, h264M2ts).caps.containers)
    }

    @Test
    fun `AC-1_4 HEVC 2160p on a 1080p hardware decoder goes to VLC`() {
        assertEquals(VLC, EngineSelector.select(on, hevcUhd).engine)
        val fhd = F.file(8, video = VideoInfo("HEVC", 1920, 1080))
        assertEquals(exo(on, fhd), EngineSelector.select(on, fhd))
    }

    @Test
    fun `AC-1_5 exoFailed or forceVlc move an H264 MKV to VLC`() {
        assertEquals(EngineChoice(VLC, VlcCapsBuilder.build()), EngineSelector.select(on, h264Mkv, EngineHistory(exoFailed = true)))
        assertEquals(EngineChoice(VLC, VlcCapsBuilder.build()), EngineSelector.select(on.copy(forceVlc = true), h264Mkv))
        assertEquals(exo(on, h264Mkv), EngineSelector.select(on, h264Mkv))
    }

    @Test
    fun `AC-1_6 no video stream or a probe error stays on EXO unless forceVlc`() {
        for (file in listOf(noVideo, probeError)) {
            for (failed in listOf(EngineHistory(), EngineHistory(exoFailed = true))) {
                assertEquals(exo(on, file, failed), EngineSelector.select(on, file, failed))
            }
            assertEquals(VLC, EngineSelector.select(on.copy(forceVlc = true), file).engine)
        }
    }

    @Test
    fun `an audio exclusion keeps the engine, VLC caps ignore the exclusions`() {
        val history = EngineHistory(excludeAudio = setOf("aac"))
        assertEquals(EXO, EngineSelector.select(on, h264Mkv, history).engine)
        assertEquals(VlcCapsBuilder.build(), EngineSelector.select(on, mpeg4Avi, history.copy(excludeVideo = setOf("mpeg4"))).caps)
    }

    @Test
    fun `capsForFile and exoCapsForFile`() {
        val any = EngineSelector.capsForFile(on) { EngineHistory() }
        val exoTier = EngineSelector.exoCapsForFile(on) { EngineHistory() }
        assertEquals(VlcCapsBuilder.build(), any.capsFor(mpeg4Avi))
        assertEquals(EngineSelector.exoCaps(on, h264Mkv), any.capsFor(h264Mkv))
        assertEquals(EngineSelector.exoCaps(on, mpeg4Avi), exoTier.capsFor(mpeg4Avi))
        val failed = EngineSelector.capsForFile(on) { f -> EngineHistory(exoFailed = f.id == h264Mkv.id) }
        assertEquals(VlcCapsBuilder.build(), failed.capsFor(h264Mkv))
    }
}
