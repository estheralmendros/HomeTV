package com.hometv.tv.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.core.playback.PlayerState

/**
 * The player the Player screen's `MediaSession` wraps (headset, Bluetooth AVRCP, media controllers), for either
 * engine. Play / pause / seek back-forward go to [listener] (`PlayerController.Listener.onRemote*`), exactly like
 * the remote's keys, and never drive the engine. Every other command is withheld, and seek back/forward are only
 * offered while the controller isn't IDLE (as the ExoPlayer-wrapping session did). The state getters report the
 * active controller's real state with the ViewModel's play intent, so a play-pause toggle picks the right side
 * (also during a rebuffer). [refresh] republishes it. Main thread only. It is never released on its own (no
 * `COMMAND_RELEASE`): the screen releases the [PlayerMediaSession], and the ViewModel releases the engines.
 */
@OptIn(UnstableApi::class)
class ControllerSessionPlayer(
    private val controller: () -> PlayerController?,
    private val listener: () -> PlayerController.Listener?,
    /** The ViewModel's play intent (`vm.state.value.playing`), never the engine's `isPlaying`. */
    private val playing: () -> Boolean,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    override fun getState(): State {
        val c = controller()
        val playbackState = when (c?.state) {
            PlayerState.BUFFERING -> Player.STATE_BUFFERING
            PlayerState.READY -> Player.STATE_READY
            PlayerState.ENDED -> Player.STATE_ENDED
            PlayerState.IDLE, null -> Player.STATE_IDLE
        }
        val builder = State.Builder()
            .setAvailableCommands(if (playbackState == Player.STATE_IDLE) IDLE_COMMANDS else ALLOWED_COMMANDS)
            .setPlayWhenReady(playing(), Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
        if (c != null && playbackState != Player.STATE_IDLE) {
            // SimpleBasePlayer requires a non-empty playlist outside IDLE/ENDED: one item for the playing file.
            val item = MediaItemData.Builder(ITEM_UID)
            c.durationMs?.let { item.setDurationUs(Util.msToUs(it)) }
            builder.setPlaylist(listOf(item.build())).setContentPositionMs(c.positionMs)
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        listener()?.let { if (playWhenReady) it.onRemotePlay() else it.onRemotePause() }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_BACK -> listener()?.onRemoteSeek(forward = false)
            Player.COMMAND_SEEK_FORWARD -> listener()?.onRemoteSeek(forward = true)
            else -> Unit
        }
        return Futures.immediateVoidFuture()
    }

    /** Republishes the state (every `PlayerUiState` change: playing, engine, position ticks). */
    fun refresh() = invalidateState()

    companion object {
        private const val ITEM_UID = "hometv-player"
        private const val SEEK_INCREMENT_MS = 10_000L

        /** The commands the session offers: play/pause, seek back/forward and read-only state. */
        val ALLOWED = setOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
        )

        val ALLOWED_COMMANDS: Player.Commands = Player.Commands.Builder().apply { ALLOWED.forEach(::add) }.build()

        /** While IDLE (no media, stopped or released controller): no seeking. */
        private val IDLE_COMMANDS: Player.Commands = Player.Commands.Builder().apply {
            ALLOWED.filter { it != Player.COMMAND_SEEK_BACK && it != Player.COMMAND_SEEK_FORWARD }.forEach(::add)
        }.build()
    }
}
