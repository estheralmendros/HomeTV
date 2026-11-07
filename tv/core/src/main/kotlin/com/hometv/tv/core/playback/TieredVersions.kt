package com.hometv.tv.core.playback

import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.Progress

/** [exo] = ExoPlayer tier (today's caps), [any] = engine caps (VLC caps for libVLC-only files). */
data class VersionCaps(val exo: CapsForFile, val any: CapsForFile)

/**
 * Pure Exo-first version choice over `VersionSelector`: ExoPlayer-playable versions first, libVLC-only
 * versions after. With `exo == any` (libVLC unavailable) every function returns `VersionSelector`'s result.
 */
object TieredVersions {
    /** `VersionSelector.defaultChoice` with [VersionCaps.exo]; when its file isn't Exo-playable, the result with [VersionCaps.any] (else the Exo one). */
    fun defaultChoice(files: List<MediaFile>, caps: VersionCaps, progress: Progress?): Choice? {
        val exo = VersionSelector.defaultChoice(files, caps.exo, progress)
        if (exo != null && VersionSelector.isPlayable(exo.file, caps.exo)) return exo
        return VersionSelector.defaultChoice(files, caps.any, progress) ?: exo
    }

    /** Same rule over `VersionSelector.select`. */
    fun select(files: List<MediaFile>, caps: VersionCaps, progress: Progress?, group: VersionGroup): MediaFile? =
        tiered(caps) { VersionSelector.select(files, it, progress, group) }

    /** `candidates(exo)` followed by the `candidates(any)` not already listed (Exo-playable first). */
    fun candidates(files: List<MediaFile>, caps: VersionCaps, group: VersionGroup = VersionGroup.Standalone, progress: Progress? = null): List<MediaFile> {
        val exo = VersionSelector.candidates(files, caps.exo, group, progress)
        return exo + VersionSelector.candidates(files, caps.any, group, progress).filter { it !in exo }
    }

    /** Same rule as [select] over `VersionSelector.nextPart`. */
    fun nextPart(files: List<MediaFile>, caps: VersionCaps, current: MediaFile): MediaFile? =
        tiered(caps) { VersionSelector.nextPart(files, it, current) }

    private inline fun tiered(caps: VersionCaps, choose: (CapsForFile) -> MediaFile?): MediaFile? {
        val exo = choose(caps.exo)
        if (exo != null && VersionSelector.isPlayable(exo, caps.exo)) return exo
        return choose(caps.any) ?: exo
    }
}
