package com.hometv.tv.core.playback

import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.playback.F.H264
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** prd3 Capability Detection `VersionSelector`; prd.md §9 D10–D13, Rev 8 order. */
class VersionSelectorTest {
    private val vs = VersionSelector
    private fun ids(files: List<MediaFile>) = files.map { it.id }
    private fun uhd(id: Long, part: Int? = null, duration: Double? = 3000.0, size: Long = 20_000_000_000L) =
        file(id, video = HEVC, part = part, duration = duration, label = "2160p HEVC", size = size)
    private fun fhd(id: Long, part: Int? = null, duration: Double? = 3000.0, size: Long = 8_000_000_000L) =
        file(id, video = H264, part = part, duration = duration, label = "1080p H264", size = size)

    @Nested
    inner class Grouping {
        @Test
        fun `parts by part_number with versions height DESC, whatever the list order`() {
            val apiOrder = listOf(uhd(1, 1), fhd(2, 1), uhd(3, 2), fhd(4, 2))
            for (files in listOf(apiOrder, apiOrder.reversed(), listOf(apiOrder[3], apiOrder[0], apiOrder[2], apiOrder[1]))) {
                val g = vs.group(files)
                assertTrue(g.standalone.isEmpty())
                assertEquals(listOf(1, 2), g.parts.map { it.number })
                assertEquals(listOf(listOf(1L, 2L), listOf(3L, 4L)), g.parts.map { ids(it.versions) })
            }
        }

        @Test
        fun `standalone plus parts`() {
            val g = vs.group(listOf(fhd(2, 1), uhd(1), fhd(3, 2)))
            assertEquals(listOf(1L), ids(g.standalone))
            assertEquals(listOf(1, 2), g.parts.map { it.number })
            assertTrue(g.hasParts)
        }

        @Test
        fun `Rev 8 order - height DESC with null last, then size DESC, then id ASC`() {
            val files = listOf(
                file(5, video = null, size = 9),
                file(4, video = H264.copy(height = 720), size = 1),
                file(3, video = H264, size = 5),
                file(2, video = H264, size = 7),
                file(1, video = H264, size = 5),
                file(6, video = HEVC, size = 1),
                file(7, video = H264.copy(height = null), size = 10),
            )
            assertEquals(listOf(6L, 2L, 1L, 3L, 4L, 7L, 5L), ids(vs.group(files).standalone))
            assertEquals(Standalone, vs.groupOf(files[0]))
            assertEquals(Part(2), vs.groupOf(fhd(9, 2)))
        }

        @Test
        fun `isLastPart is true for a standalone file and for the highest part`() {
            val files = listOf(uhd(9), fhd(1, 1), fhd(2, 2), uhd(3, 2))
            assertTrue(vs.isLastPart(files[0], files))
            assertFalse(vs.isLastPart(files[1], files))
            assertTrue(vs.isLastPart(files[2], files))
            assertTrue(vs.isLastPart(files[3], files))
        }
    }

    @Nested
    inner class SingleItemVersions {
        @Test
        fun `prefers progress media_file_id when present and playable`() {
            val files = listOf(file(1), file(2))
            assertEquals(2L, vs.defaultChoice(files, caps, progress(2))?.file?.id)
            assertEquals(2L, vs.select(files, caps, progress(2), Standalone)?.id)
        }

        @Test
        fun `falls back when media_file_id is null, removed or unplayable`() {
            val files = listOf(file(1), hevc(2))
            assertEquals(1L, vs.defaultChoice(files, caps, progress(null, duration = 1000.0))?.file?.id)
            assertEquals(1L, vs.defaultChoice(files, caps, progress(99, duration = 1000.0))?.file?.id)
            assertEquals(1L, vs.defaultChoice(files, caps, progress(2, duration = 1000.0))?.file?.id)
            assertEquals(2L, vs.defaultChoice(files, hevcCaps, progress(2))?.file?.id)
        }

        @Test
        fun `skips a first-listed HEVC file without HEVC caps, probe errors and files without video`() {
            assertEquals(2L, vs.defaultChoice(listOf(hevc(1), file(2)), caps, null)?.file?.id)
            assertEquals(1L, vs.defaultChoice(listOf(hevc(1), file(2)), hevcCaps, null)?.file?.id)
            assertEquals(2L, vs.defaultChoice(listOf(file(1, probeError = "x"), file(2)), caps, null)?.file?.id)
            assertEquals(2L, vs.defaultChoice(listOf(file(1, video = null), file(2)), caps, null)?.file?.id)
        }

        @Test
        fun `nothing playable - the first readable file, all unreadable or no files - null`() {
            assertEquals(2L, vs.defaultChoice(listOf(file(1, probeError = "x"), hevc(2), hevc(3)), caps, null)?.file?.id)
            assertNull(vs.defaultChoice(listOf(file(1, probeError = "x"), file(2, probeError = "y")), caps, null))
            assertNull(vs.defaultChoice(emptyList(), caps, null))
            assertNull(vs.select(listOf(file(1, probeError = "x")), caps, null, Standalone))
        }

        @Test
        fun `a watched item ignores the last-played version`() {
            val files = listOf(file(1), file(2))
            assertEquals(1L, vs.defaultChoice(files, caps, progress(2, watched = true))?.file?.id)
        }

        @Test
        fun `per-file caps decide playability`() {
            val files = listOf(uhd(1, null), fhd(2, null))
            val refined = CapsForFile { f -> if (f.video?.height == 2160) caps else hevcCaps }
            assertFalse(vs.isPlayable(files[0], refined))
            assertEquals(listOf(2L), ids(vs.candidates(files, refined)))
        }
    }

    @Nested
    inner class MultiPart {
        private val p1 = part(1, 1)
        private val p2 = part(2, 2, duration = 3200.0)

        @Test
        fun `default is the part of media_file_id, else Part 1`() {
            assertEquals(Choice(Part(1), p1, false), vs.defaultChoice(listOf(p1, p2), caps, null))
            assertEquals(Choice(Part(2), p2, false), vs.defaultChoice(listOf(p1, p2), caps, progress(2, 1000.0, 3200.0)))
        }

        @Test
        fun `a finished Part 1 (95 percent or the last 30 s) starts Part 2 from 0`() {
            val files = listOf(p1, p2)
            for (position in listOf(2850.0, 2980.0, 2700.0)) {
                assertEquals(Choice(Part(2), p2, startAtZero = true), vs.defaultChoice(files, caps, progress(1, position, 3000.0)), "$position")
            }
            assertEquals(Choice(Part(1), p1, false), vs.defaultChoice(files, caps, progress(1, 2690.0, 3000.0)))
            assertEquals(Choice(Part(1), p1, false), vs.defaultChoice(files, caps, progress(1, 1500.0, 3000.0)))
        }

        @Test
        fun `the last part at 95 percent (not yet watched) resumes`() {
            val p = progress(2, 3040.0, 3200.0)
            assertEquals(Choice(Part(2), p2, false), vs.defaultChoice(listOf(p1, p2), caps, p))
            assertEquals(Start(3040.0, false), vs.startFor(p2, listOf(p1, p2), p))
        }

        @Test
        fun `standalone plus parts - the standalone version by default, Part 1 when it is unplayable`() {
            val standalone = uhd(9, null, duration = 6200.0)
            val files = listOf(standalone, fhd(1, 1), fhd(2, 2))
            assertEquals(9L, vs.defaultChoice(files, hevcCaps, null)?.file?.id)
            assertEquals(9L, vs.defaultChoice(files, hevcCaps, progress(2, 3000.0, 3000.0, watched = true))?.file?.id)
            assertEquals(Choice(Part(1), files[1], false), vs.defaultChoice(files, caps, null))
            val onStandalone = progress(9, 1200.0, 6200.0)
            assertEquals(Choice(Standalone, standalone, false), vs.defaultChoice(files, hevcCaps, onStandalone))
            assertTrue(vs.isLastPart(standalone, files))
            assertNull(vs.nextPart(files, hevcCaps, standalone))
        }

        @Test
        fun `another Part 1 version when the first is not in caps, or the first readable one`() {
            assertEquals(2L, vs.defaultChoice(listOf(part(1, 1, video = HEVC), part(2, 1), part(3, 2)), caps, null)?.file?.id)
            val none = listOf(part(1, 1, probeError = "x"), part(2, 1, video = HEVC), part(3, 2, video = HEVC))
            assertEquals(2L, vs.defaultChoice(none, caps, null)?.file?.id)
        }

        @Test
        fun `D10 next part wins over a matching Part 1 sibling and starts everything at 0`() {
            val files = listOf(part(1, 1, video = HEVC), part(2, 1), part(3, 2))
            val p = progress(1, 2980.0, 3000.0)
            assertEquals(Choice(Part(2), files[2], true), vs.defaultChoice(files, caps, p))
            for (f in files) assertEquals(Start(0.0, false), vs.startFor(f, files, p))
        }
    }

    @Nested
    inner class NextPartD13 {
        private val remuxP1 = part(1, 1, label = "Remux")
        private val p2Hevc = part(2, 2, video = HEVC, label = "Remux")
        private val p2Web = part(3, 2, label = "Web")

        @Test
        fun `playability wins over the label`() {
            val files = listOf(remuxP1, p2Hevc, p2Web)
            assertEquals(3L, vs.nextPart(files, caps, remuxP1)?.id)
            assertEquals(2L, vs.nextPart(files, hevcCaps, remuxP1)?.id)
        }

        @Test
        fun `nothing playable - the same-label file without probe_error, then the first readable`() {
            val hevcOnly = listOf(remuxP1, part(2, 2, video = HEVC, label = "Other"), part(3, 2, video = HEVC, label = "Remux"))
            assertEquals(3L, vs.nextPart(hevcOnly, caps, remuxP1)?.id)
            val broken = listOf(remuxP1, part(2, 2, label = "Remux", probeError = "e"), part(3, 2, video = HEVC, label = "Other"))
            assertEquals(3L, vs.nextPart(broken, caps, remuxP1)?.id)
        }

        @Test
        fun `keeps the same label when playable, only looks at the next part number`() {
            val a = part(1, 1, label = "A")
            assertEquals(3L, vs.nextPart(listOf(a, part(2, 2, label = "B"), part(3, 2, label = "A")), caps, a)?.id)
            assertEquals(2L, vs.nextPart(listOf(a, part(3, 3), part(2, 2, video = HEVC)), caps, a)?.id)
        }

        @Test
        fun `null for the last part or when every next-part file is unreadable`() {
            val a = part(1, 1)
            val b = part(2, 2)
            assertNull(vs.nextPart(listOf(a, b), caps, b))
            assertNull(vs.nextPart(listOf(a, part(2, 2, probeError = "x")), caps, a))
        }

        @Test
        fun `the D10 default prefers the playable Part 2 over the same label`() {
            val p1 = part(1, 1, video = HEVC, label = "2160p HEVC")
            val files = listOf(p1, part(2, 2, video = HEVC, label = "2160p HEVC"), part(3, 2, label = "1080p H264"))
            assertEquals(3L, vs.defaultChoice(files, caps, progress(1, 2990.0, 3000.0))?.file?.id)
        }
    }

    @Nested
    inner class MatchesD11 {
        private fun last(duration: Double = 9360.0, part: Int? = null) = hevc(1, duration = duration, part = part)
        private fun p(duration: Double = 9360.0) = progress(1, 3723.0, duration)

        @Test
        fun `duration tolerance max(60 s, 2 percent)`() {
            assertTrue(vs.matches(file(2, duration = 9372.0), listOf(last()), p()))
            assertTrue(vs.matches(file(2, duration = 9500.0), listOf(last()), p()))
            assertFalse(vs.matches(file(2, duration = 9600.0), listOf(last()), p()))
            assertTrue(vs.matches(file(2, duration = 650.0), listOf(last(600.0)), p(600.0)))
            assertFalse(vs.matches(file(2, duration = 661.0), listOf(last(600.0)), p(600.0)))
            assertFalse(vs.matches(file(2, duration = null), listOf(last()), p()))
            assertFalse(vs.matches(file(2), listOf(last()), null))
        }

        @Test
        fun `the same part number is required (two nulls are equal)`() {
            val files = listOf(last(3000.0, part = 1), part(2, 2, duration = 3000.0), part(3, 1, duration = 3000.0))
            assertFalse(vs.matches(files[1], files, p(3000.0)))
            assertTrue(vs.matches(files[2], files, p(3000.0)))
        }

        @Test
        fun `a removed file - duration test alone without parts, never with parts`() {
            val removed = progress(null, 600.0, 1300.0)
            assertTrue(vs.matches(file(5, duration = 1320.0), listOf(file(5, duration = 1320.0)), removed))
            assertTrue(vs.matches(file(5, duration = 1320.0), listOf(file(5, duration = 1320.0)), removed.copy(media_file_id = 999)))
            val parts = listOf(part(1, 1, duration = 1300.0), part(2, 2, duration = 1300.0))
            assertFalse(vs.matches(parts[0], parts, removed))
        }

        @Test
        fun `select and defaultChoice resume a matching version when the last-played one is unplayable`() {
            val files = listOf(hevc(1, duration = 9360.0), file(2, duration = 9372.0))
            assertEquals(2L, vs.select(files, caps, p(), Standalone)?.id)
            assertEquals(Choice(Standalone, files[1], false), vs.defaultChoice(files, caps, p()))
            assertEquals(Start(3723.0, false), vs.startFor(files[1], files, p()))
        }

        @Test
        fun `without a matching version the default starts from 0 with the notice`() {
            val files = listOf(hevc(1, duration = 9360.0), file(2, duration = 8100.0))
            assertEquals(2L, vs.defaultChoice(files, caps, p())?.file?.id)
            assertEquals(Start(0.0, true), vs.startFor(files[1], files, p()))
        }

        @Test
        fun `a different part starts at 0 with the notice`() {
            val files = listOf(file(10, duration = 3200.0), part(11, 1, duration = 3100.0), part(12, 2, video = HEVC, duration = 3200.0))
            val prog = progress(12, 1000.0, 3200.0)
            val chosen = vs.defaultChoice(files, caps, prog)!!.file
            assertEquals(10L, chosen.id)
            assertEquals(Start(0.0, true), vs.startFor(chosen, files, prog))
            assertEquals(Start(0.0, true), vs.startFor(files[1], files, prog))
        }

        @Test
        fun `a removed file on a film with parts uses the default rules at 0`() {
            val files = listOf(part(1, 1), part(2, 2))
            val prog = progress(null, 600.0, 3000.0)
            val chosen = vs.defaultChoice(files, caps, prog)!!.file
            assertEquals(1L, chosen.id)
            assertEquals(0.0, vs.startFor(chosen, files, prog).positionSeconds)
        }

        @Test
        fun `a removed file on an item without parts resumes a matching version`() {
            val files = listOf(file(501, duration = 1320.0))
            val near = progress(null, 600.0, 1300.0)
            assertEquals(501L, vs.defaultChoice(files, caps, near)?.file?.id)
            assertEquals(Start(600.0, false), vs.startFor(files[0], files, near))
            assertEquals(Start(0.0, true), vs.startFor(files[0], files, progress(null, 600.0, 1250.0)))
        }
    }

    @Nested
    inner class Candidates {
        @Test
        fun `excludes probe errors and caps-incompatible files, empty when nothing plays`() {
            val files = listOf(hevc(1), file(2, probeError = "x"), file(3), file(4, video = null))
            assertEquals(listOf(3L), ids(vs.candidates(files, caps)))
            assertEquals(listOf(1L, 3L), ids(vs.candidates(files, hevcCaps)))
            assertTrue(vs.candidates(listOf(hevc(1), file(2, probeError = "x")), caps).isEmpty())
        }

        @Test
        fun `orders the last-played file first, then matching versions, then the rest`() {
            val files = listOf(file(1, duration = 5000.0, size = 9), file(2, duration = 7210.0, size = 8), file(3, duration = 7200.0, size = 7))
            assertEquals(listOf(3L, 2L, 1L), ids(vs.candidates(files, caps, Standalone, progress(3, 1000.0, 7200.0))))
            assertEquals(listOf(1L, 2L, 3L), ids(vs.candidates(files, caps, Standalone, null)))
        }

        @Test
        fun `only the versions of the given part`() {
            val files = listOf(part(1, 1), part(2, 1), part(3, 2))
            assertEquals(listOf(1L, 2L), ids(vs.candidates(files, caps, Part(1))))
            assertEquals(listOf(3L), ids(vs.candidates(files, caps, Part(2))))
            assertTrue(vs.candidates(files, caps, Standalone).isEmpty())
        }
    }

    @Nested
    inner class StartFor {
        private val files = listOf(hevc(1, duration = 7200.0), file(2, duration = 7190.0), file(3, duration = 6000.0))
        private val p = progress(1, 3000.0, 7200.0)

        @Test
        fun `the saved position for the same or a matching file`() {
            assertEquals(Start(3000.0, false), vs.startFor(files[0], files, p))
            assertEquals(Start(3000.0, false), vs.startFor(files[1], files, p))
        }

        @Test
        fun `0 with the notice for a non-matching file`() {
            assertEquals(Start(0.0, true), vs.startFor(files[2], files, p))
        }

        @Test
        fun `0 without the notice from the start, for watched items and short positions`() {
            assertEquals(Start(0.0, false), vs.startFor(files[2], files, p, fromStart = true))
            assertEquals(Start(0.0, false), vs.startFor(files[0], files, p, fromStart = true))
            assertEquals(Start(0.0, false), vs.startFor(files[2], files, p.copy(watched = true)))
            assertEquals(Start(0.0, false), vs.startFor(files[2], files, p.copy(position_seconds = 29.0)))
            assertEquals(Start(0.0, false), vs.startFor(files[0], files, null))
        }

        @Test
        fun `a carried position within the last 30 s of the target starts at 0`() {
            val late = progress(1, 7170.0, 7200.0)
            assertEquals(Start(0.0, false), vs.startFor(files[1], files, late))
        }
    }
}
