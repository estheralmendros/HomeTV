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
    fun `AC-1_4 HEVC 2160p on a 1080p hardware decoder is converted by the server (EXO), not decoded by libVLC`() {
        assertEquals(exo(on, hevcUhd), EngineSelector.select(on, hevcUhd))
        assertFalse("hevc" in EngineSelector.select(on, hevcUhd).caps.video_codecs)
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

    // ---- Video conversion: conversion-only files (specs/video-transcoding/prd2.md FR-T4) ----

    private fun hwP(mime: String, vararg profiles: Int, w: Int = 3840, h: Int = 2160) =
        VideoDecoder(mime, hardware = true, maxWidth = w, maxHeight = h, profiles = profiles.toSet())

    /** A device with HEVC Main only and no AV1 decoder. */
    private val mainOnly = inventory.copy(video = listOf(hwP(Mimes.VIDEO_H264), hwP(Mimes.VIDEO_HEVC, CodecProfiles.HEVC_MAIN)))
    private val convOn = on.copy(inventory = mainOnly)
    private val hevc10 = F.file(30, video = VideoInfo("hevc", 1920, 1080, profile = "Main 10", bit_depth = 10))
    private val hevc8 = F.file(31, video = VideoInfo("hevc", 1920, 1080, profile = "Main", bit_depth = 8))
    private val av110 = F.file(32, video = VideoInfo("av1", 1920, 1080, bit_depth = 10))
    private val av1Avi = F.file(33, video = VideoInfo("av1", 1920, 1080, bit_depth = 10), container = "avi", fileName = "film.avi")
    private val high10 = F.file(34, video = VideoInfo("h264", 1920, 1080, profile = "High 10", bit_depth = 10))

    @Test
    fun `FR-T4 isConversionOnly table`() {
        val none = EngineHistory()
        assertTrue(EngineSelector.isConversionOnly(convOn, hevc10, none), "not claimed by the hardware")
        assertTrue(EngineSelector.isConversionOnly(convOn, av110, none), "no decoder at all")
        assertFalse(EngineSelector.isConversionOnly(convOn, hevc8, none), "claimed")
        assertFalse(EngineSelector.isConversionOnly(convOn, h264Mkv, none), "h264 8-bit is always claimed")
        assertFalse(EngineSelector.isConversionOnly(convOn, high10, none), "an AVC decoder that reports no profiles claims it")
        val avcNoHigh10 = convOn.copy(inventory = mainOnly.copy(video = listOf(hwP(Mimes.VIDEO_H264, 0x8))))
        assertTrue(EngineSelector.isConversionOnly(avcNoHigh10, high10, none), "High 10 without AVC_HIGH10")
        assertTrue(EngineSelector.isConversionOnly(convOn, hevc8, EngineHistory(excludeVideo = setOf("hevc"))), "excluded after a decoder failure")
        assertFalse(EngineSelector.isConversionOnly(convOn, av1Avi, none), "legacy container")
        assertFalse(EngineSelector.isConversionOnly(convOn, F.file(35, video = VideoInfo("mpeg4", 720, 400)), none), "legacy codec, and not a modern codec")
        assertFalse(EngineSelector.isConversionOnly(convOn.copy(forceVlc = true), hevc10, none), "forced")
        assertFalse(EngineSelector.isConversionOnly(convOn, F.file(36, video = VideoInfo("av1", 1920, 1080), probeError = "x"), none), "probe_error")
        assertFalse(EngineSelector.isConversionOnly(convOn, noVideo, none))
        assertFalse(EngineSelector.isConversionOnly(convOn, F.file(37, video = VideoInfo("vc1", 720, 400)), none), "not a modern codec")
        assertFalse(EngineSelector.isConversionOnly(convOn, F.file(38, video = VideoInfo("mpeg2video", 720, 400)), none), "not a modern codec")
        assertTrue(EngineSelector.isConversionOnly(convOn, F.file(39, video = VideoInfo("AV1", 1920, 1080, bit_depth = 10)), none), "case-insensitive")
    }

    @Test
    fun `FR-T4 a conversion-only file plays on EXO even with libVLC available`() {
        assertEquals(exo(convOn, av110), EngineSelector.select(convOn, av110))
        assertFalse("av1" in EngineSelector.select(convOn, av110).caps.video_codecs)
        assertEquals(exo(convOn, hevc10), EngineSelector.select(convOn, hevc10))
        // 4K HEVC on a 1080p-only decoder (the size rule)
        assertEquals(EXO, EngineSelector.select(on, hevcUhd).engine)
        // an excluded codec
        assertEquals(EXO, EngineSelector.select(convOn, hevc8, EngineHistory(excludeVideo = setOf("hevc"))).engine)
    }

    @Test
    fun `FR-T4 forceVlc, exoFailed, legacy files and old codecs keep going to VLC`() {
        assertEquals(VLC, EngineSelector.select(convOn.copy(forceVlc = true), av110).engine)
        assertEquals(VLC, EngineSelector.select(convOn, av110, EngineHistory(exoFailed = true)).engine)
        assertEquals(VLC, EngineSelector.select(convOn, av1Avi).engine)
        assertEquals(VLC, EngineSelector.select(convOn, F.file(40, video = VideoInfo("mpeg4", 720, 400))).engine)
        assertEquals(VLC, EngineSelector.select(convOn, F.file(41, video = VideoInfo("vc1", 720, 400))).engine)
        assertEquals(EXO, EngineSelector.select(off, av110).engine)
    }

    @Test
    fun `FR-T4 noFirstFrame goes to EXO with exoCaps, except a legacy file, and before the exoFailed and forced rules`() {
        val history = EngineHistory(excludeVideo = setOf("hevc"), noFirstFrame = true)
        assertEquals(exo(convOn, hevc8, history), EngineSelector.select(convOn, hevc8, history))
        assertEquals(exo(convOn, hevc8, history.copy(exoFailed = true)), EngineSelector.select(convOn, hevc8, history.copy(exoFailed = true)))
        assertEquals(VLC, EngineSelector.select(convOn, av1Avi, EngineHistory(noFirstFrame = true)).engine)
        assertEquals(VLC, EngineSelector.select(convOn.copy(forceVlc = true), hevc8, history).engine)
        assertEquals(EXO, EngineSelector.select(convOn, h264Mkv, EngineHistory(noFirstFrame = true)).engine)
    }

    @Test
    fun `FR-T4 convertCapsForFile adds the file's codec for a conversion-only file when h264 is claimed`() {
        val exoTier = EngineSelector.exoCapsForFile(convOn) { EngineHistory() }
        val convert = EngineSelector.convertCapsForFile(convOn) { EngineHistory() }
        assertFalse(VersionSelector.isPlayable(av110, exoTier))
        assertTrue(VersionSelector.isPlayable(av110, convert))
        assertTrue(VersionSelector.isPlayable(hevc10, convert))
        // the request caps never carry it
        assertFalse("av1" in EngineSelector.exoCaps(convOn, av110).video_codecs)
        // directly playable and legacy files are untouched
        assertEquals(exoTier.capsFor(hevc8), convert.capsFor(hevc8))
        assertEquals(exoTier.capsFor(av1Avi), convert.capsFor(av1Avi))
        assertFalse(VersionSelector.isPlayable(av1Avi, convert))
        // h264 not claimed for this file (High 10 without AVC High 10): no conversion output
        val noHigh10 = convOn.copy(inventory = convOn.inventory.copy(video = listOf(hwP(Mimes.VIDEO_H264, 0x8))))
        assertFalse(VersionSelector.isPlayable(high10, EngineSelector.convertCapsForFile(noHigh10) { EngineHistory() }))
        // without libVLC the tier still applies
        assertTrue(VersionSelector.isPlayable(av110, EngineSelector.convertCapsForFile(convOn.copy(vlcAvailable = false)) { EngineHistory() }))
    }
}
