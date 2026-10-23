package com.hometv.tv.core.playback

import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.api.MediaFile

/**
 * Pure: device decoders + current audio route (+ the file, + exclusions) → `ClientCaps`
 * (prd3 Capability Detection). Exclusions hold codecs/containers removed after a decoder,
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

    /** Per-file refinement: the file's codec stays only if a hardware decoder supports its size (HDR is not filtered). */
    private fun fitsDecoder(codec: String, file: MediaFile?, hardware: List<VideoDecoder>): Boolean {
        val video = file?.video ?: return true
        if (!video.codec.equals(codec, ignoreCase = true)) return true
        val width = video.width ?: return true
        val height = video.height ?: return true
        val mime = VIDEO_MIMES[codec] ?: return true
        return hardware.any { it.mime == mime && it.supports(width, height) }
    }
}
