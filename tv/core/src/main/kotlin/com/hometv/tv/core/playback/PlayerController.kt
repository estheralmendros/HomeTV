package com.hometv.tv.core.playback

/**
 * Media3 `PlaybackException.ERROR_CODE_*` values the mapper branches on (core has no Android
 * dependency; `app` asserts these equal the Media3 constants).
 */
object PlaybackErrorCodes {
    const val IO_UNSPECIFIED = 2000
    const val IO_NETWORK_CONNECTION_FAILED = 2001
    const val IO_NETWORK_CONNECTION_TIMEOUT = 2002
    const val IO_BAD_HTTP_STATUS = 2004
    const val IO_FILE_NOT_FOUND = 2005
    const val IO_READ_POSITION_OUT_OF_RANGE = 2008
    const val PARSING_CONTAINER_MALFORMED = 3001
    const val PARSING_MANIFEST_MALFORMED = 3002
    const val PARSING_CONTAINER_UNSUPPORTED = 3003
    const val PARSING_MANIFEST_UNSUPPORTED = 3004
    const val DECODER_INIT_FAILED = 4001
    const val DECODER_QUERY_FAILED = 4002
    const val DECODING_FAILED = 4003
    const val DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4004
    const val DECODING_FORMAT_UNSUPPORTED = 4005
    const val AUDIO_TRACK_INIT_FAILED = 5001
    const val AUDIO_TRACK_WRITE_FAILED = 5002

    val DECODER_ERRORS = setOf(
        DECODER_INIT_FAILED, DECODER_QUERY_FAILED, DECODING_FAILED, DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        DECODING_FORMAT_UNSUPPORTED, AUDIO_TRACK_INIT_FAILED, AUDIO_TRACK_WRITE_FAILED,
    )

    fun isIo(code: Int): Boolean = code in 2000..2999

    fun isParsing(code: Int): Boolean = code in 3000..3999
}

/** Which load failed (`urlKind`, from the most recent `AnalyticsListener.onLoadError`). */
enum class UrlKind {
    /** The direct `…/stream` URL. */
    STREAM,

    /** A path under `/api/hls/`. */
    HLS,

    /** A side-loaded VTT (`file://` from `SubtitleFileCache`, or a text track load). */
    SUBTITLE,

    /** Anything else, or no load error recorded for this failure. */
    OTHER,
}

/** Media3 renderer type, from `ExoPlaybackException.type == TYPE_RENDERER`. */
enum class RendererType { VIDEO, AUDIO, OTHER }

/** A pure description of an ExoPlayer `PlaybackException`, built by `ExoPlayerController`. */
data class PlayerFailure(
    val errorCode: Int,
    val rendererType: RendererType? = null,
    /** `rendererFormat.sampleMimeType` (video/hevc, audio/vnd.dts…). */
    val rendererMime: String? = null,
    /** `InvalidResponseCodeException.responseCode`. */
    val httpStatus: Int? = null,
    val urlKind: UrlKind = UrlKind.OTHER,
    /** `error.code` of the response body's envelope, when present. */
    val bodyCode: String? = null,
)

/** A side-loaded WebVTT file from `SubtitleFileCache` (Media3 `SubtitleConfiguration`). */
data class SideloadedSubtitle(
    val trackId: String,
    val fileUri: String,
    val language: String?,
    val label: String?,
    val forced: Boolean,
)

/** What `MediaItemBuilder` turns into a Media3 `MediaItem`. */
data class MediaItemSpec(
    val url: String,
    /** Container MIME for direct play, `application/x-mpegURL` for HLS, null to let extractors sniff. */
    val mimeType: String?,
    val subtitles: List<SideloadedSubtitle> = emptyList(),
    /** Server `audio_tracks[].index` in container order: `ExoTrackMapper` matches audio groups by ordinal. */
    val audioTrackIndices: List<Int> = emptyList(),
    /** Server ids of the embedded image subtitle tracks in container order (direct mode; matched by ordinal). */
    val imageSubtitleIds: List<String> = emptyList(),
)

/** Media3 `Player.STATE_*`. */
enum class PlayerState { IDLE, BUFFERING, READY, ENDED }

/**
 * The player as `PlayerViewModel` sees it (implemented by `ExoPlayerController` in `app`, faked in
 * tests). Times are milliseconds; track selection uses the server's ids/indices, the controller
 * maps them to ExoPlayer track groups (`ExoTrackMapper`).
 */
interface PlayerController {
    val positionMs: Long

    /** `player.duration`, or null while `C.TIME_UNSET`. */
    val durationMs: Long?
    val bufferedPositionMs: Long
    val isPlaying: Boolean
    val state: PlayerState

    fun setListener(listener: Listener?)

    /** `setMediaItem(item, startPositionMs)`; the previous item's tracks and overrides are dropped. */
    fun setMediaItem(item: MediaItemSpec, startPositionMs: Long)

    fun prepare()

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** Releases decoders and the network; the media item is kept and the last frame stays on screen. */
    fun stop()

    fun release()

    /** Pins the audio group of the server `audio_stream_index` (direct mode); null = no override. */
    fun selectAudio(serverIndex: Int?)

    /** Side-loaded text track by server id, a container image track (direct mode), or null for Off. */
    fun selectSubtitle(trackId: String?)

    interface Listener {
        /** Once per media item with non-empty tracks, after the overrides are applied. */
        fun onTracksChanged(check: TrackCheckInput)

        fun onStateChanged(state: PlayerState)

        fun onIsPlayingChanged(isPlaying: Boolean)

        fun onDurationChanged(durationMs: Long?)

        fun onFailure(failure: PlayerFailure)

        /**
         * A play command from outside the app's key handling (headset and Bluetooth
         * play/pause: the Player's media session, i.e. Bluetooth AVRCP, the headset button or any
         * media controller). The controller never plays by itself: the listener decides.
         */
        fun onRemotePlay() {}

        /** A pause command from the media session (see [onRemotePlay]). */
        fun onRemotePause() {}

        /** A seek back/forward command from the media session (see [onRemotePlay]). */
        fun onRemoteSeek(forward: Boolean) {}
    }
}
