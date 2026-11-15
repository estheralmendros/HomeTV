package com.hometv.tv.data

import com.hometv.tv.core.data.LatestWinsWriter
import com.hometv.tv.core.data.ProfileRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.playback.SubtitleLook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow

/**
 * The profile's subtitle style for the Player: [style] is [AccountState.subtitleStyle]; [save]
 * applies a look at once for the session, then `PUT /api/me/subtitle-style` in [appScope] (it
 * outlives the Player). Writes go through one [LatestWinsWriter] slot: one `PUT` in flight; a [save]
 * made meanwhile replaces the queued look, sent when the in-flight one settles, and the replaced
 * look's `Deferred` completes with the newer look's result (same profile) or `false` at once (the
 * profile changed). A queued look is dropped (`false`, nothing sent) when the stored profile changed
 * since it was made. A failure is logged through [log] and keeps the applied look until the next
 * `AccountState.refresh`.
 *
 * Thread-safe: [save] runs on the main thread, the worker in [appScope]; the writer keeps its state
 * under one lock and runs the `PUT` and the completions outside it.
 */
class SubtitleStyleStore(
    private val account: AccountState,
    private val profiles: ProfileRepository,
    store: SessionStore,
    appScope: CoroutineScope,
    log: (Throwable) -> Unit,
) {
    val style: StateFlow<SubtitleLook> get() = account.subtitleStyle

    private val writer = LatestWinsWriter<Unit, SubtitleLook>(store, appScope, log) { _, look ->
        profiles.saveSubtitleStyle(look.toWire())
    }

    /** Applies at once for the session, then PUTs in appScope; resolves false on failure. */
    fun save(look: SubtitleLook): Deferred<Boolean> {
        account.setSubtitleStyle(look)
        return writer.submit(Unit, look)
    }
}
