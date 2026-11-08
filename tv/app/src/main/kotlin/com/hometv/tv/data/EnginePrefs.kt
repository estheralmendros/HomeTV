package com.hometv.tv.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-device player preferences (not per profile, not per server). */
interface EnginePrefs {
    /** The admin debug switch "Usar siempre VLC (experimental)"; default false. */
    val forceVlc: StateFlow<Boolean>

    suspend fun setForceVlc(on: Boolean)
}

/**
 * [EnginePrefs] on its own Preferences DataStore file (`filesDir/datastore/device.preferences_pb`, key
 * `force_vlc`), separate from the session file, so sign-out and server changes never touch it. Loaded
 * synchronously once at startup ([load]); loading never fails (a corrupt file is replaced by an empty
 * one, an unreadable one reads as off). [setForceVlc] updates memory only after the file write
 * succeeded; the write is not cancellable (leaving Ajustes mid-write still finishes it) and a write
 * IOException is logged and swallowed.
 */
class DataStoreEnginePrefs private constructor(
    private val store: DataStore<Preferences>,
    initial: Boolean,
    private val log: (String, Throwable?) -> Unit,
) : EnginePrefs {
    private val state = MutableStateFlow(initial)
    override val forceVlc: StateFlow<Boolean> = state.asStateFlow()

    override suspend fun setForceVlc(on: Boolean) = withContext(NonCancellable) {
        try {
            state.value = store.edit { it[FORCE_VLC] = on }[FORCE_VLC] ?: false
        } catch (e: IOException) {
            log("Device preferences write failed", e)
        }
    }

    companion object {
        private val FORCE_VLC = booleanPreferencesKey("force_vlc")

        private val defaultLog: (String, Throwable?) -> Unit = { msg, e -> runCatching { Log.w("HomeTvPrefs", msg, e) } }

        /** A corrupt file is replaced by an empty one (→ off). */
        fun dataStore(file: File, scope: CoroutineScope): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { file },
        )

        /** The first read; an IOException is logged and reads as off (later writes still go to [dataStore]). */
        suspend fun load(dataStore: DataStore<Preferences>, log: (String, Throwable?) -> Unit = defaultLog): DataStoreEnginePrefs {
            val initial = try {
                dataStore.data.first()[FORCE_VLC] ?: false
            } catch (e: IOException) {
                log("Device preferences unreadable, using defaults", e)
                false
            }
            return DataStoreEnginePrefs(dataStore, initial, log)
        }
    }
}

/** Tests and the container default. */
class InMemoryEnginePrefs(initial: Boolean = false) : EnginePrefs {
    private val state = MutableStateFlow(initial)
    override val forceVlc: StateFlow<Boolean> = state.asStateFlow()

    override suspend fun setForceVlc(on: Boolean) {
        state.value = on
    }
}
