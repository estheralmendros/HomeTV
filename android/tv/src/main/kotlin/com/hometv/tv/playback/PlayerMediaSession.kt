package com.hometv.tv.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import java.util.concurrent.atomic.AtomicLong

/**
 * The Player screen's Media3 `MediaSession` (headset and Bluetooth play/pause, any media controller) over a
 * [ControllerSessionPlayer], for either engine. Owned by the screen (one per Player screen); [release] releases
 * the session only (the wrapped player drives nothing).
 */
@OptIn(UnstableApi::class)
class PlayerMediaSession(context: Context, player: ControllerSessionPlayer) {
    private val session = MediaSession.Builder(context, player)
        .setId("hometv-player-${ids.incrementAndGet()}")
        .setCallback(RemoteOnlyCallback)
        .build()

    /** The session id (unique within the process). */
    val id: String get() = session.id

    fun release() = session.release()

    /** Every controller (AVRCP, the system, apps) gets only the [ControllerSessionPlayer] commands and no custom ones. */
    private object RemoteOnlyCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(ControllerSessionPlayer.ALLOWED_COMMANDS)
                .build()
    }

    private companion object {
        /** MediaSession ids must be unique within the process (one per Player screen). */
        val ids = AtomicLong()
    }
}
