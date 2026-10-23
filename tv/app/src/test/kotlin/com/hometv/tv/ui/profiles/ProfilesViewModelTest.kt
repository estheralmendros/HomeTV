package com.hometv.tv.ui.profiles

import com.hometv.tv.R
import com.hometv.tv.core.api.Avatar
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.api.ProfileList
import com.hometv.tv.core.api.ServerInfo
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.text.UiText
import java.net.ConnectException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class ProfilesViewModelTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    private val repos = TestRepos().apply {
        api.onProfiles = {
            ProfileList(listOf(Profile(3, "esther", "avatar-01", is_admin = true), Profile(5, "Javi", "avatar-02"), Profile(8, "Kids", "avatar-09")))
        }
        api.onInfo = { ServerInfo(name = "HomeTV", avatars = listOf(Avatar("avatar-01", "#E53935"), Avatar("avatar-02", "#1E88E5"))) }
    }
    private val chosen = mutableListOf<Profile>()

    private fun vm(expired: Boolean = false) = ProfilesViewModel(repos.profiles, repos.store, onSignedIn = { p, _ -> chosen += p }, expired = expired)

    @Test
    fun `lists profiles with avatar colors and initials, focus on the last used profile`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val content = vm.state.value as ProfilesUiState.Content
        assertEquals(listOf("E", "J", "K"), content.profiles.map { it.initial })
        assertEquals(listOf("#E53935", "#1E88E5", null), content.profiles.map { it.color })
        assertEquals(5L, content.initialFocusId)
        assertNull(content.message)
    }

    @Test
    fun `without a remembered profile the first one gets the focus`() = runTest(main.dispatcher) {
        repos.store.saveSession(99, "tok", java.time.Instant.parse("2026-12-20T00:00:00Z"))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(3L, (vm.state.value as ProfilesUiState.Content).initialFocusId)
    }

    @Test
    fun `any profile, admin included, signs in with one session request and no PIN`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.select(3)
        assertEquals(ProfilesEvent.SignedIn, vm.events.first())
        assertEquals(1, repos.api.count("POST /api/profiles/3/session"))
        assertEquals("tok-3", repos.store.session.value.token)
        assertEquals(3L, repos.store.session.value.profileId)
        assertEquals(listOf(3L), chosen.map { it.id })
    }

    @Test
    fun `a gone profile refreshes the list and says so`() = runTest(main.dispatcher) {
        repos.api.onCreateSession = { throw httpError(404, "profile_not_found") }
        val vm = vm()
        advanceUntilIdle()
        vm.select(8)
        advanceUntilIdle()
        assertEquals(2, repos.api.count("GET /api/profiles"))
        assertEquals(UiText.Res(R.string.profiles_gone), (vm.state.value as ProfilesUiState.Content).message)
    }

    @Test
    fun `the expired banner is shown when the session was lost`() = runTest(main.dispatcher) {
        val vm = vm(expired = true)
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.profiles_session_expired), (vm.state.value as ProfilesUiState.Content).message)
    }

    @Test
    fun `an unreachable server shows the Connection Error and Retry reloads`() = runTest(main.dispatcher) {
        repos.api.onProfiles = { throw ConnectException("refused") }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(ScreenError.Connection("hometv.tail1234.ts.net", tls = false), (vm.state.value as ProfilesUiState.Error).error)
        repos.api.onProfiles = { ProfileList(listOf(Profile(5, "Javi", "avatar-02"))) }
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf(5L), (vm.state.value as ProfilesUiState.Content).profiles.map { it.id })
    }
}
