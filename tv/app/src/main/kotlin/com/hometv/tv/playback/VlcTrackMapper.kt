package com.hometv.tv.playback

import com.hometv.tv.core.playback.MediaItemSpec
import com.hometv.tv.core.playback.TrackCheckInput

/** Server tracks ↔ libVLC ES ids (mirrors `ExoTrackMapper`): by ordinal, with a count guard. */
object VlcTrackMapper {
    /**
     * libVLC audio id for server `audio_stream_index`: ordinal in `spec.audioTrackIndices` → same ordinal in [audioIds];
     * null when [serverIndex] is null, unknown, or `audioIds.size != spec.audioTrackIndices.size` (count guard).
     */
    fun audioTrackId(audioIds: List<Int>, serverIndex: Int?, spec: MediaItemSpec): Int? {
        if (serverIndex == null || audioIds.size != spec.audioTrackIndices.size) return null
        val ordinal = spec.audioTrackIndices.indexOf(serverIndex).takeIf { it >= 0 } ?: return null
        return audioIds[ordinal]
    }

    /**
     * libVLC SPU id for server track id: expected SPU list = `spec.embeddedSubtitleIds` (container order) then
     * `spec.subtitles` (slaves, in the order they were added). -1 for null (Off), and also when
     * `spuIds.size != embedded + slaves` or the id is unknown (count guard: never a wrong track).
     */
    fun spuTrackId(spuIds: List<Int>, trackId: String?, spec: MediaItemSpec): Int {
        if (trackId == null) return DISABLED
        val expected = spec.embeddedSubtitleIds + spec.subtitles.map { it.trackId }
        if (spuIds.size != expected.size) return DISABLED
        val ordinal = expected.indexOf(trackId).takeIf { it >= 0 } ?: return DISABLED
        return spuIds[ordinal]
    }

    /** `TrackCheck` input; `PlayerViewModel` overrides the two expectations with its own data. */
    fun checkInput(videoTracks: Int, audioIds: List<Int>, selectedAudio: Int, expectedAudio: Boolean): TrackCheckInput =
        TrackCheckInput(
            fileHasVideo = true,
            expectedAudio = expectedAudio,
            videoSupported = videoTracks > 0,
            videoSelected = videoTracks > 0,
            audioSupported = audioIds.isNotEmpty(),
            audioSelected = selectedAudio != DISABLED,
        )

    /** libVLC's "Disable" track id. */
    const val DISABLED = -1
}
