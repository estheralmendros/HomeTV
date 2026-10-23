package com.hometv.tv.data

import com.hometv.tv.core.data.Session
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Preferences DataStore keys `server_url`, `profile_id`, `token`, `token_expires_at` (prd3 Persistence). */
class DataStoreSessionStoreTest {
    private val dir: File = Files.createTempDirectory("session").toFile()
    private val file = File(dir, "session.preferences_pb")
    private val scopes = mutableListOf<CoroutineScope>()
    private val expires = Instant.parse("2026-11-20T00:00:00Z")

    private fun open(): DataStoreSessionStore = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        DataStoreSessionStore.load(DataStoreSessionStore.dataStore(file, scope))
    }

    /** Only one DataStore may be active per file: close the previous ones before reopening. */
    private fun reopen(): DataStoreSessionStore {
        runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }
        scopes.clear()
        return open()
    }

    @AfterEach
    fun tearDown() {
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
        store.clearToken()
        assertEquals(Session("https://a.ts.net", 7, null, null), store.session.value)
        assertEquals(Session("https://a.ts.net", 7, null, null), reopen().session.value)
    }
}
