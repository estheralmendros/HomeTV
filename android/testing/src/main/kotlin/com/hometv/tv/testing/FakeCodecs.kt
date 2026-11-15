package com.hometv.tv.testing

import com.hometv.tv.core.playback.AudioRouteCaps
import com.hometv.tv.core.playback.CodecInventory
import com.hometv.tv.core.playback.Mimes
import com.hometv.tv.core.playback.VideoDecoder
import com.hometv.tv.playback.CodecSource

/** A device with hardware H.264 + HEVC (1080p), AC3 decoder, stereo route. */
class FakeCodecs(
    var inventory: CodecInventory = CodecInventory(
        video = listOf(VideoDecoder(Mimes.VIDEO_H264, true, 1920, 1080), VideoDecoder(Mimes.VIDEO_HEVC, true, 1920, 1080)),
        audioDecoderMimes = setOf(Mimes.AUDIO_AC3, Mimes.AUDIO_OPUS, Mimes.AUDIO_FLAC, Mimes.AUDIO_VORBIS),
    ),
    var route: AudioRouteCaps = AudioRouteCaps(emptySet(), 2),
) : CodecSource {
    override suspend fun inventory(): CodecInventory = inventory

    override fun audioRoute(): AudioRouteCaps = route
}
