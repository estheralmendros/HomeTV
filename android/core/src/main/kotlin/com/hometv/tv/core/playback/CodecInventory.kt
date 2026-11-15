package com.hometv.tv.core.playback

/** A decoder from `MediaCodecList` (built once per process by `AndroidCodecInventory`). */
data class VideoDecoder(
    val mime: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    /** `VideoCapabilities.isSizeSupported(w, h)`. */
    val supports: (width: Int, height: Int) -> Boolean = { w, h -> w <= maxWidth && h <= maxHeight },
)

/** The device's decoders; they do not change at runtime. */
data class CodecInventory(
    val video: List<VideoDecoder>,
    val audioDecoderMimes: Set<String>,
)

/** The current audio route, read fresh for every playback request (soundbar ↔ TV speakers ↔ Bluetooth). */
data class AudioRouteCaps(
    /** Media3 `C.ENCODING_*` values the route can pass through ([AudioEncodings]). */
    val passthroughEncodings: Set<Int>,
    /** `AudioCapabilities.maxChannelCount` (≥ 2). */
    val maxOutputChannels: Int,
)

/** Media3 `C.ENCODING_*` values used for passthrough (core has no Android dependency; `app` asserts they match). */
object AudioEncodings {
    const val AC3 = 5
    const val E_AC3 = 6
    const val DTS = 7
    const val DTS_HD = 8
    const val DOLBY_TRUEHD = 14
    const val E_AC3_JOC = 18
}

/** MIME types (Media3 `MimeTypes` values) used by the capability rules and the planner. */
object Mimes {
    const val VIDEO_H264 = "video/avc"
    const val VIDEO_HEVC = "video/hevc"
    const val VIDEO_VP9 = "video/x-vnd.on2.vp9"
    const val VIDEO_AV1 = "video/av01"
    const val VIDEO_MPEG2 = "video/mpeg2"
    const val VIDEO_MP4V = "video/mp4v-es"
    const val AUDIO_OPUS = "audio/opus"
    const val AUDIO_VORBIS = "audio/vorbis"
    const val AUDIO_FLAC = "audio/flac"
    const val AUDIO_AC3 = "audio/ac3"
    const val AUDIO_E_AC3 = "audio/eac3"
    const val AUDIO_DTS = "audio/vnd.dts"
    const val AUDIO_TRUEHD = "audio/true-hd"
    const val APPLICATION_M3U8 = "application/x-mpegURL"
    const val TEXT_VTT = "text/vtt"
}
