package com.hometv.tv.data

import com.hometv.tv.core.api.RatingPrompt
import com.hometv.tv.core.data.BrowseSort
import com.hometv.tv.core.data.Session
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.data.WatchlistSort
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.browse.BrowseSortMemory
import com.hometv.tv.ui.navigation.Library
import com.hometv.tv.ui.watchlist.WatchlistMemory
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** "Salir": everything a viewer leaves behind on the device is forgotten; the server URL stays. */
class SignOutTest {
    private val dune = RatingPrompt("film", 12, "Dune")

    /** Delegates to the real store; [beforeSignOut] runs first (record, gate or fail). */
    private class RecordingStore(private val inner: SessionStore, private val beforeSignOut: suspend () -> Unit) : SessionStore {
        override val session get() = inner.session
        override suspend fun setServerUrl(url: String) = inner.setServerUrl(url)
        override suspend fun saveSession(profileId: Long, token: String, expiresAt: Instant) = inner.saveSession(profileId, token, expiresAt)
        override suspend fun signOut() {
            beforeSignOut()
            inner.signOut()
        }
        override suspend fun saveRenewedSession(profileId: Long, token: String, expiresAt: Instant) = inner.saveRenewedSession(profileId, token, expiresAt)
        override suspend fun clearToken(profileId: Long) = inner.clearToken(profileId)
    }

    private inner class Fixture(scope: TestScope, beforeSignOut: suspend Fixture.() -> Unit = {}) {
        val repos = TestRepos(appScope = scope.backgroundScope)
        val account = AccountState(repos.profiles)
        val browseSorts = BrowseSortMemory().apply {
            set(Library.FILMS, BrowseSort.YEAR_DESC)
            set(Library.SHOWS, BrowseSort.ADDED_DESC)
        }
        val watchlistMemory = WatchlistMemory().apply { sort = WatchlistSort.TITLE }
        val logged = mutableListOf<Throwable>()
        val store = RecordingStore(repos.store) { beforeSignOut() }
        val signOut = SignOut(store, repos.ratingPrompts, account, browseSorts, watchlistMemory, log = { logged += it })

        suspend fun signedIn() = apply {
            account.refresh()
            repos.ratingPrompts.offer(5, dune)
        }
    }

    @Test
    fun `everything is reset and the session keeps only the server`() = runTest {
        val f = Fixture(this).signedIn()
        assertTrue(f.account.profile.value != null && f.account.info.value != null)

        f.signOut()

        assertEquals(Session(serverUrl = TestRepos.SERVER), f.repos.store.session.value)
        assertNull(f.repos.ratingPrompts.pending.value)
        assertNull(f.account.profile.value)
        assertNull(f.account.info.value)
        assertEquals(BrowseSort.TITLE_ASC, f.browseSorts.get(Library.FILMS))
        assertEquals(BrowseSort.TITLE_ASC, f.browseSorts.get(Library.SHOWS))
        assertEquals(WatchlistSort.ADDED, f.watchlistMemory.sort)
        assertTrue(f.logged.isEmpty())
    }

    @Test
    fun `prompts, account and memories are cleared before the store signs out`() = runTest {
        val seen = mutableListOf<String>()
        val f = Fixture(this) {
            seen += "pending=${repos.ratingPrompts.pending.value} profile=${account.profile.value} films=${browseSorts.get(Library.FILMS)} list=${watchlistMemory.sort}"
        }.signedIn()

        f.signOut()

        assertEquals(listOf("pending=null profile=null films=TITLE_ASC list=ADDED"), seen)
    }

    @Test
    fun `an IOException from the store is logged, not thrown`() = runTest {
        val f = Fixture(this) { throw IOException("disk full") }.signedIn()

        f.signOut()

        assertTrue(f.logged.single() is IOException)
        assertNull(f.repos.ratingPrompts.pending.value)
    }

    @Test
    fun `cancelling the caller during the write still completes it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val f = Fixture(this) {
            entered.complete(Unit)
            gate.await()
        }.signedIn()

        val job = launch { f.signOut() }
        runCurrent()
        assertTrue(entered.isCompleted)
        job.cancel()
        gate.complete(Unit)
        runCurrent()

        assertEquals(Session(serverUrl = TestRepos.SERVER), f.repos.store.session.value)
    }
}
