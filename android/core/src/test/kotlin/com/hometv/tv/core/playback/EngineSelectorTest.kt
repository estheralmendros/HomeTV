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

    /** A device that reports a hardware MPEG-4 Part 2 decoder (the production TV of the AVI fix). */
    private val mp4vInventory = inventory.copy(video = inventory.video + hw(Mimes.VIDEO_MP4V))
    private val mp4vOn = on.copy(inventory = mp4vInventory)
    private val mpeg4Mkv = F.file(9, video = VideoInfo("mpeg4", 720, 400))
    private val wmvAsf = F.file(10, video = VideoInfo("wmv3", 720, 400), container = "asf", fileName = "film.wmv")

    private val tableRows = listOf(mpeg4Avi, h264Avi, h264M2ts, h264Mkv, hevcUhd, noVideo, probeError, mpeg4Mkv, wmvAsf)
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
    fun `AC-1_3 H264 in M2TS stays on EXO with today's caps (HLS remux), H264 in AVI goes to VLC (AC-1_16)`() {
        val choice = EngineSelector.select(on, h264M2ts)
        assertEquals(exo(on, h264M2ts), choice)
        assertFalse("ts" in choice.caps.containers)
        assertTrue(choice.caps.hls)
        assertEquals(EngineChoice(VLC, VlcCapsBuilder.build()), EngineSelector.select(on, h264Avi))
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

    // ---- Addendum FR-1.4: legacy containers and codecs go to libVLC up front ----

    @Test
    fun `AC-1_16 a legacy container goes to VLC whatever its video codec, modern containers stay on EXO`() {
        for (container in listOf("avi", "asf", "flv", "rm", "mpeg", "AVI")) {
            val file = F.file(20, container = container, fileName = "film.$container")
            assertEquals(EngineChoice(VLC, VlcCapsBuilder.build()), EngineSelector.select(on, file), container)
            assertEquals(exo(off, file), EngineSelector.select(off, file), "$container without libVLC")
        }
        for ((container, name) in listOf("mkv" to "film.mkv", "mp4" to "film.mp4", "webm" to "film.webm", "ts" to "film.ts", "ts" to "film.m2ts")) {
            val file = F.file(21, container = container, fileName = name)
            assertEquals(exo(on, file), EngineSelector.select(on, file), name)
        }
    }

    @Test
    fun `AC-1_17 a legacy video codec goes to VLC even when the device reports a decoder for it`() {
        // "Annie (1982).avi": mpeg4 Part 2 in AVI on a TV with a hardware video/mp4v-es decoder.
        assertTrue("mpeg4" in EngineSelector.exoCaps(mp4vOn, mpeg4Avi).video_codecs)
        assertEquals(EngineChoice(VLC, VlcCapsBuilder.build()), EngineSelector.select(mp4vOn, mpeg4Avi))
        assertEquals(EngineChoice(VLC, VlcCapsBuilder.build()), EngineSelector.select(mp4vOn, mpeg4Mkv))
        val mp4vOff = mp4vOn.copy(vlcAvailable = false)
        for (codec in listOf("mpeg4", "MPEG4", "msmpeg4v1", "msmpeg4v2", "msmpeg4v3", "wmv1", "wmv2", "wmv3", "h263", "flv1", "rv30", "rv40")) {
            val file = F.file(22, video = VideoInfo(codec, 720, 400))
            assertEquals(VLC, EngineSelector.select(mp4vOn, file).engine, codec)
            assertEquals(exo(mp4vOff, file), EngineSelector.select(mp4vOff, file), "$codec without libVLC")
        }
        val mpeg2On = mp4vOn.copy(inventory = mp4vInventory.copy(video = mp4vInventory.video + hw(Mimes.VIDEO_MPEG2)))
        for (codec in listOf("h264", "hevc", "mpeg2video")) {
            assertEquals(EXO, EngineSelector.select(mpeg2On, F.file(23, video = VideoInfo(codec, 1280, 720))).engine, codec)
        }
    }

    @Test
    fun `AC-1_18 a legacy file is not in the Exo tier when libVLC is available, the any tier plays it on VLC`() {
        val exoTier = EngineSelector.exoCapsForFile(mp4vOn) { EngineHistory() }
        val anyTier = EngineSelector.capsForFile(mp4vOn) { EngineHistory() }
        for (file in listOf(mpeg4Avi, h264Avi, mpeg4Mkv, wmvAsf)) {
            assertFalse(VersionSelector.isPlayable(file, exoTier), "${file.id} exo tier")
            assertTrue(VersionSelector.isPlayable(file, anyTier), "${file.id} any tier")
        }
        assertEquals(EngineSelector.exoCaps(mp4vOn, h264Mkv), exoTier.capsFor(h264Mkv))
        assertEquals(EngineSelector.exoCaps(mp4vOn, h264M2ts), exoTier.capsFor(h264M2ts))
        // Without libVLC the Exo tier is today's caps for every file.
        val offTier = EngineSelector.exoCapsForFile(mp4vOn.copy(vlcAvailable = false)) { EngineHistory() }
        for (file in tableRows) assertEquals(EngineSelector.exoCaps(mp4vOn, file), offTier.capsFor(file), "${file.id}")
    }
}
