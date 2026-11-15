package com.hometv.tv.playback

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.PlayerState
import com.hometv.tv.core.playback.TrackCheckInput
import com.hometv.tv.testing.FakePlayerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Headset and Bluetooth play/pause for either engine (prd2 FR-10.1): the player the Player's `MediaSession`
 * wraps routes play / pause / seek back-forward to the ViewModel (`PlayerController.Listener.onRemote*`), never
 * to the controller, and reports the active controller's state with the ViewModel's play intent.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ControllerSessionPlayerTest {
    private var fake: FakePlayerController? = FakePlayerController().apply { state = PlayerState.READY }
    private var playing = false
    private val listener = RecordingListener()
    private val session = ControllerSessionPlayer({ fake }, { listener }, { playing })

    private fun refresh() {
        session.refresh()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun controllerCalls() = fake!!.log.filter { it == "play" || it == "pause" || it == "prepare" || it.startsWith("seekTo") }

    @Test
    fun `play, pause and setPlayWhenReady go to the listener, never to the controller`() {
        refresh()
        session.play()
        session.pause()
        session.playWhenReady = true
        session.playWhenReady = false
        assertEquals(listOf("play", "pause", "play", "pause"), listener.calls)
        assertEquals(emptyList<String>(), controllerCalls())
    }

    @Test
    fun `seek back and forward go to the listener`() {
        refresh()
        session.seekBack()
        session.seekForward()
        assertEquals(listOf("seek false", "seek true"), listener.calls)
        assertEquals(emptyList<String>(), controllerCalls())
        assertEquals(10_000, session.seekBackIncrement)
        assertEquals(10_000, session.seekForwardIncrement)
    }

    @Test
    fun `a headset play-pause toggles from the play intent, also during a rebuffer`() {
        playing = true
        fake!!.isPlaying = true
        refresh()
        assertTrue(session.playWhenReady)
        assertEquals(Player.STATE_READY, session.playbackState)
        // Rebuffer: the engine isn't playing, the intent still is.
        fake!!.state = PlayerState.BUFFERING
        fake!!.isPlaying = false
        refresh()
        assertTrue(session.playWhenReady)
        assertEquals(Player.STATE_BUFFERING, session.playbackState)
        assertTrue(Util.handlePlayPauseButtonAction(session))
        assertEquals(listOf("pause"), listener.calls)
        playing = false
        refresh()
        assertTrue(Util.handlePlayPauseButtonAction(session))
        assertEquals(listOf("pause", "play"), listener.calls)
        assertEquals(emptyList<String>(), controllerCalls())
    }

    @Test
    fun `a play on an idle (released) controller does not prepare it`() {
        fake!!.state = PlayerState.IDLE
        refresh()
        Util.handlePlayButtonAction(session)
        assertEquals(listOf("play"), listener.calls)
        assertEquals(emptyList<String>(), controllerCalls())
    }

    @Test
    fun `only play-pause, seek back-forward and the read-only getters are offered`() {
        refresh()
        for (command in listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
            assertTrue("command $command", session.isCommandAvailable(command))
            assertTrue("command $command", session.availableCommands.contains(command))
        }
        val withheld = listOf(
            Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_RELEASE,
        )
        for (command in withheld) {
            assertFalse("command $command", session.isCommandAvailable(command))
            assertFalse("command $command", session.availableCommands.contains(command))
        }
        assertEquals(ControllerSessionPlayer.ALLOWED, (0 until 40).filter { session.availableCommands.contains(it) }.toSet())
    }

    @Test
    fun `seek back-forward are offered only while the controller isn't idle`() {
        fake!!.state = PlayerState.IDLE
        refresh()
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_BACK))
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
        assertTrue(session.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        session.seekBack()
        session.seekForward()
        assertEquals(emptyList<String>(), listener.calls)
        fake = null
        refresh()
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
        assertEquals(Player.STATE_IDLE, session.playbackState)
        assertTrue(session.currentTimeline.isEmpty)
    }

    @Test
    fun `the state follows the active controller after a refresh`() {
        val exo = fake!!.apply { positionMs = 61_000; durationMs = 5_400_000 }
        playing = true
        refresh()
        assertEquals(Player.STATE_READY, session.playbackState)
        assertEquals(61_000, session.currentPosition)
        assertEquals(5_400_000, session.duration)
        val vlc = FakePlayerController().apply {
            state = PlayerState.ENDED
            positionMs = 7_000
        }
        fake = vlc
        refresh()
        assertEquals(Player.STATE_ENDED, session.playbackState)
        assertEquals(7_000, session.currentPosition)
        assertTrue(exo.log.isEmpty() && vlc.log.isEmpty())
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
