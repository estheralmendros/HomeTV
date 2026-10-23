package com.hometv.tv.core.playback

import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.SubtitleTrack
import com.hometv.tv.core.util.ServerUrl

/** prd.md unsupported reasons; [UNKNOWN] covers future ones ("Este formato no es compatible."). */
enum class UnsupportedReason(val wire: String?) {
    PROBE_FAILED("probe_failed"),
    NO_VIDEO_STREAM("no_video_stream"),
    VIDEO_CODEC_UNSUPPORTED("video_codec_unsupported"),
    HLS_NOT_SUPPORTED("hls_not_supported"),
    DURATION_UNKNOWN("duration_unknown"),
    KEYFRAMES_TOO_SPARSE("keyframes_too_sparse"),
    UNKNOWN(null),
    ;

    companion object {
        fun fromWire(reason: String?): UnsupportedReason = entries.firstOrNull { it.wire != null && it.wire == reason } ?: UNKNOWN
    }
}

/** A subtitle offered in this playback; [url] is absolute for text tracks (image tracks have none). */
data class PlannedSubtitle(val track: SubtitleTrack, val url: String?)

sealed interface PlaybackPlan {
    data class Play(
        val mode: PlaybackMode,
        val url: String,
        val mimeType: String?,
        val sessionId: String?,
        val audioStreamIndex: Int?,
        val audioAction: String?,
        val durationSeconds: Double?,
        val subtitles: List<PlannedSubtitle>,
        val defaultSubtitleId: String?,
    ) : PlaybackPlan {
        /** HLS with an `audio_action` other than `copy`/`none`: the current audio entry shows "(convertido)". */
        val audioConverted: Boolean get() = mode == PlaybackMode.HLS && audioAction != null && audioAction != "copy" && audioAction != "none"
    }

    /** `mode: unsupported` (the server `detail` is kept for logs only). */
    data class Unsupported(val reason: UnsupportedReason, val wireReason: String?, val detail: String?) : PlaybackPlan
}

/** `PlaybackResponse` → what the player attaches (prd3 Playback Flow rules 1–3). Pure. */
object PlaybackPlanner {
    /** Direct play MIME by `MediaFile.container` (progressive source; extractors sniff the content). */
    fun mimeForContainer(container: String?): String? = when (container?.lowercase()) {
        "mkv", "matroska" -> "video/x-matroska"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        else -> null
    }

    /**
     * Resolves relative URLs against [serverUrl], picks the MIME, lists the subtitles visible in
     * that mode and the default one (`TrackRules.defaultSubtitle` with the language of the response's
     * audio track). An unknown `mode`, or a `direct`/`hls` answer without a `url`, is unsupported.
     */
    fun plan(response: PlaybackResponse, serverUrl: String, file: MediaFile): PlaybackPlan {
        val mode = when (response.mode) {
            "direct" -> PlaybackMode.DIRECT
            "hls" -> PlaybackMode.HLS
            "unsupported" -> return PlaybackPlan.Unsupported(UnsupportedReason.fromWire(response.reason), response.reason, response.detail)
            else -> return PlaybackPlan.Unsupported(UnsupportedReason.UNKNOWN, response.reason, "unknown mode ${response.mode}")
        }
        val url = response.url ?: return PlaybackPlan.Unsupported(UnsupportedReason.UNKNOWN, response.reason, "missing url")
        val visible = TrackRules.visibleSubtitles(response.subtitle_tracks, mode)
        val audioLanguage = file.audio_tracks.firstOrNull { it.index == response.audio_stream_index }?.language
        return PlaybackPlan.Play(
            mode = mode,
            url = ServerUrl.resolve(serverUrl, url),
            mimeType = if (mode == PlaybackMode.HLS) Mimes.APPLICATION_M3U8 else mimeForContainer(file.container),
            sessionId = response.session_id,
            audioStreamIndex = response.audio_stream_index,
            audioAction = response.audio_action,
            durationSeconds = response.duration_seconds,
            subtitles = visible.map { track -> PlannedSubtitle(track, track.url?.takeIf { track.isText }?.let { ServerUrl.resolve(serverUrl, it) }) },
            defaultSubtitleId = TrackRules.defaultSubtitle(visible, mode, audioLanguage)?.id,
        )
    }
}
