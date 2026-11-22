package com.hometv.tv.core.playback

import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.Progress

/**
 * [exo] = ExoPlayer tier (today's caps), [convert] = the server converts the video (`EngineSelector.convertCapsForFile`),
 * [any] = engine caps (VLC caps for libVLC-only files).
 */
data class VersionCaps(val exo: CapsForFile, val convert: CapsForFile, val any: CapsForFile)

/**
 * Pure tiered version choice over `VersionSelector`: direct ExoPlayer versions first ([VersionCaps.exo]), then server
 * conversions ([VersionCaps.convert]), then libVLC-only versions ([VersionCaps.any]). One exception keeps a resume
 * point: when the resumable last-played file (it would resume per `VersionSelector.startFor`) is playable only in a
 * lower tier and its group has no matching version playable in a higher one, that file is chosen ([resumeOnLower]).
 * With `exo == convert == any` every function returns `VersionSelector`'s result.
 */
object TieredVersions {
    private fun tiers(caps: VersionCaps) = listOf(caps.exo, caps.convert, caps.any)

    /** `VersionSelector.defaultChoice` over the first tier whose choice is playable in that tier (else the `any` result, else the Exo one). */
    fun defaultChoice(files: List<MediaFile>, caps: VersionCaps, progress: Progress?): Choice? {
        for (tier in tiers(caps)) {
            val choice = VersionSelector.defaultChoice(files, tier, progress)
            if (choice != null && VersionSelector.isPlayable(choice.file, tier)) {
                if (choice.startAtZero) return choice // the next part of a finished part: no resume point to keep
                return resumeOnLower(files, caps, progress)?.let { Choice(VersionSelector.groupOf(it), it, startAtZero = false) } ?: choice
            }
        }
        return VersionSelector.defaultChoice(files, caps.any, progress) ?: VersionSelector.defaultChoice(files, caps.exo, progress)
    }

    /** Same rule over `VersionSelector.select`. */
    fun select(files: List<MediaFile>, caps: VersionCaps, progress: Progress?, group: VersionGroup): MediaFile? {
        resumeOnLower(files, caps, progress)?.takeIf { VersionSelector.groupOf(it) == group }?.let { return it }
        return tiered(caps) { VersionSelector.select(files, it, progress, group) }
    }

    /** `candidates(exo)`, then the `convert` additions, then the `any` additions; [resumeOnLower]'s file first. */
    fun candidates(files: List<MediaFile>, caps: VersionCaps, group: VersionGroup = VersionGroup.Standalone, progress: Progress? = null): List<MediaFile> {
        val all = tiers(caps).fold(emptyList<MediaFile>()) { acc, tier -> acc + VersionSelector.candidates(files, tier, group, progress).filter { it !in acc } }
        val resume = resumeOnLower(files, caps, progress)?.takeIf { it in all } ?: return all
        return listOf(resume) + all.filter { it != resume }
    }

    /** Same rule as [select] over `VersionSelector.nextPart`. */
    fun nextPart(files: List<MediaFile>, caps: VersionCaps, current: MediaFile): MediaFile? =
        tiered(caps) { VersionSelector.nextPart(files, it, current) }

    private inline fun tiered(caps: VersionCaps, choose: (CapsForFile) -> MediaFile?): MediaFile? {
        for (tier in tiers(caps)) {
            val file = choose(tier)
            if (file != null && VersionSelector.isPlayable(file, tier)) return file
        }
        return choose(caps.any) ?: choose(caps.exo)
    }

    /**
     * The last-played file when its best tier is [VersionCaps.convert] or [VersionCaps.any], it carries a resume point
     * (`startFor` > 0: unwatched, ≥ 30 s, not a finished part) and no matching version of its group is playable in a
     * higher tier; else null.
     */
    private fun resumeOnLower(files: List<MediaFile>, caps: VersionCaps, progress: Progress?): MediaFile? {
        val id = progress?.media_file_id ?: return null
        val last = files.firstOrNull { it.id == id } ?: return null
        val all = tiers(caps)
        val best = all.indexOfFirst { VersionSelector.isPlayable(last, it) }
        if (best <= 0) return null
        if (VersionSelector.startFor(last, files, progress).positionSeconds <= 0.0) return null
        val higher = all.take(best)
        val group = VersionSelector.group(files).versionsOf(VersionSelector.groupOf(last))
        if (group.any { v -> higher.any { VersionSelector.isPlayable(v, it) } && VersionSelector.matches(v, files, progress) }) return null
        return last
    }
}
