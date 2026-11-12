package com.hometv.tv.data

import com.hometv.tv.core.api.TrackChoice
import com.hometv.tv.core.data.RatingKind
import com.hometv.tv.core.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The audio/subtitle choices picked on this device (`AppContainer.trackChoiceMemory`, process
 * lifetime, like [RatingPrompts]): a pick wins over any fetched choice, part by part
 * (`TrackChoiceRules.effective`), until its `PUT` is confirmed ([markConfirmed]) **and** a detail
 * requested after that confirmation arrives ([fetched]). Leaving the player, a failed `PUT` or a
 * fetched older choice never lose it; a real change of (serverUrl, profileId), [clear] (sign-out,
 * `AppNavigator.authExpired`) and process death do.
 *
 * The clear on a (serverUrl, profileId) change runs asynchronously, from the session collector on
 * `scope` (like [RatingPrompts]): a pick made right after a switch, before the collector runs, can be
 * wiped, and a quick A→B→A switch can be conflated by the `StateFlow` into no change at all. Both are
 * harmless: the keys include the profile id, so another profile never reads an entry.
 *
 * Thread-safe: called from the main thread (get/put/ticket/fetched), from `appScope` (markConfirmed,
 * the session collector) and from sign-out; every public method runs its whole body under one lock,
 * with no suspension or callback inside. Seqs and tickets share one counter, so they are strictly
 * increasing across threads.
 */
class TrackChoiceMemory(session: StateFlow<Session>, scope: CoroutineScope) {
    private data class Key(val profileId: Long, val kind: RatingKind, val id: Long)

    private class Entry(val choice: TrackChoice, val seq: Long, var confirmedTicket: Long? = null)

    private val lock = Any()
    private val entries = HashMap<Key, Entry>()
    private var counter = 0L

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.map { it.serverUrl to it.profileId }.distinctUntilChanged().drop(1).collect { clear() }
        }
    }

    /** The choice picked on this device for (profile, title), until confirmed and refetched. */
    fun get(profileId: Long, kind: RatingKind, id: Long): TrackChoice? = synchronized(lock) { entries[Key(profileId, kind, id)]?.choice }

    /** Records a pick; returns its seq (for [markConfirmed]). */
    fun put(profileId: Long, kind: RatingKind, id: Long, choice: TrackChoice): Long = synchronized(lock) {
        val seq = ++counter
        entries[Key(profileId, kind, id)] = Entry(choice, seq)
        seq
    }

    /** A monotonically increasing ticket (one counter per memory, never a clock). */
    fun ticket(): Long = synchronized(lock) { ++counter }

    /**
     * The save of [seq] was confirmed by the server; records [ticket] as the confirmation ticket.
     * Ignored unless seq is still the entry's latest.
     */
    fun markConfirmed(profileId: Long, kind: RatingKind, id: Long, seq: Long) {
        synchronized(lock) {
            val entry = entries[Key(profileId, kind, id)] ?: return
            if (entry.seq != seq) return
            entry.confirmedTicket = ++counter
        }
    }

    /**
     * A choice for the title was fetched from a request started at [requestTicket]: drops the entry
     * when it is confirmed with a confirmation ticket < [requestTicket].
     */
    fun fetched(profileId: Long, kind: RatingKind, id: Long, requestTicket: Long) {
        synchronized(lock) {
            val key = Key(profileId, kind, id)
            val confirmed = entries[key]?.confirmedTicket ?: return
            if (confirmed < requestTicket) entries.remove(key)
        }
    }

    /** Sign-out, `AppNavigator.authExpired` and a real (serverUrl, profileId) change. */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
