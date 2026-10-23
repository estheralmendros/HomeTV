package com.hometv.tv.data

import androidx.datastore.core.DataStore
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

/**
 * [SessionStore] on Preferences DataStore (keys `server_url`, `profile_id`, `token`,
 * `token_expires_at`). [load] reads the file once before the API client is built, so the
 * `AuthInterceptor` can read `session.value.token` synchronously; every write updates the
 * in-memory value after the file.
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

    override suspend fun clearToken() {
        state.value = store.edit { p ->
            p.remove(TOKEN)
            p.remove(EXPIRES_AT)
        }.toSession()
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
