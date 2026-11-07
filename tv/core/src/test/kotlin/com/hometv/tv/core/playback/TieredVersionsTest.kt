package com.hometv.tv.core.playback

import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.VideoInfo
import com.hometv.tv.core.playback.F.HEVC
import com.hometv.tv.core.playback.F.caps
import com.hometv.tv.core.playback.F.file
import com.hometv.tv.core.playback.F.hevc
import com.hometv.tv.core.playback.F.hevcCaps
import com.hometv.tv.core.playback.F.part
import com.hometv.tv.core.playback.F.progress
import com.hometv.tv.core.playback.VersionGroup.Part
import com.hometv.tv.core.playback.VersionGroup.Standalone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Exo-first version tiers (AC-1.8..AC-1.10). */
class TieredVersionsTest {
    private val vs = VersionSelector
    private val tv = TieredVersions
    private fun ids(files: List<MediaFile>) = files.map { it.id }

    private val mpeg4 = VideoInfo("mpeg4", 720, 400)
    private val vlcCaps = VlcCapsBuilder.build()

    /** Exo tier = Chromecast-like caps; `any` = VLC caps for every file the Exo caps refuse (what `EngineSelector.capsForFile` does). */
    private val exoTier = CapsForFile.of(caps)
    private val anyTier = CapsForFile { f -> if (vs.isPlayable(f, caps)) caps else vlcCaps }
    private val tiers = VersionCaps(exoTier, anyTier)

    /** The `VersionSelectorTest` fixtures: file sets and progress values. */
    private val fileSets: List<List<MediaFile>> = listOf(
        listOf(file(1), file(2)),
        listOf(file(1), hevc(2)),
        listOf(hevc(1), file(2)),
        listOf(file(1, probeError = "x"), file(2)),
        listOf(file(1, video = null), file(2)),
        listOf(file(1, probeError = "x"), hevc(2), hevc(3)),
        listOf(file(1, probeError = "x"), file(2, probeError = "y")),
        emptyList(),
        listOf(part(1, 1), part(2, 2, duration = 3200.0)),
        listOf(file(9, video = HEVC, duration = 6200.0), part(1, 1), part(2, 2)),
        listOf(part(1, 1, video = HEVC), part(2, 1), part(3, 2)),
        listOf(part(1, 1, probeError = "x"), part(2, 1, video = HEVC), part(3, 2, video = HEVC)),
        listOf(part(1, 1, label = "Remux"), part(2, 2, video = HEVC, label = "Remux"), part(3, 2, label = "Web")),
        listOf(part(1, 1, video = HEVC, label = "2160p HEVC"), part(2, 2, video = HEVC, label = "2160p HEVC"), part(3, 2, label = "1080p H264")),
        listOf(hevc(1, duration = 9360.0), file(2, duration = 9372.0)),
        listOf(hevc(1, duration = 9360.0), file(2, duration = 8100.0)),
        listOf(file(10, duration = 3200.0), part(11, 1, duration = 3100.0), part(12, 2, video = HEVC, duration = 3200.0)),
        listOf(file(1, duration = 5000.0, size = 9), file(2, duration = 7210.0, size = 8), file(3, duration = 7200.0, size = 7)),
        listOf(file(501, duration = 1320.0)),
    )
    private val progresses: List<Progress?> = listOf(
        null, progress(2), progress(1, 2980.0, 3000.0), progress(1, 1500.0, 3000.0), progress(2, 3040.0, 3200.0),
        progress(9, 1200.0, 6200.0), progress(1, 3723.0, 9360.0), progress(12, 1000.0, 3200.0), progress(null, 600.0, 1300.0),
        progress(3, 1000.0, 7200.0), progress(2, watched = true),
    )
    private val groups = listOf(Standalone, Part(1), Part(2))

    @Test
    fun `AC-1_8 with exo == any every function returns what VersionSelector returns`() {
        for (c in listOf(caps, hevcCaps)) {
            val single = CapsForFile.of(c)
            val same = VersionCaps(single, single)
            for (files in fileSets) {
                for (p in progresses) {
                    assertEquals(vs.defaultChoice(files, single, p), tv.defaultChoice(files, same, p), "$files $p")
                    for (g in groups) {
                        assertEquals(vs.select(files, single, p, g), tv.select(files, same, p, g))
                        assertEquals(vs.candidates(files, single, g, p), tv.candidates(files, same, g, p))
                    }
                }
                for (current in files) assertEquals(vs.nextPart(files, single, current), tv.nextPart(files, same, current))
            }
        }
    }

    @Test
    fun `AC-1_9 H264 720p is preferred over a libVLC-only HEVC 2160p`() {
        val files = listOf(file(1, video = HEVC), file(2, video = F.H264.copy(height = 720)))
        assertEquals(2L, tv.defaultChoice(files, tiers, null)?.file?.id)
        assertEquals(2L, tv.select(files, tiers, null, Standalone)?.id)
        assertEquals(listOf(2L, 1L), ids(tv.candidates(files, tiers)))
        // VLC caps alone would take the 2160p file.
        assertEquals(1L, vs.defaultChoice(files, CapsForFile.of(vlcCaps), null)?.file?.id)
    }

    @Test
    fun `AC-1_10 a mpeg4-only episode is a candidate only with the VLC tier`() {
        val files = listOf(file(1, video = mpeg4, container = "avi"))
        assertEquals(listOf(1L), ids(tv.candidates(files, tiers)))
        assertTrue(tv.candidates(files, VersionCaps(exoTier, exoTier)).isEmpty())
        assertEquals(1L, tv.defaultChoice(files, tiers, null)?.file?.id)
    }

    @Test
    fun `a libVLC-only file is chosen when no version is Exo-playable`() {
        val files = listOf(hevc(1), file(2, video = mpeg4))
        assertEquals(vs.defaultChoice(files, anyTier, null), tv.defaultChoice(files, tiers, null))
        assertEquals(vs.select(files, anyTier, null, Standalone), tv.select(files, tiers, null, Standalone))
    }

    @Test
    fun `the next part prefers an Exo-playable file, else a libVLC-only one`() {
        val p1 = part(1, 1)
        val files = listOf(p1, part(2, 2, video = HEVC, label = p1.label), part(3, 2, label = "Web"))
        assertEquals(3L, tv.nextPart(files, tiers, p1)?.id)
        val vlcOnly = listOf(p1, part(4, 2, video = mpeg4, label = "Other"), part(5, 2, video = VideoInfo("cinepak", 320, 240), label = p1.label))
        assertEquals(5L, vs.nextPart(vlcOnly, exoTier, p1)?.id)
        assertEquals(4L, tv.nextPart(vlcOnly, tiers, p1)?.id)
        assertNull(tv.nextPart(files, tiers, files[2]))
    }

    // ---- review 1: a resume point on a libVLC-only version is kept ----------------------------------

    private val h264Film = file(1, duration = 7200.0)
    private val aviFilm = file(2, video = mpeg4, container = "avi", duration = 5400.0)
    private val resumeFiles = listOf(h264Film, aviFilm)

    @Test
    fun `progress on a libVLC-only version with no matching Exo version resumes it`() {
        val p = progress(2, 1200.0, 5400.0)
        val choice = tv.defaultChoice(resumeFiles, tiers, p)
        assertEquals(Choice(Standalone, aviFilm, startAtZero = false), choice)
        assertEquals(Start(1200.0, differentVersionNotice = false), vs.startFor(choice!!.file, resumeFiles, p))
        assertEquals(2L, tv.select(resumeFiles, tiers, p, Standalone)?.id)
        assertEquals(listOf(2L, 1L), ids(tv.candidates(resumeFiles, tiers, Standalone, p)), "next-episode plan starts with the same file")
    }

    @Test
    fun `no progress, or progress on the Exo-playable version, keeps the Exo-first choice`() {
        for (p in listOf(null, progress(1, 1200.0, 7200.0))) {
            assertEquals(1L, tv.defaultChoice(resumeFiles, tiers, p)?.file?.id, "$p")
            assertEquals(1L, tv.select(resumeFiles, tiers, p, Standalone)?.id, "$p")
            assertEquals(listOf(1L, 2L), ids(tv.candidates(resumeFiles, tiers, Standalone, p)), "$p")
        }
    }

    @Test
    fun `progress on a libVLC-only version with no resume point keeps the Exo-first choice`() {
        // Watched (starts over) or under the 30 s resume floor: nothing to keep.
        for (p in listOf(progress(2, 1200.0, 5400.0, watched = true), progress(2, 10.0, 5400.0))) {
            assertEquals(1L, tv.defaultChoice(resumeFiles, tiers, p)?.file?.id, "$p")
            assertEquals(1L, tv.select(resumeFiles, tiers, p, Standalone)?.id, "$p")
        }
    }

    @Test
    fun `progress on a libVLC-only version with a matching Exo version takes the Exo one at the same point`() {
        val files = listOf(file(1, duration = 5410.0), aviFilm)
        val p = progress(2, 1200.0, 5400.0)
        val choice = tv.defaultChoice(files, tiers, p)
        assertEquals(1L, choice?.file?.id)
        assertEquals(Start(1200.0, differentVersionNotice = false), vs.startFor(choice!!.file, files, p))
        assertEquals(1L, tv.select(files, tiers, p, Standalone)?.id)
    }

    @Test
    fun `a resume point on a libVLC-only part is kept within its group only`() {
        val files = listOf(part(1, 1), part(2, 1, video = mpeg4, duration = 2000.0), part(3, 2))
        val p = progress(2, 600.0, 2000.0)
        assertEquals(2L, tv.defaultChoice(files, tiers, p)?.file?.id)
        assertEquals(2L, tv.select(files, tiers, p, Part(1))?.id)
        assertEquals(3L, tv.select(files, tiers, p, Part(2))?.id)
    }
}
