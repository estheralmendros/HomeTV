package com.hometv.tv.core.playback

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.MediaFile

/**
 * Catalog message keys (`app` maps each to a `strings.xml` id). Never server text: `error.message`,
 * `details.detail` and the unsupported `detail` are logged only.
 */
enum class MessageKey {
    /** "Este vídeo no se puede reproducir en este televisor" (+ a REASON_* line). */
    PLAYBACK_UNSUPPORTED,
    REASON_VIDEO_CODEC_UNSUPPORTED,
    REASON_NO_VIDEO_STREAM,
    REASON_HLS_NOT_SUPPORTED,
    REASON_PROBE_FAILED,
    REASON_DURATION_UNKNOWN,
    REASON_KEYFRAMES_TOO_SPARSE,
    REASON_UNKNOWN,
    PREPARING_TIMEOUT,
    TOO_MANY_SESSIONS,
    HLS_DISK_FULL,
    SERVER_UNAVAILABLE,
    KEYFRAME_INDEX_FAILED,
    FILE_MISSING,
    ITEM_GONE,
    HLS_FAILED,
    PLAYBACK_FAILED,
    UNEXPECTED_ERROR,
    CONNECTION_ERROR,
    TLS_ERROR,
    SIGN_IN_FAILED,
    AUDIO_TRACK_UNPLAYABLE,
    AUDIO_SWITCH_FAILED,
    AUDIO_PREPARING_TIMEOUT,
}

enum class PlayerAction { RETRY, RETRY_FROM_HERE, TRY_OTHER_VERSION, BACK }

/** A full-screen player error: a message key, an optional reason line, its arguments and buttons (first = focused). */
data class PlayerError(
    val message: MessageKey,
    val reason: MessageKey? = null,
    val args: List<String> = emptyList(),
    val actions: List<PlayerAction>,
)

/** What the player does with a failure. */
sealed interface PlaybackDecision {
    data class ShowError(
        val error: PlayerError,
        /** Terminal for this version: never retried during this playback ([Probar otra versión] excludes it). */
        val refuseVersion: Boolean = false,
        /** 404 `not_found`: emit `AppEvent.ItemGone`. */
        val itemGone: Boolean = false,
    ) : PlaybackDecision

    /** 503 `preparing` reached the mapper (the repository loop normally handles it). */
    data object Preparing : PlaybackDecision

    /** The renewal answered 404 `profile_not_found`: Profile Picker. */
    data object AuthExpired : PlaybackDecision

    /** 422 `invalid_audio_stream`: re-send once with `audio_stream_index = null`. */
    data object RetryWithoutAudioIndex : PlaybackDecision

    /** Stream 401: `SessionGuard.renew()`, then recovery at the current position. */
    data object RenewAndRecover : PlaybackDecision

    /** HLS 404 (`session_not_found`): new playback request at the current position. */
    data object Recover : PlaybackDecision

    data class RetryExcludingVideo(val codec: String) : PlaybackDecision

    data class RetryExcludingAudio(val codec: String) : PlaybackDecision

    data class RetryExcludingContainer(val container: String) : PlaybackDecision

    /** Side-loaded subtitle failure: drop it, rebuild without it, Off, toast; no playback request. */
    data object SubtitleUnavailable : PlaybackDecision

    /** ExoPlayer can't play this file (or its remux): the same file on libVLC at the current position, once per file. */
    data object FallBackToVlc : PlaybackDecision
}

/** Per-playback history the ViewModel keeps for the playback retry limits. Times in ms. */
data class FailureContext(
    val mode: PlaybackMode,
    val nowMs: Long,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioAction: String? = null,
    val container: String? = null,
    val audioRetried: Boolean = false,
    val videoRetried: Boolean = false,
    val containerRetried: Boolean = false,
    val lastRenewalAtMs: Long? = null,
    val lastRecoveryAtMs: Long? = null,
    val hasOtherVersion: Boolean = false,
    /** The engine of the current attachment. */
    val engine: PlaybackEngine = PlaybackEngine.EXO,
    /** libVLC can be used for this file (available, and ExoPlayer hasn't already failed on it). */
    val vlcFallbackAllowed: Boolean = false,
    /** The one libVLC renew + recovery of this file was used. */
    val vlcRecovered: Boolean = false,
)

/** "Preparando el vídeo…" estimate: none, "queda menos de un minuto", or "quedan unos N min". */
sealed interface PreparingEstimate {
    data object None : PreparingEstimate

    data object LessThanAMinute : PreparingEstimate

    data class Minutes(val minutes: Int) : PreparingEstimate
}

/** A failed direct → HLS audio switch: a toast, never a full-screen error. */
sealed interface SwitchFailure {
    data class Toast(val message: MessageKey) : SwitchFailure

    data object AuthExpired : SwitchFailure
}

/**
 * Pure mapping of every playback failure (including a failed audio switch) to a decision with message keys.
 * Branches on `error.code`, never on the status alone (503 has three meanings).
 */
object PlaybackErrorMapper {
    /** At most one stream-401 renewal and one HLS-404 recovery per 30 s. */
    const val RATE_LIMIT_MS = 30_000L
    private const val KEYFRAME_INDEX_FAILED_PREFIX = "keyframe_index_failed"

    /** Codes the server defines; any other code shows "Se ha producido un error inesperado (%1$s)." */
    private val KNOWN_CODES = setOf(
        "preparing", "too_many_sessions", "hls_disk_full", "transcoder_failed", "file_missing", "not_found",
        "invalid_audio_stream", "validation_error", "unauthorized", "forbidden", "session_not_found", "segment_timeout",
        "segment_not_found", "media_file_not_linked", "profile_not_found", "internal_error",
    )

    private val REASON_KEYS = mapOf(
        UnsupportedReason.VIDEO_CODEC_UNSUPPORTED to MessageKey.REASON_VIDEO_CODEC_UNSUPPORTED,
        UnsupportedReason.NO_VIDEO_STREAM to MessageKey.REASON_NO_VIDEO_STREAM,
        UnsupportedReason.HLS_NOT_SUPPORTED to MessageKey.REASON_HLS_NOT_SUPPORTED,
        UnsupportedReason.PROBE_FAILED to MessageKey.REASON_PROBE_FAILED,
        UnsupportedReason.DURATION_UNKNOWN to MessageKey.REASON_DURATION_UNKNOWN,
        UnsupportedReason.KEYFRAMES_TOO_SPARSE to MessageKey.REASON_KEYFRAMES_TOO_SPARSE,
        UnsupportedReason.UNKNOWN to MessageKey.REASON_UNKNOWN,
    )

    /** `N = ceil(estimated_seconds / 60)`; under 60 s "queda menos de un minuto"; absent → plain text. */
    fun preparingEstimate(estimatedSeconds: Int?): PreparingEstimate = when {
        estimatedSeconds == null -> PreparingEstimate.None
        estimatedSeconds < 60 -> PreparingEstimate.LessThanAMinute
        else -> PreparingEstimate.Minutes((estimatedSeconds + 59) / 60)
    }

    /** "Este vídeo no se puede reproducir en este televisor" + the reason line (codec in upper case for `video_codec_unsupported`). */
    fun forUnsupported(reason: UnsupportedReason, file: MediaFile?, hasOtherVersion: Boolean): PlayerError {
        val args = if (reason == UnsupportedReason.VIDEO_CODEC_UNSUPPORTED) listOfNotNull(file?.video?.codec?.uppercase()) else emptyList()
        return PlayerError(MessageKey.PLAYBACK_UNSUPPORTED, REASON_KEYS.getValue(reason), args, withOther(hasOtherVersion, PlayerAction.BACK))
    }

    /** Server reasons that mean "this client needs an HLS remux the server can't make"; libVLC plays the file directly. */
    val REMUX_REASONS: Set<UnsupportedReason> = setOf(UnsupportedReason.HLS_NOT_SUPPORTED, UnsupportedReason.DURATION_UNKNOWN, UnsupportedReason.KEYFRAMES_TOO_SPARSE)

    /** True when an `unsupported` answer to an EXO request should be retried once on libVLC. */
    fun fallsBackToVlc(reason: UnsupportedReason, vlcFallbackAllowed: Boolean): Boolean =
        vlcFallbackAllowed && reason in REMUX_REASONS

    fun forPreparingTimeout(): PlayerError = PlayerError(MessageKey.PREPARING_TIMEOUT, actions = listOf(PlayerAction.RETRY, PlayerAction.BACK))

    /** A failed `POST .../playback` (after the repository's `preparing` loop and the session renewal). */
    fun forRequestError(e: ApiException, hasOtherVersion: Boolean, audioIndexRetried: Boolean = false, vlcFallbackAllowed: Boolean = false): PlaybackDecision {
        val retry = listOf(PlayerAction.RETRY, PlayerAction.BACK)
        return when (e) {
            is ApiException.ProfileGone -> PlaybackDecision.AuthExpired
            is ApiException.Unreachable -> show(if (e.isTls) MessageKey.TLS_ERROR else MessageKey.CONNECTION_ERROR, retry)
            is ApiException.Unauthorized -> show(MessageKey.SIGN_IN_FAILED, retry)
            is ApiException.Decode -> show(MessageKey.PLAYBACK_FAILED, retry)
            is ApiException.Http -> when {
                e.code == "preparing" -> PlaybackDecision.Preparing
                e.code == "too_many_sessions" -> show(MessageKey.TOO_MANY_SESSIONS, retry)
                e.code == "hls_disk_full" -> show(MessageKey.HLS_DISK_FULL, retry)
                e.status == 503 -> show(MessageKey.SERVER_UNAVAILABLE, retry)
                isKeyframeIndexFailure(e) && vlcFallbackAllowed -> PlaybackDecision.FallBackToVlc
                isKeyframeIndexFailure(e) -> PlaybackDecision.ShowError(
                    PlayerError(MessageKey.KEYFRAME_INDEX_FAILED, actions = withOther(hasOtherVersion, PlayerAction.BACK)),
                    refuseVersion = true,
                )
                e.code == "file_missing" -> PlaybackDecision.ShowError(
                    PlayerError(MessageKey.FILE_MISSING, actions = withOther(hasOtherVersion, PlayerAction.BACK)),
                    refuseVersion = true,
                )
                e.code == "not_found" -> PlaybackDecision.ShowError(PlayerError(MessageKey.ITEM_GONE, actions = listOf(PlayerAction.BACK)), itemGone = true)
                e.code == "invalid_audio_stream" && !audioIndexRetried -> PlaybackDecision.RetryWithoutAudioIndex
                e.code != null && e.code !in KNOWN_CODES ->
                    PlaybackDecision.ShowError(PlayerError(MessageKey.UNEXPECTED_ERROR, args = listOf(e.code), actions = retry))
                else -> PlaybackDecision.ShowError(PlayerError(MessageKey.PLAYBACK_FAILED, actions = withOther(hasOtherVersion, PlayerAction.BACK, first = PlayerAction.RETRY)))
            }
        }
    }

    /**
     * A playback error from either engine (`PlayerFailure` from `ExoPlayerController` or `VlcPlayerController`).
     * A libVLC failure (`engine = VLC`) is opaque: one renew + recovery per file, then generic (rows 1/2); an
     * ExoPlayer failure goes through the subtitle, HTTP, HLS, network, decoder and parsing rows.
     */
    fun forPlayerFailure(failure: PlayerFailure, ctx: FailureContext): PlaybackDecision {
        // libVLC errors are opaque: one renew + recovery per file (no 30 s limit), then the generic screen.
        if (failure.engine == PlaybackEngine.VLC) return if (!ctx.vlcRecovered) PlaybackDecision.RenewAndRecover else generic(ctx)
        val code = failure.errorCode
        if (failure.urlKind == UrlKind.SUBTITLE && (PlaybackErrorCodes.isParsing(code) || PlaybackErrorCodes.isIo(code))) {
            return PlaybackDecision.SubtitleUnavailable
        }
        when (failure.httpStatus) {
            401 -> return if (allowed(ctx.lastRenewalAtMs, ctx.nowMs)) PlaybackDecision.RenewAndRecover else generic(ctx)
            404 -> when (failure.urlKind) {
                UrlKind.STREAM -> return PlaybackDecision.ShowError(PlayerError(MessageKey.FILE_MISSING, actions = withOther(ctx.hasOtherVersion, PlayerAction.BACK)))
                UrlKind.HLS -> return if (allowed(ctx.lastRecoveryAtMs, ctx.nowMs)) PlaybackDecision.Recover else generic(ctx)
                else -> Unit
            }
        }
        val status = failure.httpStatus
        if (status != null && status >= 500 && failure.urlKind == UrlKind.HLS) {
            // The server conversion failed: libVLC plays the file directly (checked before HLS_FAILED).
            if (ctx.vlcFallbackAllowed) return PlaybackDecision.FallBackToVlc
            return show(MessageKey.HLS_FAILED, listOf(PlayerAction.RETRY_FROM_HERE, PlayerAction.BACK))
        }
        if (code == PlaybackErrorCodes.IO_NETWORK_CONNECTION_FAILED || code == PlaybackErrorCodes.IO_NETWORK_CONNECTION_TIMEOUT) {
            return show(MessageKey.CONNECTION_ERROR, listOf(PlayerAction.RETRY_FROM_HERE, PlayerAction.BACK))
        }
        if (code in PlaybackErrorCodes.DECODER_ERRORS) return forDecoderFailure(rendererKind(failure), ctx)
        val containerFailure = code == PlaybackErrorCodes.PARSING_CONTAINER_UNSUPPORTED || code == PlaybackErrorCodes.PARSING_CONTAINER_MALFORMED
        if (containerFailure && failure.urlKind == UrlKind.STREAM && ctx.mode == PlaybackMode.DIRECT) {
            val container = ctx.container
            return if (!ctx.containerRetried && container != null) PlaybackDecision.RetryExcludingContainer(container) else genericOrVlc(failure, ctx)
        }
        return genericOrVlc(failure, ctx)
    }

    /** `TrackCheck` outcome: handled exactly like a decoder failure of that type (generic on libVLC); null for NONE. */
    fun forTrackFailure(failure: TrackFailure, ctx: FailureContext): PlaybackDecision? {
        if (failure != TrackFailure.NONE && ctx.engine == PlaybackEngine.VLC) return generic(ctx)
        return when (failure) {
            TrackFailure.NONE -> null
            TrackFailure.VIDEO -> forDecoderFailure(RendererType.VIDEO, ctx)
            TrackFailure.AUDIO -> forDecoderFailure(RendererType.AUDIO, ctx)
        }
    }

    /** Row 40: a failed direct → HLS audio switch is a toast; the direct media keeps playing. */
    fun forSwitchError(e: ApiException): SwitchFailure = when {
        e is ApiException.ProfileGone -> SwitchFailure.AuthExpired
        e is ApiException.Http && e.code == "too_many_sessions" -> SwitchFailure.Toast(MessageKey.TOO_MANY_SESSIONS)
        e is ApiException.Http && e.code == "hls_disk_full" -> SwitchFailure.Toast(MessageKey.HLS_DISK_FULL)
        e is ApiException.Http && (isKeyframeIndexFailure(e) || e.code == "invalid_audio_stream") -> SwitchFailure.Toast(MessageKey.AUDIO_TRACK_UNPLAYABLE)
        else -> SwitchFailure.Toast(MessageKey.AUDIO_SWITCH_FAILED)
    }

    fun forSwitchUnsupported(): SwitchFailure = SwitchFailure.Toast(MessageKey.AUDIO_TRACK_UNPLAYABLE)

    fun forSwitchPreparingTimeout(): SwitchFailure = SwitchFailure.Toast(MessageKey.AUDIO_PREPARING_TIMEOUT)

    /**
     * Video → libVLC when allowed (DIRECT or HLS, row 4), else in direct mode one retry without the file's video
     * codec (the server then answers unsupported). Audio → one retry without the current audio codec in direct
     * mode, or in HLS only with `audio_action = copy` (the server converts to AAC). Anything else is generic.
     */
    private fun forDecoderFailure(kind: RendererType, ctx: FailureContext): PlaybackDecision {
        val audioCodec = ctx.audioCodec
        val videoCodec = ctx.videoCodec
        return when {
            kind == RendererType.AUDIO && !ctx.audioRetried && audioCodec != null &&
                (ctx.mode == PlaybackMode.DIRECT || ctx.audioAction == "copy") -> PlaybackDecision.RetryExcludingAudio(audioCodec)
            kind == RendererType.VIDEO && ctx.vlcFallbackAllowed -> PlaybackDecision.FallBackToVlc
            kind == RendererType.VIDEO && ctx.mode == PlaybackMode.DIRECT && !ctx.videoRetried && videoCodec != null ->
                PlaybackDecision.RetryExcludingVideo(videoCodec)
            else -> generic(ctx)
        }
    }

    /** Row 8: a parsing failure (not a subtitle) that would end on the generic screen continues on libVLC when allowed. */
    private fun genericOrVlc(failure: PlayerFailure, ctx: FailureContext): PlaybackDecision =
        // Subtitle parsing failures already returned SubtitleUnavailable in forPlayerFailure.
        if (ctx.vlcFallbackAllowed && PlaybackErrorCodes.isParsing(failure.errorCode)) {
            PlaybackDecision.FallBackToVlc
        } else {
            generic(ctx)
        }

    private fun rendererKind(failure: PlayerFailure): RendererType = when {
        failure.errorCode == PlaybackErrorCodes.AUDIO_TRACK_INIT_FAILED || failure.errorCode == PlaybackErrorCodes.AUDIO_TRACK_WRITE_FAILED -> RendererType.AUDIO
        failure.rendererMime?.startsWith("audio/") == true -> RendererType.AUDIO
        failure.rendererMime?.startsWith("video/") == true -> RendererType.VIDEO
        failure.rendererMime == null && failure.rendererType != null -> failure.rendererType
        else -> RendererType.OTHER
    }

    private fun isKeyframeIndexFailure(e: ApiException.Http) = e.code == "transcoder_failed" && e.detail?.startsWith(KEYFRAME_INDEX_FAILED_PREFIX) == true

    private fun allowed(lastAtMs: Long?, nowMs: Long) = lastAtMs == null || nowMs - lastAtMs >= RATE_LIMIT_MS

    /** "No se ha podido reproducir este vídeo." */
    private fun generic(ctx: FailureContext) = PlaybackDecision.ShowError(
        PlayerError(MessageKey.PLAYBACK_FAILED, actions = withOther(ctx.hasOtherVersion, PlayerAction.BACK, first = PlayerAction.RETRY_FROM_HERE)),
    )

    private fun show(key: MessageKey, actions: List<PlayerAction>) = PlaybackDecision.ShowError(PlayerError(key, actions = actions))

    private fun withOther(hasOtherVersion: Boolean, last: PlayerAction, first: PlayerAction? = null) =
        listOfNotNull(first, PlayerAction.TRY_OTHER_VERSION.takeIf { hasOtherVersion }, last)
}
