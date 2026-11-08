package com.hometv.tv.core.playback

import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.Progress

/** [exo] = ExoPlayer tier (today's caps), [any] = engine caps (VLC caps for libVLC-only files). */
data class VersionCaps(val exo: CapsForFile, val any: CapsForFile)

/**
 * Pure Exo-first version choice over `VersionSelector`: ExoPlayer-playable versions first, libVLC-only
 * versions after. One exception keeps a resume point: when the resumable last-played file (it would resume
 * per `VersionSelector.startFor`) is playable only under [VersionCaps.any] and its group has no
 * Exo-playable matching version, that file is chosen ([resumeOnAny]). With `exo == any` (libVLC
 * unavailable) every function returns `VersionSelector`'s result.
 */
object TieredVersions {
    /** `VersionSelector.defaultChoice` with [VersionCaps.exo]; when its file isn't Exo-playable, the result with [VersionCaps.any] (else the Exo one). */
    fun defaultChoice(files: List<MediaFile>, caps: VersionCaps, progress: Progress?): Choice? {
        val exo = VersionSelector.defaultChoice(files, caps.exo, progress)
        if (exo != null && VersionSelector.isPlayable(exo.file, caps.exo)) {
            if (exo.startAtZero) return exo // the next part of a finished part: no resume point to keep
            return resumeOnAny(files, caps, progress)?.let { Choice(VersionSelector.groupOf(it), it, startAtZero = false) } ?: exo
        }
        return VersionSelector.defaultChoice(files, caps.any, progress) ?: exo
    }

    /** Same rule over `VersionSelector.select`. */
    fun select(files: List<MediaFile>, caps: VersionCaps, progress: Progress?, group: VersionGroup): MediaFile? {
        resumeOnAny(files, caps, progress)?.takeIf { VersionSelector.groupOf(it) == group }?.let { return it }
        return tiered(caps) { VersionSelector.select(files, it, progress, group) }
    }

    /** `candidates(exo)` followed by the `candidates(any)` not already listed (Exo-playable first), [resumeOnAny]'s file first. */
    fun candidates(files: List<MediaFile>, caps: VersionCaps, group: VersionGroup = VersionGroup.Standalone, progress: Progress? = null): List<MediaFile> {
        val exo = VersionSelector.candidates(files, caps.exo, group, progress)
        val all = exo + VersionSelector.candidates(files, caps.any, group, progress).filter { it !in exo }
        val resume = resumeOnAny(files, caps, progress)?.takeIf { it in all } ?: return all
        return listOf(resume) + all.filter { it != resume }
    }

    /** Same rule as [select] over `VersionSelector.nextPart`. */
    fun nextPart(files: List<MediaFile>, caps: VersionCaps, current: MediaFile): MediaFile? =
        tiered(caps) { VersionSelector.nextPart(files, it, current) }

    private inline fun tiered(caps: VersionCaps, choose: (CapsForFile) -> MediaFile?): MediaFile? {
        val exo = choose(caps.exo)
        if (exo != null && VersionSelector.isPlayable(exo, caps.exo)) return exo
        return choose(caps.any) ?: exo
    }

    /**
     * The last-played file when only libVLC can play it, it carries a resume point (`startFor` > 0: unwatched,
     * ≥ 30 s, not a finished part) and no Exo-playable version of its group matches it; else null.
     */
    private fun resumeOnAny(files: List<MediaFile>, caps: VersionCaps, progress: Progress?): MediaFile? {
        val id = progress?.media_file_id ?: return null
        val last = files.firstOrNull { it.id == id } ?: return null
        if (VersionSelector.isPlayable(last, caps.exo) || !VersionSelector.isPlayable(last, caps.any)) return null
        if (VersionSelector.startFor(last, files, progress).positionSeconds <= 0.0) return null
        val group = VersionSelector.group(files).versionsOf(VersionSelector.groupOf(last))
        if (group.any { VersionSelector.isPlayable(it, caps.exo) && VersionSelector.matches(it, files, progress) }) return null
        return last
    }
}
