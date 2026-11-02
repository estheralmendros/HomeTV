package com.hometv.tv.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.hometv.tv.core.playback.MediaItemSpec
import com.hometv.tv.core.playback.TrackCheckInput

/**
 * Server tracks ↔ ExoPlayer `Tracks`. Pure over Media3 value types.
 * - Audio groups match `audio_tracks` by ordinal position (both follow container order); HLS has one audio group.
 * - Side-loaded text groups match by `Format.id` == server track id (`MergingMediaSource` prefixes the
 *   *group* id, not the format id; a `n:` prefix on the format id is tolerated too).
 * - Container image tracks (PGS/VobSub/DVB; original MIME in `sampleMimeType` or, when parsed during
 *   extraction, in `codecs`) match the server's embedded image tracks by ordinal position.
 * - Container text tracks are never selected: text stays disabled unless an explicit override exists.
 */
@OptIn(UnstableApi::class)
object ExoTrackMapper {
    private val IMAGE_MIMES = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS)

    fun audioGroup(tracks: Tracks, serverIndex: Int?, spec: MediaItemSpec): Tracks.Group? {
        if (serverIndex == null) return null
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.size == 1 && isHls(spec)) return audio.first()
        val ordinal = spec.audioTrackIndices.indexOf(serverIndex)
        return if (ordinal < 0) null else audio.getOrNull(ordinal)
    }

    fun subtitleGroup(tracks: Tracks, trackId: String?, spec: MediaItemSpec): Tracks.Group? {
        if (trackId == null) return null
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        text.firstOrNull { group -> formats(group).any { matchesId(it, trackId) } }?.let { return it }
        val ordinal = spec.imageSubtitleIds.indexOf(trackId)
        if (ordinal < 0) return null
        return text.filter { group -> formats(group).any(::isImage) }.getOrNull(ordinal)
    }

    /** The overrides for the current selection: audio pinned, text on only with an explicit override. */
    fun parameters(base: TrackSelectionParameters, tracks: Tracks, audioIndex: Int?, subtitleId: String?, spec: MediaItemSpec): TrackSelectionParameters {
        val builder = base.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT)
        audioGroup(tracks, audioIndex, spec)?.let { builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        val text = subtitleGroup(tracks, subtitleId, spec)
        if (text != null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(text.mediaTrackGroup, 0))
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        return builder.build()
    }

    /** `TrackCheck` input (rule 10); `PlayerViewModel` overrides the two expectations with its own data. */
    fun checkInput(tracks: Tracks, fileHasVideo: Boolean, expectedAudio: Boolean) = TrackCheckInput(
        fileHasVideo = fileHasVideo,
        expectedAudio = expectedAudio,
        videoSupported = tracks.isTypeSupported(C.TRACK_TYPE_VIDEO),
        videoSelected = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO),
        audioSupported = tracks.isTypeSupported(C.TRACK_TYPE_AUDIO),
        audioSelected = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO),
    )

    fun isImage(format: Format): Boolean = format.sampleMimeType in IMAGE_MIMES || format.codecs in IMAGE_MIMES

    private fun isHls(spec: MediaItemSpec) = spec.mimeType == MimeTypes.APPLICATION_M3U8

    private fun matchesId(format: Format, trackId: String): Boolean {
        val id = format.id ?: return false
        return id == trackId || id.endsWith(":$trackId")
    }

    private fun formats(group: Tracks.Group): List<Format> = (0 until group.length).map(group::getTrackFormat)
}
