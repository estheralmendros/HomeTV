package com.hometv.tv.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hometv.tv.core.data.Session
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Preferences DataStore keys `server_url`, `profile_id`, `token`, `token_expires_at`. */
class DataStoreSessionStoreTest {
    private val dir: File = Files.createTempDirectory("session").toFile()
    private val file = File(dir, "session.preferences_pb")
    private val scopes = mutableListOf<CoroutineScope>()
    private val expires = Instant.parse("2026-11-20T00:00:00Z")

    private val url = "https://a.ts.net"
    private val later = Instant.parse("2026-12-20T00:00:00Z")
    private val gate = CompletableDeferred<Unit>()

    private fun open(): DataStoreSessionStore = runBlocking {
        DataStoreSessionStore.load(fileStore())
    }

    private fun fileStore(): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return DataStoreSessionStore.dataStore(file, scope)
    }

    /** A store signed in as profile 5 on [url]. */
    private suspend fun signedIn(store: DataStoreSessionStore = open()) = store.apply {
        setServerUrl(url)
        saveSession(5, "tok", expires)
    }

    /**
     * Delegates to the real file store; once [park] is set, the next `updateData` parks on [gate]
     * after the delegate returned (the file is already written) and completes [delegateReturned] first.
     */
    private class ParkingDataStore(private val inner: DataStore<Preferences>, private val gate: CompletableDeferred<Unit>) : DataStore<Preferences> {
        @Volatile var park = false
        val delegateReturned = CompletableDeferred<Unit>()
        override val data: Flow<Preferences> get() = inner.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val result = inner.updateData(transform)
            if (park) {
                park = false
                delegateReturned.complete(Unit)
                gate.await()
            }
            return result
        }
    }

    /** Signed-in data whose writes all fail. */
    private class FailingDataStore(initial: Preferences) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(initial)
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk full")
    }

    /** Only one DataStore may be active per file: close the previous ones before reopening. */
    private fun reopen(): DataStoreSessionStore {
        runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }
        scopes.clear()
        return open()
    }

    @AfterEach
    fun tearDown() {
        gate.complete(Unit)
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    @Test
    fun `an empty store loads an empty session synchronously readable`() {
        assertEquals(Session(), open().session.value)
    }

    @Test
    fun `session values persist across restarts`() = runBlocking {
        val store = open()
        assertTrue(store.setServerUrl("https://hometv.tail1234.ts.net"))
        store.saveSession(5, "tok", expires)
        assertEquals(Session("https://hometv.tail1234.ts.net", 5, "tok", expires), store.session.value)

        assertEquals(Session("https://hometv.tail1234.ts.net", 5, "tok", expires), reopen().session.value)
    }

    @Test
    fun `a different server URL clears token, profile and expiry and the same URL keeps them`() = runBlocking {
        val store = open()
        store.setServerUrl("https://a.ts.net")
        store.saveSession(5, "tok", expires)
        assertFalse(store.setServerUrl("https://a.ts.net"))
        assertEquals("tok", store.session.value.token)
        assertTrue(store.setServerUrl("https://b.ts.net"))
        assertEquals(Session(serverUrl = "https://b.ts.net"), store.session.value)
        assertEquals(Session(serverUrl = "https://b.ts.net"), reopen().session.value)
    }

    @Test
    fun `clearToken keeps the server and the last profile for the Picker focus`() = runBlocking {
        val store = open()
        store.setServerUrl("https://a.ts.net")
        store.saveSession(7, "tok", expires)
        assertTrue(store.clearToken(7))
        assertEquals(Session("https://a.ts.net", 7, null, null), store.session.value)
        assertEquals(Session("https://a.ts.net", 7, null, null), reopen().session.value)
    }

    @Test
    fun `signOut keeps only the server URL, also after reopen`() = runBlocking {
        val store = signedIn()

        store.signOut()

        assertEquals(Session(serverUrl = url), store.session.value)
        assertEquals(Session(serverUrl = url), reopen().session.value)
    }

    @Test
    fun `a failed signOut write still clears the in-memory session`() = runBlocking {
        val prefs = preferencesOf(
            stringPreferencesKey("server_url") to url,
            longPreferencesKey("profile_id") to 5L,
            stringPreferencesKey("token") to "tok",
            stringPreferencesKey("token_expires_at") to expires.toString(),
        )
        val store = DataStoreSessionStore.load(FailingDataStore(prefs))
        assertEquals(Session(url, 5, "tok", expires), store.session.value)

        assertThrows<IOException> { store.signOut() }

        assertEquals(Session(serverUrl = url), store.session.value)
    }

    @Test
    fun `saveRenewedSession replaces token and expiry for the same profile`() = runBlocking {
        val store = signedIn()

        assertTrue(store.saveRenewedSession(5, "new", later))

        assertEquals(Session(url, 5, "new", later), store.session.value)
        assertEquals(Session(url, 5, "new", later), reopen().session.value)
    }

    @Test
    fun `saveRenewedSession refuses another profile`() = runBlocking {
        val store = signedIn().apply { saveSession(7, "tok7", expires) }

        assertFalse(store.saveRenewedSession(5, "new", later))

        assertEquals(Session(url, 7, "tok7", expires), store.session.value)
        assertEquals(Session(url, 7, "tok7", expires), reopen().session.value)
    }

    @Test
    fun `saveRenewedSession refuses a signed-out store`() = runBlocking {
        val store = signedIn().apply { signOut() }

        assertFalse(store.saveRenewedSession(5, "new", later))

        assertEquals(Session(serverUrl = url), store.session.value)
        assertEquals(Session(serverUrl = url), reopen().session.value)
    }

    @Test
    fun `clearToken refuses another profile and a signed-out store`() = runBlocking {
        val store = signedIn().apply { saveSession(7, "tok7", expires) }
        assertFalse(store.clearToken(5))
        assertEquals(Session(url, 7, "tok7", expires), store.session.value)

        store.signOut()
        assertFalse(store.clearToken(5))
        assertEquals(Session(serverUrl = url), store.session.value)
        assertEquals(Session(serverUrl = url), reopen().session.value)
    }

    /** Runs [write] for profile 5 with its memory update held after the file write, then [meanwhile], then releases it. */
    private suspend fun lateContinuation(
        write: suspend DataStoreSessionStore.() -> Boolean,
        meanwhile: suspend DataStoreSessionStore.() -> Unit,
    ): Pair<Boolean, DataStoreSessionStore> {
        val parking = ParkingDataStore(fileStore(), gate)
        val store = signedIn(DataStoreSessionStore.load(parking))
        parking.park = true
        val result = kotlinx.coroutines.coroutineScope {
            val pending = async(Dispatchers.IO) { store.write() }
            parking.delegateReturned.await()
            store.meanwhile()
            gate.complete(Unit)
            pending.await()
        }
        return result to store
    }

    @Test
    fun `a late memory update after signOut leaves the store signed out`() = runBlocking {
        val (saved, store) = lateContinuation({ saveRenewedSession(5, "new", later) }, { signOut() })

        assertFalse(saved)
        assertEquals(Session(serverUrl = url), store.session.value)
        assertEquals(Session(serverUrl = url), reopen().session.value)
    }

    @Test
    fun `a late memory update after a profile switch keeps the new profile`() = runBlocking {
        val (saved, store) = lateContinuation({ saveRenewedSession(5, "new", later) }, { saveSession(7, "tok7", expires) })

        assertFalse(saved)
        assertEquals(Session(url, 7, "tok7", expires), store.session.value)
    }

    @Test
    fun `a late clearToken after signOut leaves the store signed out`() = runBlocking {
        val (cleared, store) = lateContinuation({ clearToken(5) }, { signOut() })

        assertFalse(cleared)
        assertEquals(Session(serverUrl = url), store.session.value)
        assertEquals(Session(serverUrl = url), reopen().session.value)
    }

    @Test
    fun `a late clearToken after a profile switch keeps the new profile`() = runBlocking {
        val (cleared, store) = lateContinuation({ clearToken(5) }, { saveSession(7, "tok7", expires) })

        assertFalse(cleared)
        assertEquals(Session(url, 7, "tok7", expires), store.session.value)
    }
}
