package com.hometv.tv.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.PlayerState
import com.hometv.tv.core.playback.TrackCheckInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * prd3 §8 "Headset and Bluetooth play/pause": the player the Player's `MediaSession` wraps routes
 * play / pause / seek back-forward to the ViewModel (`PlayerController.Listener.onRemote*`) and
 * never drives the wrapped player itself.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RemoteCommandPlayerTest {
    private val wrapped = FakeWrapped()
    private val listener = RecordingListener()
    private val remote = RemoteCommandPlayer(wrapped) { listener }

    @Test
    fun `play, pause and setPlayWhenReady go to the listener, never to the wrapped player`() {
        remote.play()
        remote.pause()
        remote.playWhenReady = true
        remote.playWhenReady = false
        assertEquals(listOf("play", "pause", "play", "pause"), listener.calls)
        assertEquals(emptyList<String>(), wrapped.calls)
    }

    @Test
    fun `seek back and forward go to the listener`() {
        remote.seekBack()
        remote.seekForward()
        assertEquals(listOf("seek false", "seek true"), listener.calls)
        assertEquals(emptyList<String>(), wrapped.calls)
    }

    @Test
    fun `a headset play-pause toggles through the listener from the wrapped player's state`() {
        wrapped.playing = true
        assertTrue(Util.handlePlayPauseButtonAction(remote))
        wrapped.playing = false
        assertTrue(Util.handlePlayPauseButtonAction(remote))
        assertEquals(listOf("pause", "play"), listener.calls)
        assertEquals(emptyList<String>(), wrapped.calls)
    }

    @Test
    fun `a play on a stopped (released) player does not prepare it`() {
        wrapped.idle = true
        Util.handlePlayButtonAction(remote)
        assertEquals(listOf("play"), listener.calls)
        assertEquals(emptyList<String>(), wrapped.calls)
    }

    @Test
    fun `only play-pause, seek back-forward and the read-only getters are offered`() {
        for (command in listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
            assertTrue("command $command", remote.isCommandAvailable(command))
            assertTrue("command $command", remote.availableCommands.contains(command))
        }
        for (command in listOf(Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_RELEASE)) {
            assertFalse("command $command", remote.isCommandAvailable(command))
            assertFalse("command $command", remote.availableCommands.contains(command))
        }
    }

    /** A wrapped player with every command available that records whatever reaches it. */
    private class FakeWrapped : SimpleBasePlayer(Looper.getMainLooper()) {
        val calls = mutableListOf<String>()
        var playing = false
            set(value) {
                field = value
                invalidateState()
            }
        var idle = false
            set(value) {
                field = value
                invalidateState()
            }

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(MediaItemData.Builder("item").setMediaItem(MediaItem.fromUri("https://hometv/x")).build()))
            .setPlaybackState(if (idle) Player.STATE_IDLE else Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = done("setPlayWhenReady $playWhenReady")

        override fun handlePrepare(): ListenableFuture<*> = done("prepare")

        override fun handleStop(): ListenableFuture<*> = done("stop")

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = done("seek $positionMs")

        private fun done(call: String): ListenableFuture<*> {
            calls += call
            return Futures.immediateVoidFuture()
        }
    }

    private class RecordingListener : PlayerController.Listener {
        val calls = mutableListOf<String>()

        override fun onTracksChanged(check: TrackCheckInput) = Unit

        override fun onStateChanged(state: PlayerState) = Unit

        override fun onIsPlayingChanged(isPlaying: Boolean) = Unit

        override fun onDurationChanged(durationMs: Long?) = Unit

        override fun onFailure(failure: PlayerFailure) = Unit

        override fun onRemotePlay() {
            calls += "play"
        }

        override fun onRemotePause() {
            calls += "pause"
        }

        override fun onRemoteSeek(forward: Boolean) {
            calls += "seek $forward"
        }
    }
}
