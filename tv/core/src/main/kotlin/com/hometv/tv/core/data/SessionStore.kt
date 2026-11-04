package com.hometv.tv.core.data

import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The persisted session (DataStore keys `server_url`, `profile_id`, `token`, `token_expires_at`). */
data class Session(
    val serverUrl: String? = null,
    val profileId: Long? = null,
    val token: String? = null,
    val expiresAt: Instant? = null,
)

/**
 * Session persistence. `app` implements it on Preferences DataStore (`DataStoreSessionStore`,
 * exposing the loaded value as a StateFlow); [InMemorySessionStore] is the JVM implementation used
 * by tests and fakes. `session.value.token` is read synchronously by the `AuthInterceptor`.
 */
interface SessionStore {
    val session: StateFlow<Session>

    /** Stores the server URL; a different URL also clears token, profile and expiry. Returns true when it changed. */
    suspend fun setServerUrl(url: String): Boolean

    suspend fun saveSession(profileId: Long, token: String, expiresAt: Instant)

    /** Forgets the profile: removes profile id, token and expiry; keeps the server URL. */
    suspend fun signOut()

    /**
     * Stores a renewed token and expiry only if the stored profile is still [profileId] (checked
     * atomically with the write). Returns true only when both the file and the in-memory session took
     * it; false after a sign-out or a profile change, including one that lands between the two.
     */
    suspend fun saveRenewedSession(profileId: Long, token: String, expiresAt: Instant): Boolean

    /**
     * Drops the token and its expiry (keeps the server and the last profile id for the Picker's
     * focus), only if the stored profile is still [profileId]. Same atomicity and return rule as
     * [saveRenewedSession].
     */
    suspend fun clearToken(profileId: Long): Boolean
}

class InMemorySessionStore(initial: Session = Session()) : SessionStore {
    private val state = MutableStateFlow(initial)
    override val session: StateFlow<Session> = state.asStateFlow()

    override suspend fun setServerUrl(url: String): Boolean {
        val changed = state.value.serverUrl != url
        state.update { if (changed) Session(serverUrl = url) else it }
        return changed
    }

    override suspend fun saveSession(profileId: Long, token: String, expiresAt: Instant) {
        state.update { it.copy(profileId = profileId, token = token, expiresAt = expiresAt) }
    }

    override suspend fun signOut() {
        state.update { Session(serverUrl = it.serverUrl) }
    }

    override suspend fun saveRenewedSession(profileId: Long, token: String, expiresAt: Instant): Boolean =
        updateIfProfile(profileId) { it.copy(token = token, expiresAt = expiresAt) }

    override suspend fun clearToken(profileId: Long): Boolean =
        updateIfProfile(profileId) { it.copy(token = null, expiresAt = null) }

    /** One atomic update; `update` may run the block again on contention, so the flag is set on every pass. */
    private fun updateIfProfile(profileId: Long, change: (Session) -> Session): Boolean {
        var applied = false
        state.update { cur ->
            applied = cur.profileId == profileId
            if (applied) change(cur) else cur
        }
        return applied
    }
}
