package com.hometv.tv.data

import com.hometv.tv.core.api.Me
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.api.SubtitleStyle
import com.hometv.tv.core.playback.SubtitleColor
import com.hometv.tv.core.playback.SubtitleLook
import com.hometv.tv.core.playback.SubtitleSize
import com.hometv.tv.testing.TestRepos
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The signed-in profile, the server info and the profile's subtitle style (`/api/me`), in memory. */
class AccountStateTest {
    private val yellowLarge = SubtitleStyle(size = "large", color = "yellow")
    private val yellowLargeLook = SubtitleLook.DEFAULT.copy(size = SubtitleSize.LARGE, color = SubtitleColor.YELLOW)
    private val expires = Instant.parse("2026-12-20T00:00:00Z")

    private fun TestScope.fixture(): Pair<TestRepos, AccountState> {
        val repos = TestRepos(appScope = backgroundScope)
        return repos to AccountState(repos.profiles, repos.store, backgroundScope)
    }

    @Test
    fun `the style follows api me, a cold start with no profile loaded applies the Me of the stored profile`() = runTest {
        val (repos, account) = fixture()
        repos.api.onMe = { Me(5, "Esther", "avatar-05", subtitle_style = yellowLarge) }
        assertEquals(SubtitleLook.DEFAULT, account.subtitleStyle.value)
        assertNull(account.profile.value)
        account.refresh()
        assertEquals(Profile(5, "Esther", "avatar-05"), account.profile.value)
        assertEquals(yellowLargeLook, account.subtitleStyle.value)
    }

    @Test
    fun `a failed api me keeps the default, ensureLoaded retries while the style is not loaded`() = runTest {
        val (repos, account) = fixture()
        repos.api.onMe = { throw java.io.IOException("down") }
        account.ensureLoaded()
        assertEquals(SubtitleLook.DEFAULT, account.subtitleStyle.value)
        repos.api.onMe = { Me(5, "Esther", "avatar-05", subtitle_style = yellowLarge) }
        account.ensureLoaded()
        assertEquals(yellowLargeLook, account.subtitleStyle.value)
        // Loaded: no more requests.
        val calls = repos.api.count("GET /api/me")
        account.ensureLoaded()
        assertEquals(calls, repos.api.count("GET /api/me"))
    }

    @Test
    fun `clear resets the style to the default and forgets that it was loaded`() = runTest {
        val (repos, account) = fixture()
        repos.api.onMe = { Me(5, "Esther", "avatar-05", subtitle_style = yellowLarge) }
        account.refresh()
        account.clear()
        assertNull(account.profile.value)
        assertEquals(SubtitleLook.DEFAULT, account.subtitleStyle.value)
        account.ensureLoaded()
        assertEquals(yellowLargeLook, account.subtitleStyle.value)
    }

    @Test
    fun `signedIn with another profile resets the style and loads the new one's in appScope`() = runTest {
        val (repos, account) = fixture()
        repos.api.onMe = { Me(5, "Esther", "avatar-05", subtitle_style = yellowLarge) }
        account.refresh()
        repos.store.saveSession(6, "tok-6", expires)
        repos.api.onMe = { Me(6, "Invitado", "avatar-06", subtitle_style = SubtitleStyle(position = "high")) }
        val gate = CompletableDeferred<Unit>()
        val inner = repos.api.onMe
        repos.api.onMe = { gate.await(); inner() }
        account.signedIn(Profile(6, "Invitado", "avatar-06"), null)
        assertEquals(SubtitleLook.DEFAULT, account.subtitleStyle.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(SubtitleLook.DEFAULT.copy(position = com.hometv.tv.core.playback.SubtitlePosition.HIGH), account.subtitleStyle.value)
    }

    @Test
    fun `a Me of a previous profile landing after signedIn of another changes nothing`() = runTest {
        val (repos, account) = fixture()
        val gate = CompletableDeferred<Unit>()
        repos.api.onMe = { gate.await(); Me(5, "Esther", "avatar-05", subtitle_style = yellowLarge) }
        val late = launch { account.refresh() }
        runCurrent()
        repos.store.saveSession(6, "tok-6", expires)
        repos.api.onMe = { Me(6, "Invitado", "avatar-06") }
        account.signedIn(Profile(6, "Invitado", "avatar-06"), null)
        runCurrent()
        gate.complete(Unit)
        late.join()
        assertEquals(Profile(6, "Invitado", "avatar-06"), account.profile.value)
        assertEquals(SubtitleLook.DEFAULT, account.subtitleStyle.value)
    }

    @Test
    fun `a Me landing after sign-out applies nothing`() = runTest {
        val (repos, account) = fixture()
        val gate = CompletableDeferred<Unit>()
        repos.api.onMe = { gate.await(); Me(5, "Esther", "avatar-05", subtitle_style = yellowLarge) }
        val late = launch { account.refresh() }
        runCurrent()
        account.clear()
        repos.store.signOut()
        gate.complete(Unit)
        late.join()
        assertNull(account.profile.value)
        assertEquals(SubtitleLook.DEFAULT, account.subtitleStyle.value)
    }

    @Test
    fun `a Me requested before a local style set never reverts it`() = runTest {
        val (repos, account) = fixture()
        val gate = CompletableDeferred<Unit>()
        repos.api.onMe = { gate.await(); Me(5, "Esther", "avatar-05") }
        val refresh = launch { account.refresh() }
        runCurrent()
        account.setSubtitleStyle(yellowLargeLook)
        gate.complete(Unit)
        refresh.join()
        assertEquals(Profile(5, "Esther", "avatar-05"), account.profile.value)
        assertEquals(yellowLargeLook, account.subtitleStyle.value)
    }
}
