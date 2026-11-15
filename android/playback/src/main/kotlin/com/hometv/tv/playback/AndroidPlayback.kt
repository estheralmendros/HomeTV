package com.hometv.tv.playback

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.core.playback.SubtitleLook
import okhttp3.OkHttpClient
import org.videolan.libvlc.LibVLC

/** The engines both apps build: one LibVlcProvider per process, Exo/VLC controllers on demand. */
class AndroidPlayback private constructor(
    val controllers: (PlaybackEngine, OkHttpClient, () -> SubtitleLook) -> PlayerController,
    val vlcAvailability: VlcAvailability,
) {
    companion object {
        /**
         * Nothing is created here: LibVLC loads its native code at the first VLC open (prd2 FR-5). [displayHeightPx] is
         * read at each VLC port build (prd3 FR-3.2: the mobile player is landscape by then), not at `create`.
         */
        fun create(app: Context, token: () -> String?, log: (String, Throwable?) -> Unit, displayHeightPx: () -> Int): AndroidPlayback =
            // One LibVLC per option list (the subtitle style is in the instance options); a restyle releases the old one.
            create(
                app, token, log, displayHeightPx,
                LibVlcProvider<LibVLC>(Build.SUPPORTED_ABIS.toList(), { opts -> LibVLC(app, ArrayList(opts)) }, { it.release() }, log),
            ) { lib -> LibVlcPort(lib) }

        /** Test seam: a fake [libVlc] provider and the port built over its instance. */
        internal fun <T : Any> create(
            app: Context,
            token: () -> String?,
            log: (String, Throwable?) -> Unit,
            displayHeightPx: () -> Int,
            libVlc: LibVlcProvider<T>,
            portOf: (T) -> VlcPlayerPort,
        ): AndroidPlayback {
            val mainHandler = Handler(Looper.getMainLooper())
            return AndroidPlayback(
                controllers = { engine, streamClient, look ->
                    when (engine) {
                        PlaybackEngine.EXO -> ExoPlayerController(PlayerFactory.create(app, streamClient))
                        PlaybackEngine.VLC -> VlcPlayerController(
                            portFactory = { l -> libVlc.getOrOld(VlcOptions.libVlc(displayHeightPx(), l)).let { (lib, applied) -> portOf(lib) to applied } },
                            token = token,
                            log = log,
                            post = { block -> mainHandler.post(block) },
                            look = look,
                        )
                    }
                },
                vlcAvailability = libVlc.availability,
            )
        }
    }
}
