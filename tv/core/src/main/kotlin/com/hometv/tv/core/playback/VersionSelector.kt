package com.hometv.tv.core.playback

import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.Progress

/** The caps to judge a file with: normally `ClientCapsBuilder.build(inventory, route, forFile = file, …)`. */
fun interface CapsForFile {
    fun capsFor(file: MediaFile): ClientCaps

    companion object {
        /** The same caps for every file (tests, or caps already refined). */
        fun of(caps: ClientCaps): CapsForFile = CapsForFile { caps }
    }
}

sealed interface VersionGroup {
    /** Files with `part_number == null`: full versions (all files of an episode or of a film without parts). */
    data object Standalone : VersionGroup

    data class Part(val number: Int) : VersionGroup
}

data class PartGroup(val number: Int, val versions: List<MediaFile>)

data class Grouping(val standalone: List<MediaFile>, val parts: List<PartGroup>) {
    val hasParts: Boolean get() = parts.isNotEmpty()

    fun versionsOf(group: VersionGroup): List<MediaFile> = when (group) {
        VersionGroup.Standalone -> standalone
        is VersionGroup.Part -> parts.firstOrNull { it.number == group.number }?.versions.orEmpty()
    }
}

/** The default version: [startAtZero] is true for a D10 next part ("Reproducir parte N+1"). */
data class Choice(val group: VersionGroup, val file: MediaFile, val startAtZero: Boolean)

data class Start(val positionSeconds: Double, val differentVersionNotice: Boolean)

/**
 * Pure version choice, multi-part aware (prd3 §6.5, Capability Detection; prd.md §9 D10–D13).
 * Groups are sorted by the Rev 8 key (height DESC with null last, size DESC, id ASC), which equals
 * the API order, so the TV picks the same file as the web (which keeps the API order).
 * "Playable" = no `probe_error`, a video stream, and its codec in the (per-file refined) caps.
 * A watched item's progress is ignored for the version choice (it starts over), as on the web.
 */
object VersionSelector {
    private const val MIN_RESUME_SECONDS = 30.0
    private const val FINISHED_TAIL_SECONDS = 30.0
    private const val FINISHED_RATIO = 0.9
    private const val MATCH_FLOOR_SECONDS = 60.0
    private const val MATCH_RATIO = 0.02

    private val VERSION_ORDER: Comparator<MediaFile> = compareBy<MediaFile> { it.video?.height == null }
        .thenByDescending { it.video?.height ?: 0 }
        .thenByDescending { it.size_bytes }
        .thenBy { it.id }

    /** Never playable: decides only whether a next part exists (its last fallback ignores caps). */
    private val NO_CAPS = CapsForFile { ClientCaps(emptyList(), emptyList(), emptyList(), 0, false, false) }

    fun group(files: List<MediaFile>): Grouping = Grouping(
        standalone = files.filter { it.part_number == null }.sortedWith(VERSION_ORDER),
        parts = files.mapNotNull { f -> f.part_number?.let { it to f } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .map { (number, versions) -> PartGroup(number, versions.sortedWith(VERSION_ORDER)) },
    )

    fun groupOf(file: MediaFile): VersionGroup = file.part_number?.let { VersionGroup.Part(it) } ?: VersionGroup.Standalone

    /** A standalone file is never "a part": ending it exits the player. */
    fun isLastPart(file: MediaFile, files: List<MediaFile>): Boolean {
        val number = file.part_number ?: return true
        return files.none { (it.part_number ?: Int.MIN_VALUE) > number }
    }

    fun isPlayable(file: MediaFile, caps: CapsForFile): Boolean {
        if (file.probe_error != null) return false
        val codec = file.video?.codec ?: return false
        return caps.capsFor(file).video_codecs.any { it.equals(codec, ignoreCase = true) }
    }

    /**
     * D11: [file] may carry the saved position — same `part_number` as the last-played file (two
     * nulls are equal) and both durations known within max(60 s, 2 %) of `progress.duration_seconds`.
     * When the last-played file is gone, only items without parts can match (duration test alone).
     */
    fun matches(file: MediaFile, files: List<MediaFile>, progress: Progress?): Boolean {
        val expected = progress?.duration_seconds ?: return false
        val duration = file.duration_seconds ?: return false
        if (expected <= 0) return false
        if (kotlin.math.abs(duration - expected) > maxOf(MATCH_FLOOR_SECONDS, MATCH_RATIO * expected)) return false
        val last = lastPlayed(files, progress)
        return if (last != null) last.part_number == file.part_number else files.none { it.part_number != null }
    }

    /**
     * Within [group]: the last-played file when it is there and playable; else the first playable
     * version that matches it (D11); else the first playable; else the first readable; else null.
     */
    fun select(files: List<MediaFile>, caps: CapsForFile, progress: Progress?, group: VersionGroup): MediaFile? {
        val versions = group(files).versionsOf(group)
        val resumable = progress?.takeUnless { it.watched }
        val last = lastPlayed(versions, resumable)
        if (last != null && isPlayable(last, caps)) return last
        return versions.firstOrNull { isPlayable(it, caps) && matches(it, files, resumable) }
            ?: versions.firstOrNull { isPlayable(it, caps) }
            ?: versions.firstOrNull { it.probe_error == null }
    }

    /**
     * The playable versions of [group] in `select` order: the last-played file, then versions that
     * match it (D11), then the rest. Used for [Probar otra versión] and the next-episode `nextPlan`;
     * empty → not playable on this TV.
     */
    fun candidates(files: List<MediaFile>, caps: CapsForFile, group: VersionGroup = VersionGroup.Standalone, progress: Progress? = null): List<MediaFile> {
        val playable = group(files).versionsOf(group).filter { isPlayable(it, caps) }
        val resumable = progress?.takeUnless { it.watched }
        val last = lastPlayed(playable, resumable)
        val matching = playable.filter { it != last && matches(it, files, resumable) }
        return listOfNotNull(last) + matching + playable.filter { it != last && it !in matching }
    }

    /**
     * D13: among the files of the lowest part number above [current]'s: same `label` and playable →
     * first playable → same `label` without `probe_error` → first without `probe_error`. Null for a
     * standalone file or the last part.
     */
    fun nextPart(files: List<MediaFile>, caps: CapsForFile, current: MediaFile): MediaFile? {
        val number = current.part_number ?: return null
        val grouping = group(files)
        val next = grouping.parts.firstOrNull { it.number > number } ?: return null
        val sameLabel = { f: MediaFile -> f.label == current.label }
        val readable = { f: MediaFile -> f.probe_error == null }
        return next.versions.firstOrNull { sameLabel(it) && isPlayable(it, caps) }
            ?: next.versions.firstOrNull { isPlayable(it, caps) }
            ?: next.versions.firstOrNull { sameLabel(it) && readable(it) }
            ?: next.versions.firstOrNull(readable)
    }

    /**
     * §6.5 default: (1) unwatched progress whose file is still here: a finished non-final part →
     * the next part from 0 (D10, checked first); a playable file → resume it; an unplayable one → the
     * first playable matching version of its group (D11); (2) otherwise the playable standalone
     * version, else Part 1, else any readable standalone version. Null when nothing is readable.
     */
    fun defaultChoice(files: List<MediaFile>, caps: CapsForFile, progress: Progress?): Choice? {
        val grouping = group(files)
        val resumable = progress?.takeUnless { it.watched }
        val last = lastPlayed(files, resumable)
        if (resumable != null && last != null) {
            if (finishedNonFinalPart(files, last, resumable)) {
                nextPart(files, caps, last)?.let { return Choice(groupOf(it), it, startAtZero = true) }
            }
            if (isPlayable(last, caps)) return Choice(groupOf(last), last, startAtZero = false)
            grouping.versionsOf(groupOf(last)).firstOrNull { isPlayable(it, caps) && matches(it, files, resumable) }
                ?.let { return Choice(groupOf(it), it, startAtZero = false) }
        }
        val standalone = select(files, caps, resumable, VersionGroup.Standalone)
        if (standalone != null && (isPlayable(standalone, caps) || !grouping.hasParts)) {
            return Choice(VersionGroup.Standalone, standalone, startAtZero = false)
        }
        grouping.parts.firstOrNull()?.let { first ->
            select(files, caps, resumable, VersionGroup.Part(first.number))?.let { return Choice(VersionGroup.Part(first.number), it, false) }
        }
        return standalone?.let { Choice(VersionGroup.Standalone, it, false) }
    }

    /**
     * Where [file] starts (D11): the saved position when not [fromStart], the progress is resumable
     * (unwatched, ≥ 30 s) and [file] is the last-played file or matches it; a carried position within
     * the last 30 s of [file] starts at 0. Otherwise 0, with the notice when a resumable position was
     * lost (not for [fromStart] or a D10 next-part start).
     */
    fun startFor(file: MediaFile, files: List<MediaFile>, progress: Progress?, fromStart: Boolean = false): Start {
        val atZero = Start(0.0, differentVersionNotice = false)
        if (fromStart || progress == null || progress.watched || progress.position_seconds < MIN_RESUME_SECONDS) return atZero
        val last = lastPlayed(files, progress)
        if (last != null && finishedNonFinalPart(files, last, progress)) return atZero
        val position = progress.position_seconds
        if (last != null && file.id == last.id) return Start(position, false)
        if (matches(file, files, progress)) {
            val duration = file.duration_seconds ?: return atZero
            return if (position >= duration - FINISHED_TAIL_SECONDS) atZero else Start(position, false)
        }
        return Start(0.0, differentVersionNotice = true)
    }

    private fun lastPlayed(files: List<MediaFile>, progress: Progress?): MediaFile? {
        val id = progress?.media_file_id ?: return null
        return files.firstOrNull { it.id == id }
    }

    /** D10: the last-played file is a part, finished (last 30 s or ≥ 90 %), and a next part exists. */
    private fun finishedNonFinalPart(files: List<MediaFile>, last: MediaFile, progress: Progress): Boolean {
        if (last.part_number == null) return false
        val duration = last.duration_seconds ?: progress.duration_seconds ?: return false
        if (duration <= 0) return false
        val position = progress.position_seconds
        val finished = position >= duration - FINISHED_TAIL_SECONDS || position >= FINISHED_RATIO * duration
        return finished && nextPart(files, NO_CAPS, last) != null
    }

    // Convenience overloads with one ClientCaps for every file.
    fun isPlayable(file: MediaFile, caps: ClientCaps): Boolean = isPlayable(file, CapsForFile.of(caps))

    fun select(files: List<MediaFile>, caps: ClientCaps, progress: Progress?, group: VersionGroup): MediaFile? =
        select(files, CapsForFile.of(caps), progress, group)

    fun candidates(files: List<MediaFile>, caps: ClientCaps, group: VersionGroup = VersionGroup.Standalone, progress: Progress? = null): List<MediaFile> =
        candidates(files, CapsForFile.of(caps), group, progress)

    fun nextPart(files: List<MediaFile>, caps: ClientCaps, current: MediaFile): MediaFile? = nextPart(files, CapsForFile.of(caps), current)

    fun defaultChoice(files: List<MediaFile>, caps: ClientCaps, progress: Progress?): Choice? = defaultChoice(files, CapsForFile.of(caps), progress)
}
