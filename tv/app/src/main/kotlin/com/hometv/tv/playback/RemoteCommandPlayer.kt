package com.hometv.tv.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.hometv.tv.core.playback.PlayerController

/**
 * The player the Player screen's `MediaSession` wraps (prd3 §8 "Headset and Bluetooth play/pause").
 * Bluetooth AVRCP, the headset button and other media controllers reach the app only through that
 * session; their play / pause / seek back-forward commands go to the ViewModel through [listener]
 * (`PlayerController.Listener.onRemote*`), exactly like the remote's keys, and never drive the
 * wrapped ExoPlayer. Every other command is withheld; the getters still report the real state, so
 * a play-pause toggle picks the right side.
 */
@OptIn(UnstableApi::class)
class RemoteCommandPlayer(player: Player, private val listener: () -> PlayerController.Listener?) : ForwardingPlayer(player) {
    override fun play() {
        listener()?.onRemotePlay()
    }

    override fun pause() {
        listener()?.onRemotePause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun seekBack() {
        listener()?.onRemoteSeek(forward = false)
    }

    override fun seekForward() {
        listener()?.onRemoteSeek(forward = true)
    }

    override fun isCommandAvailable(command: Int): Boolean = command in ALLOWED && super.isCommandAvailable(command)

    override fun getAvailableCommands(): Player.Commands {
        val wrapped = super.getAvailableCommands()
        return Player.Commands.Builder().apply { ALLOWED.filter(wrapped::contains).forEach(::add) }.build()
    }

    companion object {
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
    }
}
