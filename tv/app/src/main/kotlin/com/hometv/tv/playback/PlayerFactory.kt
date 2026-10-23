package com.hometv.tv.playback

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import okhttp3.OkHttpClient

/**
 * Builds the ExoPlayer of the Player screen (prd3 Playback Flow rules 1, 2, 7, 8, 9, 12):
 * - data: `OkHttpDataSource.Factory(streamClient)` (Bearer from its `AuthInterceptor`, read 45 s)
 *   wrapped in `DefaultDataSource.Factory` so the `file://` subtitle URIs of `SubtitleFileCache` load too;
 * - one `DefaultMediaSourceFactory` with [HomeTvLoadErrorHandlingPolicy] (progressive + HLS);
 * - `DefaultLoadControl`: 50 s min/max buffer (> one 30 s segment), 1.5 s / 3 s to start/rebuffer,
 *   byte target from `memoryClass` ([targetBufferBytes]), size not prioritized over time;
 * - text tracks disabled until an explicit override (rule 4); movie audio attributes with audio focus.
 */
@OptIn(UnstableApi::class)
object PlayerFactory {
    const val MIN_BUFFER_MS = 50_000
    const val MAX_BUFFER_MS = 50_000
    const val BUFFER_FOR_PLAYBACK_MS = 1_500
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
    private const val MIB = 1024 * 1024
    const val MIN_TARGET_BYTES = 48 * MIB
    const val MAX_TARGET_BYTES = 128 * MIB

    /**
     * Movie audio attributes: the player's, and the ones `AndroidCodecInventory` queries passthrough
     * support with (prd3 Capability Detection), so both see the same audio route.
     */
    val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()

    /** `memoryClass × 1 MiB × 0.5`, clamped to 48–128 MiB. */
    fun targetBufferBytes(memoryClassMb: Int): Int = (memoryClassMb.toLong() * MIB / 2).coerceIn(MIN_TARGET_BYTES.toLong(), MAX_TARGET_BYTES.toLong()).toInt()

    fun create(context: Context, streamClient: OkHttpClient): ExoPlayer {
        val app = context.applicationContext
        val dataSource = DefaultDataSource.Factory(app, OkHttpDataSource.Factory(streamClient))
        val mediaSources = DefaultMediaSourceFactory(dataSource).setLoadErrorHandlingPolicy(HomeTvLoadErrorHandlingPolicy())
        val memoryClass = (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
            .setTargetBufferBytes(targetBufferBytes(memoryClass))
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val trackSelector = DefaultTrackSelector(app).apply {
            parameters = buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        return ExoPlayer.Builder(app)
            .setMediaSourceFactory(mediaSources)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(AUDIO_ATTRIBUTES, true)
            .build()
    }
}
