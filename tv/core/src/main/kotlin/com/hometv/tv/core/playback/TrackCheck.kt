package com.hometv.tv.core.playback

/** What `ExoPlayerController` reports on `onTracksChanged` after the overrides are applied. */
data class TrackCheckInput(
    /** `MediaFile.video != null`. */
    val fileHasVideo: Boolean,
    /** `selectedAudioIndex != null`. */
    val expectedAudio: Boolean,
    val videoSupported: Boolean,
    val videoSelected: Boolean,
    val audioSupported: Boolean,
    val audioSelected: Boolean,
)

enum class TrackFailure { NONE, VIDEO, AUDIO }

/**
 * Silent track failures (pure): ExoPlayer would otherwise play black video or silence without an
 * error. Both are handled as a decoder failure of that type. When both fail, VIDEO wins: its
 * retry leads to the unsupported screen, and an audio retry could not fix the video.
 */
object TrackCheck {
    fun check(input: TrackCheckInput): TrackFailure = with(input) {
        when {
            fileHasVideo && !(videoSupported && videoSelected) -> TrackFailure.VIDEO
            expectedAudio && !(audioSupported && audioSelected) -> TrackFailure.AUDIO
            else -> TrackFailure.NONE
        }
    }
}
