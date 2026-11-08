package com.hometv.tv.core.playback

import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.api.MediaFile

/** The engine that plays one attachment. `core` knows only the name, never the library. */
enum class PlaybackEngine { EXO, VLC }

/**
 * Pure: the caps the TV sends when libVLC plays the file. Every container and codec name the server
 * probe can emit (`server/hometv/probe.py`: `_container` names, ffprobe `codec_name`), `hls = false`
 * (never an HLS session), any audio track selectable. libVLC decodes audio to PCM and downmixes, so the
 * route never narrows it.
 */
object VlcCapsBuilder {
    /** `_container` emits mkv, webm, mp4 (also mov), avi, ts, else ffprobe's first format token. */
    val CONTAINERS = listOf(
        "mkv", "webm", "mp4", "avi", "ts", "mpeg", "mpegvideo", "asf", "flv", "ogg", "mov", "matroska", "m2ts", "3gp", "nut", "rm",
        "dv", "wtv",
    )

    val VIDEO_CODECS = listOf(
        "h264", "hevc", "vp8", "vp9", "av1", "mpeg4", "msmpeg4v1", "msmpeg4v2", "msmpeg4v3", "mpeg1video", "mpeg2video",
        "vc1", "wmv1", "wmv2", "wmv3", "h263", "flv1", "theora", "mjpeg", "rv30", "rv40", "prores", "dvvideo", "svq3",
    )

    val AUDIO_CODECS = listOf(
        "aac", "mp3", "mp2", "mp1", "ac3", "eac3", "dts", "truehd", "flac", "opus", "vorbis", "alac", "wmav1", "wmav2",
        "wmapro", "amr_nb", "amr_wb", "cook", "pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_u8", "pcm_dvd",
        "pcm_bluray",
    )

    const val MAX_AUDIO_CHANNELS = 8

    fun build(): ClientCaps = ClientCaps(
        containers = CONTAINERS,
        video_codecs = VIDEO_CODECS,
        audio_codecs = AUDIO_CODECS,
        max_audio_channels = MAX_AUDIO_CHANNELS,
        hls = false,
        can_select_audio_track = true,
    )
}

/** What is fixed for one playback request. */
data class EngineInputs(
    val inventory: CodecInventory,
    val route: AudioRouteCaps,
    /** `VlcAvailability` (part 2): false keeps every file on EXO and ignores [forceVlc]. */
    val vlcAvailable: Boolean,
    /** The admin debug switch (part 3), read for every request. */
    val forceVlc: Boolean = false,
)

/** The per-file part of `PlayerViewModel`'s `FileHistory` that the choice depends on. */
data class EngineHistory(
    val excludeVideo: Set<String> = emptySet(),
    val excludeAudio: Set<String> = emptySet(),
    val excludeContainers: Set<String> = emptySet(),
    /** ExoPlayer failed on this file during this playback (`PlaybackDecision.FallBackToVlc`). */
    val exoFailed: Boolean = false,
)

data class EngineChoice(val engine: PlaybackEngine, val caps: ClientCaps)

/** Pure engine choice per file (the table "Engine decision" of prd.md). */
object EngineSelector {
    /** Today's caps for [file]: `ClientCapsBuilder.build(inventory, route, file, excludeVideo, excludeAudio, excludeContainers)`. */
    fun exoCaps(inputs: EngineInputs, file: MediaFile, history: EngineHistory = EngineHistory()): ClientCaps =
        ClientCapsBuilder.build(inputs.inventory, inputs.route, file, history.excludeVideo, history.excludeAudio, history.excludeContainers)

    /**
     * Containers ExoPlayer can't read and the server remuxes poorly (`server/hometv/probe.py` `_container` names):
     * with libVLC they go to VLC up front (prd.md FR-1.4). M2TS/TS, MKV, MP4 and WebM stay on EXO.
     */
    val LEGACY_CONTAINERS: Set<String> = setOf("avi", "asf", "flv", "rm", "mpeg")

    /** ffprobe video codecs that go to VLC up front even when the device reports a decoder (FR-1.4). */
    val LEGACY_VIDEO_CODECS: Set<String> =
        setOf("mpeg4", "msmpeg4v1", "msmpeg4v2", "msmpeg4v3", "wmv1", "wmv2", "wmv3", "h263", "flv1", "rv30", "rv40")

    /**
     * EXO with [exoCaps] unless libVLC is available and one of: [EngineInputs.forceVlc]; the file has a video
     * stream and no `probe_error`, and [EngineHistory.exoFailed], or it is [isLegacy] (a legacy container or video
     * codec, FR-1.4), or its `video.codec` is not in `exoCaps(…).video_codecs` (case-insensitive). Then VLC with
     * `VlcCapsBuilder.build()`. H.264 in M2TS stays on EXO (the server remuxes it).
     */
    fun select(inputs: EngineInputs, file: MediaFile, history: EngineHistory = EngineHistory()): EngineChoice {
        val exo = exoCaps(inputs, file, history)
        if (!inputs.vlcAvailable) return EngineChoice(PlaybackEngine.EXO, exo)
        if (inputs.forceVlc) return vlc()
        val codec = file.video?.codec
        if (codec == null || file.probe_error != null) return EngineChoice(PlaybackEngine.EXO, exo)
        if (history.exoFailed || isLegacy(file) || exo.video_codecs.none { it.equals(codec, ignoreCase = true) }) return vlc()
        return EngineChoice(PlaybackEngine.EXO, exo)
    }

    /** A [LEGACY_CONTAINERS] container or a [LEGACY_VIDEO_CODECS] video codec (case-insensitive). */
    fun isLegacy(file: MediaFile): Boolean =
        file.container?.lowercase() in LEGACY_CONTAINERS || file.video?.codec?.lowercase() in LEGACY_VIDEO_CODECS

    private fun vlc() = EngineChoice(PlaybackEngine.VLC, VlcCapsBuilder.build())

    /** `CapsForFile` for the request and for the libVLC tier of the version choice: `select(…).caps`. */
    fun capsForFile(inputs: EngineInputs, history: (MediaFile) -> EngineHistory): CapsForFile =
        CapsForFile { select(inputs, it, history(it)).caps }

    /**
     * `CapsForFile` for the ExoPlayer tier: `exoCaps(…)` (today's per-file caps), except that with libVLC available
     * an [isLegacy] file's video codec is left out, so that file is a libVLC-tier version (FR-1.4): Film detail and
     * the Player prefer an Exo-playable version over it.
     */
    fun exoCapsForFile(inputs: EngineInputs, history: (MediaFile) -> EngineHistory): CapsForFile =
        CapsForFile { file ->
            val caps = exoCaps(inputs, file, history(file))
            val codec = file.video?.codec
            if (inputs.vlcAvailable && codec != null && isLegacy(file)) {
                caps.copy(video_codecs = caps.video_codecs.filterNot { it.equals(codec, ignoreCase = true) })
            } else {
                caps
            }
        }
}
