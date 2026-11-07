package com.hometv.tv.playback

import android.widget.FrameLayout
import androidx.core.net.toUri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * The one class that calls libVLC's `MediaPlayer`/`Media` (libvlc-all 3.7.x, the libVLC 3 Java API): a
 * pass-through with no branching, checked on the device (no JVM test loads libVLC native code).
 */
class LibVlcPort(private val libVlc: LibVLC) : VlcPlayerPort {
    private val player = MediaPlayer(libVlc)

    override fun open(uri: String, mediaOptions: List<String>, subtitleSlaves: List<String>) {
        // The Uri constructor: Media(LibVLC, String) takes a local file path and would fail on an http URL.
        val media = Media(libVlc, uri.toUri())
        try {
            mediaOptions.forEach(media::addOption)
            media.setHWDecoderEnabled(true, false)
            subtitleSlaves.forEach { media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, it)) }
            player.media = media
        } finally {
            media.release() // the player holds its own reference; also frees the native Media when a step throws
        }
        player.play()
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun stop() = player.stop()

    override fun release() = player.release()

    override val isSeekable: Boolean get() = player.isSeekable

    override fun setTime(timeMs: Long) {
        player.setTime(timeMs)
    }

    override fun audioTrackIds(): List<Int> = player.audioTracks.orEmpty().map { it.id }.filter { it != DISABLED }

    override fun selectedAudioTrackId(): Int = player.audioTrack

    override fun setAudioTrack(id: Int) {
        player.setAudioTrack(id)
    }

    override fun spuTrackIds(): List<Int> = player.spuTracks.orEmpty().map { it.id }.filter { it != DISABLED }

    override fun selectedSpuTrackId(): Int = player.spuTrack

    override fun setSpuTrack(id: Int) {
        player.setSpuTrack(id)
    }

    override fun videoTrackCount(): Int = player.videoTracksCount

    override fun setEventListener(listener: ((VlcEvent) -> Unit)?) {
        if (listener == null) {
            player.setEventListener(null)
            return
        }
        player.setEventListener(MediaPlayer.EventListener { event -> eventOf(event)?.let(listener) })
    }

    override fun attachViews(layout: FrameLayout) {
        player.attachViews(layout as VLCVideoLayout, null, true, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
    }

    override fun detachViews() = player.detachViews()

    private fun eventOf(event: MediaPlayer.Event): VlcEvent? = when (event.type) {
        MediaPlayer.Event.Opening -> VlcEvent.Opening
        MediaPlayer.Event.Buffering -> VlcEvent.Buffering(event.buffering)
        MediaPlayer.Event.Playing -> VlcEvent.Playing
        MediaPlayer.Event.Paused -> VlcEvent.Paused
        MediaPlayer.Event.Stopped -> VlcEvent.Stopped
        MediaPlayer.Event.EndReached -> VlcEvent.EndReached
        MediaPlayer.Event.EncounteredError -> VlcEvent.EncounteredError
        MediaPlayer.Event.TimeChanged -> VlcEvent.TimeChanged(event.timeChanged)
        MediaPlayer.Event.LengthChanged -> VlcEvent.LengthChanged(event.lengthChanged)
        MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> VlcEvent.TracksChanged
        else -> null
    }

    private companion object {
        const val DISABLED = -1
    }
}
