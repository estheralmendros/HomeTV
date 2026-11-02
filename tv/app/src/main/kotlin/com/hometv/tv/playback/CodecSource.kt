package com.hometv.tv.playback

import com.hometv.tv.core.playback.AudioRouteCaps
import com.hometv.tv.core.playback.CodecInventory

/**
 * The capability-detection hook: device decoders once per process,
 * the audio route fresh for every playback request. `AndroidCodecInventory` implements it; tests fake it.
 */
interface CodecSource {
    suspend fun inventory(): CodecInventory

    fun audioRoute(): AudioRouteCaps
}
