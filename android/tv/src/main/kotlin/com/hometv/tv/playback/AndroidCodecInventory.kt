package com.hometv.tv.playback

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.hometv.tv.core.playback.AudioRouteCaps
import com.hometv.tv.core.playback.CodecInventory
import com.hometv.tv.core.playback.VideoDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * `MediaCodecList` → decoders (built once, on `Dispatchers.Default`, cached for the process) and
 * Media3 `AudioCapabilities` → [AudioRouteCaps] (read per request: soundbar ↔ TV speakers ↔ Bluetooth).
 */
@OptIn(UnstableApi::class)
class AndroidCodecInventory(private val context: Context, scope: CoroutineScope) : CodecSource {
    private val decoders: Deferred<CodecInventory> = scope.async(Dispatchers.Default, start = CoroutineStart.LAZY) { scan() }

    override suspend fun inventory(): CodecInventory = decoders.await()

    override fun audioRoute(): AudioRouteCaps {
        val caps = AudioCapabilities.getCapabilities(context, PlayerFactory.AUDIO_ATTRIBUTES, null)
        val encodings = PASSTHROUGH.filter { caps.supportsEncoding(it) }.toSet()
        return AudioRouteCaps(encodings, caps.maxChannelCount.coerceAtLeast(2))
    }

    private fun scan(): CodecInventory {
        val video = mutableListOf<VideoDecoder>()
        val audio = mutableSetOf<String>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                val mime = type.lowercase()
                when {
                    mime.startsWith("video/") -> runCatching { info.getCapabilitiesForType(type).videoCapabilities }.getOrNull()?.let { vc ->
                        video += VideoDecoder(
                            mime = mime,
                            hardware = isHardware(info),
                            maxWidth = vc.supportedWidths.upper,
                            maxHeight = vc.supportedHeights.upper,
                            supports = { w, h -> vc.isSizeSupported(w, h) },
                        )
                    }
                    mime.startsWith("audio/") -> audio += mime
                }
            }
        }
        return CodecInventory(video, audio)
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")

    @OptIn(UnstableApi::class)
    private companion object {
        val PASSTHROUGH = listOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC, C.ENCODING_DTS, C.ENCODING_DTS_HD, C.ENCODING_DOLBY_TRUEHD)
    }
}
