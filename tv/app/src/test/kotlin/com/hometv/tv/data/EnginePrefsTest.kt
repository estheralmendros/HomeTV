package com.hometv.tv.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.hometv.tv.testing.ScriptedApi
import com.hometv.tv.testing.testContainer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The device file `device.preferences_pb`, key `force_vlc`: "Usar siempre VLC (experimental)". */
class EnginePrefsTest {
    private val dir: File = Files.createTempDirectory("device").toFile()
    private val file = File(dir, "device.preferences_pb")
    private val scopes = mutableListOf<CoroutineScope>()
    private val logged = mutableListOf<Pair<String, Throwable?>>()
    private val log: (String, Throwable?) -> Unit = { msg, e -> logged += msg to e }

    private fun fileStore(): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return DataStoreEnginePrefs.dataStore(file, scope)
    }

    private fun open(): DataStoreEnginePrefs = runBlocking { DataStoreEnginePrefs.load(fileStore(), log) }

    /** Only one DataStore may be active per file: close the previous ones before reopening. */
    private fun reopen(): DataStoreEnginePrefs {
        runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }
        scopes.clear()
        return open()
    }

    /** An in-memory DataStore: [failWrites] makes `updateData` throw, [gate] parks it before applying. */
    private class MemoryDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val current = MutableStateFlow(initial)
        var failWrites = false
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        override val data: Flow<Preferences> get() = current
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            entered.complete(Unit)
            gate?.await()
            if (failWrites) throw IOException("disk full")
            return transform(current.value).also { current.value = it }
        }
    }

    @AfterEach
    fun tearDown() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    @Test
    fun `AC-12_1 a fresh file is off, a write turns it on and survives a reload`() = runBlocking {
        val prefs = open()
        assertFalse(prefs.forceVlc.value)

        prefs.setForceVlc(true)

        assertTrue(prefs.forceVlc.value)
        assertTrue(reopen().forceVlc.value)
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `AC-12_2 signing out and changing the server keep the device preference`() = runTest {
        val prefs = runBlocking { DataStoreEnginePrefs.load(fileStore(), log) }
        runBlocking { prefs.setForceVlc(true) }
        val container = testContainer(ScriptedApi(), backgroundScope, enginePrefs = prefs)

        container.signOut()
        container.store.setServerUrl("https://other.tail1234.ts.net")
        container.onServerChanged()

        assertTrue(container.enginePrefs.forceVlc.value)
        assertTrue(reopen().forceVlc.value)
    }

    @Test
    fun `AC-12_3 a failing write is logged and leaves the value unchanged`() = runTest {
        val store = MemoryDataStore().apply { failWrites = true }
        val prefs = DataStoreEnginePrefs.load(store, log)

        prefs.setForceVlc(true)

        assertFalse(prefs.forceVlc.value)
        assertTrue(logged.single().second is IOException)
    }

    @Test
    fun `AC-12_4 a file of garbage bytes loads as off and a later write replaces it`() = runBlocking {
        file.writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, 0x42, 0x0a, 0x01, 0x02, 0x03))

        val prefs = open()
        assertFalse(prefs.forceVlc.value)

        prefs.setForceVlc(true)
        assertTrue(prefs.forceVlc.value, "write failed: ${logged.map { it.second?.suppressed?.toList() }}")
        assertTrue(reopen().forceVlc.value)
    }

    @Test
    fun `AC-12_4 a data flow that throws IOException loads as off, logs once, and writes still work`() = runTest {
        val inner = MemoryDataStore()
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("unreadable") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = inner.updateData(transform)
        }

        val prefs = DataStoreEnginePrefs.load(store, log)

        assertFalse(prefs.forceVlc.value)
        assertEquals(1, logged.size)
        assertTrue(logged.single().second is IOException)
        prefs.setForceVlc(true)
        assertTrue(prefs.forceVlc.value)
        assertEquals(1, logged.size)
    }

    @Test
    fun `a write whose caller is cancelled still completes and updates memory`() = runTest {
        val store = MemoryDataStore().apply { gate = CompletableDeferred() }
        val prefs = DataStoreEnginePrefs.load(store, log)

        val job = launch { prefs.setForceVlc(true) }
        runCurrent()
        assertTrue(store.entered.isCompleted)
        job.cancel()
        store.gate!!.complete(Unit)
        runCurrent()

        assertTrue(prefs.forceVlc.value)
    }

    @Test
    fun `in-memory prefs start from the initial value and follow writes`() = runTest {
        val prefs = InMemoryEnginePrefs(initial = true)
        assertTrue(prefs.forceVlc.value)
        prefs.setForceVlc(false)
        assertFalse(prefs.forceVlc.value)
    }
}
