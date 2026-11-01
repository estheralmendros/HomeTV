package com.hometv.tv.data

import com.hometv.tv.core.api.RatingPrompt
import com.hometv.tv.core.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A prompt offered for [profileId] (the profile current when its answer arrived). */
data class PendingPrompt(val profileId: Long, val prompt: RatingPrompt)

/**
 * The rating prompts waiting to be shown (ratings prd3 FR-T5), app-scoped (`AppContainer.ratingPrompts`):
 * fed by the player on exit and by "Marcar como visto", consumed by the root `RatingPromptHost`.
 *
 * In memory and thread-safe (offers arrive from `appScope` and from the main thread; one lock). A newer
 * offer for another title replaces an untaken one. A taken prompt's (profile, kind, id) is "handled" for
 * the process: a later report of the same title never reopens it. A real change of
 * (serverUrl, profileId) clears everything; `AppNavigator.authExpired` calls [clear] too. Opening the
 * Profile Picker or Setup and coming back changes neither, so nothing is lost there.
 */
class RatingPrompts(session: StateFlow<Session>, scope: CoroutineScope) {
    private data class Key(val profileId: Long, val kind: String, val id: Long)

    private val lock = Any()
    private val handled = HashSet<Key>()
    private val _pending = MutableStateFlow<PendingPrompt?>(null)
    val pending: StateFlow<PendingPrompt?> = _pending.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.map { it.serverUrl to it.profileId }.distinctUntilChanged().drop(1).collect { clear() }
        }
    }

    /** Ignored without a profile (no session) or when this title was already handled for that profile. */
    fun offer(profileId: Long?, prompt: RatingPrompt) {
        if (profileId == null) return
        synchronized(lock) {
            if (Key(profileId, prompt.kind, prompt.id) in handled) return
            _pending.value = PendingPrompt(profileId, prompt)
        }
    }

    /**
     * The pending prompt when it belongs to [currentProfileId] (marked handled, pending cleared). A
     * prompt of another profile (a late answer of the previous one) is dropped, not handled, and null
     * is returned: it is never shown or saved under the current profile.
     */
    fun take(currentProfileId: Long?): PendingPrompt? = synchronized(lock) {
        val p = _pending.value ?: return null
        _pending.value = null
        if (p.profileId != currentProfileId) return null
        handled += Key(p.profileId, p.prompt.kind, p.prompt.id)
        p
    }

    /** Drops the pending prompt and every handled key. */
    fun clear() {
        synchronized(lock) {
            handled.clear()
            _pending.value = null
        }
    }
}
