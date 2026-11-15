package com.hometv.tv.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hometv.tv.core.data.Session
import com.hometv.tv.core.data.SessionStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * [SessionStore] on Preferences DataStore (keys `server_url`, `profile_id`, `token`,
 * `token_expires_at`). [load] reads the file once before the API client is built, so the
 * `AuthInterceptor` can read `session.value.token` synchronously; every write updates the
 * in-memory value after the file, except [signOut], which clears it first and again from the file's
 * result; the conditional writes ([saveRenewedSession], [clearToken]) update memory only while the
 * profile is unchanged.
 */
class DataStoreSessionStore private constructor(
    private val store: DataStore<Preferences>,
    initial: Session,
) : SessionStore {
    private val state = MutableStateFlow(initial)
    override val session: StateFlow<Session> = state.asStateFlow()

    override suspend fun setServerUrl(url: String): Boolean {
        var changed = false
        val prefs = store.edit { p ->
            changed = p[SERVER_URL] != url
            if (changed) {
                p.clear()
                p[SERVER_URL] = url
            }
        }
        state.value = prefs.toSession()
        return changed
    }

    override suspend fun saveSession(profileId: Long, token: String, expiresAt: Instant) {
        state.value = store.edit { p ->
            p[PROFILE_ID] = profileId
            p[TOKEN] = token
            p[EXPIRES_AT] = expiresAt.toString()
        }.toSession()
    }

    override suspend fun signOut() {
        // Memory first: a failed file write still protects a launch in the same process.
        state.update { Session(serverUrl = it.serverUrl) }
        state.value = store.edit { p ->
            p.remove(PROFILE_ID)
            p.remove(TOKEN)
            p.remove(EXPIRES_AT)
        }.toSession()
    }

    override suspend fun saveRenewedSession(profileId: Long, token: String, expiresAt: Instant): Boolean =
        editIfProfile(profileId) { p ->
            p[TOKEN] = token
            p[EXPIRES_AT] = expiresAt.toString()
        }

    override suspend fun clearToken(profileId: Long): Boolean =
        editIfProfile(profileId) { p ->
            p.remove(TOKEN)
            p.remove(EXPIRES_AT)
        }

    /**
     * Applies [change] to the file and then to memory, each only while the stored profile is still
     * [profileId]. True only when both applied.
     */
    private suspend fun editIfProfile(profileId: Long, change: (MutablePreferences) -> Unit): Boolean {
        var saved = false
        val prefs = store.edit { p ->
            saved = p[PROFILE_ID] == profileId && state.value.profileId == profileId
            if (saved) change(p)
        }
        if (!saved) return false
        // Conditional, not `state.value = …`: this runs after `edit` returns, possibly after a whole
        // signOut() or saveSession(7) (renewals run on appScope = Dispatchers.IO, SignOut and the
        // picker on the main thread).
        var applied = false
        state.update { cur ->
            applied = cur.profileId == profileId
            if (applied) prefs.toSession() else cur
        }
        return applied
    }

    companion object {
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val PROFILE_ID = longPreferencesKey("profile_id")
        private val TOKEN = stringPreferencesKey("token")
        private val EXPIRES_AT = stringPreferencesKey("token_expires_at")

        fun dataStore(file: File, scope: CoroutineScope): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

        suspend fun load(store: DataStore<Preferences>): DataStoreSessionStore =
            DataStoreSessionStore(store, store.data.first().toSession())

        private fun Preferences.toSession() = Session(
            serverUrl = this[SERVER_URL],
            profileId = this[PROFILE_ID],
            token = this[TOKEN],
            expiresAt = this[EXPIRES_AT]?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }
}
