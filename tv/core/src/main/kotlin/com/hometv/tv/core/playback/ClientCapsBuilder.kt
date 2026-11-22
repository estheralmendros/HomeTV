package com.hometv.tv.core.playback

import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.VideoInfo

/**
 * Pure: device decoders + current audio route (+ the file, + exclusions) → `ClientCaps`.
 * Exclusions hold codecs/containers removed after a decoder,
 * silent-track or container failure; callers keep them for the rest of the item's playback.
 * Output order is fixed (containers, then codecs in the order of the tables below).
 */
object ClientCapsBuilder {
    /** Media3 extractors. */
    private val CONTAINERS = listOf("mp4", "mkv", "webm", "ts")

    /** ffprobe codec name → decoder MIME. `h264` is always claimed (mandatory on Android TV); `vc1` never. */
    private val VIDEO_MIMES = linkedMapOf(
        "h264" to Mimes.VIDEO_H264,
        "hevc" to Mimes.VIDEO_HEVC,
        "vp9" to Mimes.VIDEO_VP9,
        "av1" to Mimes.VIDEO_AV1,
        "mpeg2video" to Mimes.VIDEO_MPEG2,
        "mpeg4" to Mimes.VIDEO_MP4V,
    )

    /** Framework + Media3 software support, always present. */
    private val ALWAYS_AUDIO = listOf("aac", "mp3", "pcm_s16le")

    /** Present when the decoder MIME exists (always on API 29+, checked for 26–28). */
    private val DECODER_AUDIO = linkedMapOf("opus" to Mimes.AUDIO_OPUS, "vorbis" to Mimes.AUDIO_VORBIS, "flac" to Mimes.AUDIO_FLAC)

    /** Present when the decoder MIME exists or the route passes the encoding through (HDMI soundbar/AVR). */
    private val PASSTHROUGH_AUDIO = linkedMapOf(
        "ac3" to (Mimes.AUDIO_AC3 to setOf(AudioEncodings.AC3)),
        "eac3" to (Mimes.AUDIO_E_AC3 to setOf(AudioEncodings.E_AC3, AudioEncodings.E_AC3_JOC)),
        "dts" to (Mimes.AUDIO_DTS to setOf(AudioEncodings.DTS, AudioEncodings.DTS_HD)),
        "truehd" to (Mimes.AUDIO_TRUEHD to setOf(AudioEncodings.DOLBY_TRUEHD)),
    )

    private val M2TS = Regex(""".*\.(m2ts|mts)$""", RegexOption.IGNORE_CASE)

    fun build(
        inventory: CodecInventory,
        route: AudioRouteCaps,
        forFile: MediaFile? = null,
        excludeVideo: Set<String> = emptySet(),
        excludeAudio: Set<String> = emptySet(),
        excludeContainers: Set<String> = emptySet(),
    ): ClientCaps {
        val hardware = inventory.video.filter { it.hardware }
        val video = VIDEO_MIMES.filter { (codec, mime) -> codec == "h264" || hardware.any { it.mime == mime } }.keys
            .filter { it !in excludeVideo }
            .filter { codec -> fitsDecoder(codec, forFile, hardware) }

        val audio = ALWAYS_AUDIO +
            DECODER_AUDIO.filter { (_, mime) -> mime in inventory.audioDecoderMimes }.keys +
            PASSTHROUGH_AUDIO.filter { (_, spec) -> spec.first in inventory.audioDecoderMimes || spec.second.any { it in route.passthroughEncodings } }.keys

        // Media3's TsExtractor sniffs 188-byte packets: Blu-ray M2TS (192-byte) always goes through the remux.
        val m2ts = forFile?.file_name?.let { M2TS.matches(it) } == true
        val containers = CONTAINERS.filter { it !in excludeContainers && !(m2ts && it == "ts") }

        return ClientCaps(
            containers = containers,
            video_codecs = video,
            audio_codecs = audio.filter { it !in excludeAudio },
            // Android downmixes decoded PCM: 6 is the floor; 8 only when the route reports ≥ 8.
            max_audio_channels = route.maxOutputChannels.coerceIn(6, 8),
            hls = true,
            can_select_audio_track = true,
        )
    }

    /**
     * Per-file refinement: the file's codec stays only if a hardware decoder of that MIME supports its size **and**
     * reports a profile the file needs ([requiredProfiles]; HDR is not filtered).
     */
    private fun fitsDecoder(codec: String, file: MediaFile?, hardware: List<VideoDecoder>): Boolean {
        val video = file?.video ?: return true
        if (!video.codec.equals(codec, ignoreCase = true)) return true
        val width = video.width ?: return true
        val height = video.height ?: return true
        val mime = VIDEO_MIMES[codec] ?: return true
        val required = requiredProfiles(codec, video)
        if (required != null && required.isEmpty()) return false // bit depth above 10: no consumer hardware decoder
        return hardware.any { it.mime == mime && it.supports(width, height) && (required == null || it.profiles.isEmpty() || it.profiles.any { p -> p in required }) }
    }

    private val AVC_10 = setOf(CodecProfiles.AVC_HIGH10)
    private val HEVC_8 = setOf(CodecProfiles.HEVC_MAIN, CodecProfiles.HEVC_MAIN10, CodecProfiles.HEVC_MAIN10_HDR10, CodecProfiles.HEVC_MAIN10_HDR10_PLUS)
    private val HEVC_10 = setOf(CodecProfiles.HEVC_MAIN10, CodecProfiles.HEVC_MAIN10_HDR10, CodecProfiles.HEVC_MAIN10_HDR10_PLUS)
    private val VP9_8 = setOf(CodecProfiles.VP9_PROFILE0, CodecProfiles.VP9_PROFILE2, CodecProfiles.VP9_PROFILE2_HDR, CodecProfiles.VP9_PROFILE2_HDR10_PLUS)
    private val VP9_10 = setOf(CodecProfiles.VP9_PROFILE2, CodecProfiles.VP9_PROFILE2_HDR, CodecProfiles.VP9_PROFILE2_HDR10_PLUS)
    private val AV1_8 = setOf(CodecProfiles.AV1_MAIN8, CodecProfiles.AV1_MAIN10, CodecProfiles.AV1_MAIN10_HDR10, CodecProfiles.AV1_MAIN10_HDR10_PLUS)
    private val AV1_10 = setOf(CodecProfiles.AV1_MAIN10, CodecProfiles.AV1_MAIN10_HDR10, CodecProfiles.AV1_MAIN10_HDR10_PLUS)

    /**
     * Profiles of which at least one must be reported by a hardware decoder for [video]; null = no requirement (claim
     * as today); empty = never claimed (bit depth above 10: no consumer hardware decoder). `bit_depth` decides when
     * present; the ffprobe `profile` (case-insensitive, exact) only when it is null.
     */
    fun requiredProfiles(codec: String, video: VideoInfo): Set<Int>? {
        val c = codec.lowercase()
        if (c !in setOf("h264", "hevc", "vp9", "av1")) return null
        val depth = video.bit_depth
        if (depth != null && depth > 10) return emptySet()
        val profile = video.profile?.lowercase()
        val ten = when {
            depth != null -> depth == 10
            c == "h264" && profile == "high 10" -> true
            c == "hevc" && profile == "main 10" -> true
            c == "vp9" && profile == "profile 2" -> true
            else -> null
        }
        val eight = depth == 8 || (depth == null && when (c) { "hevc" -> profile == "main"; "vp9" -> profile == "profile 0"; else -> false })
        return when (c) {
            "h264" -> if (ten == true) AVC_10 else null
            "hevc" -> if (ten == true) HEVC_10 else if (eight) HEVC_8 else null
            "vp9" -> if (ten == true) VP9_10 else if (eight) VP9_8 else null
            else -> if (ten == true) AV1_10 else if (eight) AV1_8 else null // av1: depth null has no profile rule
        }
    }
}
