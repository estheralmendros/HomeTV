package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Global events observed by `HomeTvRoot` / `HomeViewModel`. */
sealed interface AppEvent {
    /** The renewal answered 404 `profile_not_found`: Profile Picker with the "Tu sesión ha caducado…" banner. */
    data object AuthExpired : AppEvent

    /**
     * A foreground call's renewal failed with an IOException. Reserved: no subscriber acts on it
     * today, because the foreground call that triggered it also throws and its screen shows the
     * Connection Error. [Origin] still matters: background callers never emit it, which the player
     * tests assert. Kept as part of the event contract.
     */
    data class ServerUnreachable(val cause: ApiException.Unreachable) : AppEvent

    /** A detail/episode/season/playback request answered 404 `not_found`: drop the card, refresh Home. */
    data class ItemGone(val type: String, val id: Long) : AppEvent
}

fun interface AppEventSink {
    fun emit(event: AppEvent)
}

/** The app-wide event bus (`AppContainer.events`). */
class AppEvents : AppEventSink {
    private val flow = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AppEvent> = flow.asSharedFlow()

    override fun emit(event: AppEvent) {
        flow.tryEmit(event)
    }
}
