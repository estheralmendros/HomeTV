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
     * EXO with [exoCaps] unless libVLC is available and one of: [EngineInputs.forceVlc]; the file has a video
     * stream and no `probe_error`, and [EngineHistory.exoFailed] or its `video.codec` is not in
     * `exoCaps(…).video_codecs` (case-insensitive). Then VLC with `VlcCapsBuilder.build()`. Never looks at the
     * container: AVI or M2TS with H.264 stays on EXO (the server remuxes it).
     */
    fun select(inputs: EngineInputs, file: MediaFile, history: EngineHistory = EngineHistory()): EngineChoice {
        val exo = exoCaps(inputs, file, history)
        if (!inputs.vlcAvailable) return EngineChoice(PlaybackEngine.EXO, exo)
        if (inputs.forceVlc) return vlc()
        val codec = file.video?.codec
        if (codec == null || file.probe_error != null) return EngineChoice(PlaybackEngine.EXO, exo)
        if (history.exoFailed || exo.video_codecs.none { it.equals(codec, ignoreCase = true) }) return vlc()
        return EngineChoice(PlaybackEngine.EXO, exo)
    }

    private fun vlc() = EngineChoice(PlaybackEngine.VLC, VlcCapsBuilder.build())

    /** `CapsForFile` for the request and for the libVLC tier of the version choice: `select(…).caps`. */
    fun capsForFile(inputs: EngineInputs, history: (MediaFile) -> EngineHistory): CapsForFile =
        CapsForFile { select(inputs, it, history(it)).caps }

    /** `CapsForFile` for the ExoPlayer tier: `exoCaps(…)` (exactly today's per-file caps). */
    fun exoCapsForFile(inputs: EngineInputs, history: (MediaFile) -> EngineHistory): CapsForFile =
        CapsForFile { exoCaps(inputs, it, history(it)) }
}
