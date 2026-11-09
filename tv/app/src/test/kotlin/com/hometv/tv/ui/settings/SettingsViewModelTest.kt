package com.hometv.tv.ui.settings

import com.hometv.tv.R
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.api.ServerInfo
import com.hometv.tv.data.EnginePrefs
import com.hometv.tv.data.InMemoryEnginePrefs
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.ScriptedApi
import com.hometv.tv.testing.testContainer
import com.hometv.tv.testing.toMe
import com.hometv.tv.ui.text.UiText
import com.hometv.tv.core.api.UnlockResponse
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import java.net.ConnectException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class SettingsViewModelTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    @Test
    fun `shows the server, the profile, versions, the TMDB notice and the caps summary`() = runTest(main.dispatcher) {
        val api = ScriptedApi().apply {
            onInfo = { ServerInfo(name = "Casa", version = "1.2.0", tmdb_attribution = "Este producto usa la API de TMDB pero no está avalado ni certificado por TMDB.") }
            onMe = { com.hometv.tv.core.api.Me(5, "Esther", "avatar-05") }
        }
        val container = testContainer(api, backgroundScope)
        val vm = container.settingsViewModel()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("https://hometv.tail1234.ts.net", s.serverUrl)
        assertEquals("Esther", s.profileName)
        assertEquals(UiText.Res(R.string.settings_app_version, listOf("0.1.0")), s.appVersion)
        assertEquals(UiText.Res(R.string.settings_server_version, listOf("Casa", "1.2.0")), s.serverVersion)
        assertEquals("Este producto usa la API de TMDB pero no está avalado ni certificado por TMDB.", s.tmdbAttribution)
        assertEquals(UiText.Res(R.string.settings_caps, listOf("h264, hevc", "aac, mp3, pcm_s16le, opus, vorbis, flac, ac3")), s.caps)
    }

    /** The stored profile (5): `AccountState` applies only a `/api/me` of the stored profile. */
    private val admin = Profile(5, "Admin", "avatar-01", is_admin = true)
    private val viewer = Profile(5, "Invitado", "avatar-05")

    private fun kotlinx.coroutines.test.TestScope.settings(profile: Profile, unlock: suspend (String) -> UnlockResponse = { throw httpError(401, "invalid_pin") }): Pair<SettingsViewModel, ScriptedApi> {
        val api = ScriptedApi().apply {
            onMe = { profile.toMe() }
            onUnlock = unlock
        }
        val vm = testContainer(api, backgroundScope).settingsViewModel()
        advanceUntilIdle()
        return vm to api
    }

    private fun SettingsViewModel.enter(pin: String) = pin.forEach { onPinDigit(it.digitToInt()) }

    @Test
    fun `only admin profiles are offered the server change`() = runTest(main.dispatcher) {
        assertFalse(settings(viewer).first.state.value.canChangeServer)
        assertTrue(settings(admin).first.state.value.canChangeServer)
    }

    @Test
    fun `a viewer cannot open the PIN dialog`() = runTest(main.dispatcher) {
        val (vm, _) = settings(viewer)
        vm.onChangeServer()
        assertNull(vm.state.value.pin)
    }

    @Test
    fun `the right PIN closes the dialog and asks for the server change, discarding the admin token`() = runTest(main.dispatcher) {
        val (vm, api) = settings(admin) { pin -> if (pin == "1234") UnlockResponse("adm", "2026-10-23T18:00:00Z") else throw httpError(401, "invalid_pin") }
        vm.onChangeServer()
        assertEquals(PinUi(), vm.state.value.pin)
        vm.enter("123")
        assertEquals(3, vm.state.value.pin?.entered)
        vm.enter("4")
        advanceUntilIdle()
        assertNull(vm.state.value.pin)
        assertEquals(Unit, vm.changeServer.first())
        assertTrue("POST /api/admin/unlock" in api.calls)
    }

    @Test
    fun `a wrong PIN shows the message and clears the dots`() = runTest(main.dispatcher) {
        val (vm, _) = settings(admin)
        vm.onChangeServer()
        vm.enter("0000")
        advanceUntilIdle()
        assertEquals(PinUi(entered = 0, message = UiText.Res(R.string.pin_wrong)), vm.state.value.pin)
    }

    @Test
    fun `too many attempts lock the keypad with a countdown`() = runTest(main.dispatcher) {
        val (vm, api) = settings(admin) { throw httpError(429, "too_many_attempts", """{"retry_after_seconds": 42}""", retryAfter = "42") }
        vm.onChangeServer()
        vm.enter("0000")
        runCurrent()
        assertEquals(PinUi(entered = 0, message = UiText.Res(R.string.pin_locked, listOf("0:42")), locked = true), vm.state.value.pin)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(UiText.Res(R.string.pin_locked, listOf("0:41")), vm.state.value.pin?.message)
        val before = api.calls.count { it == "POST /api/admin/unlock" }
        vm.enter("1234")
        assertEquals(0, vm.state.value.pin?.entered)
        advanceTimeBy(41_000)
        runCurrent()
        assertEquals(PinUi(), vm.state.value.pin)
        assertEquals(before, api.calls.count { it == "POST /api/admin/unlock" })
    }

    @Test
    fun `an unreachable server shows the check failure`() = runTest(main.dispatcher) {
        val (vm, _) = settings(admin) { throw ConnectException("refused") }
        vm.onChangeServer()
        vm.enter("1234")
        advanceUntilIdle()
        assertEquals(PinUi(entered = 0, message = UiText.Res(R.string.pin_check_failed)), vm.state.value.pin)
    }

    @Test
    fun `delete removes the last digit and dismiss closes the dialog`() = runTest(main.dispatcher) {
        val (vm, _) = settings(admin)
        vm.onChangeServer()
        vm.enter("12")
        vm.onPinDelete()
        assertEquals(1, vm.state.value.pin?.entered)
        vm.onPinDismiss()
        assertNull(vm.state.value.pin)
    }

    @Test
    fun `Back during the PIN check cancels it - no server change, and a reopened dialog accepts digits`() = runTest(main.dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val (vm, _) = settings(admin) { pin -> gate.await(); if (pin == "1234") UnlockResponse("adm", "2026-10-23T18:00:00Z") else throw httpError(401, "invalid_pin") }
        val emissions = mutableListOf<Unit>()
        backgroundScope.launch { vm.changeServer.collect { emissions += it } }
        vm.onChangeServer()
        vm.enter("1234")
        runCurrent()
        vm.onPinDismiss()
        assertNull(vm.state.value.pin)

        vm.onChangeServer()
        vm.enter("12")
        assertEquals(2, vm.state.value.pin?.entered, "the cancelled check no longer blocks the keypad")
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(emissions.isEmpty(), "a cancelled check never opens Setup")
        assertEquals(PinUi(entered = 2), vm.state.value.pin, "no late result lands on the new dialog")
    }
    // ---- "Reproducción" (force VLC) and the licence notice ------------------------------------

    private fun kotlinx.coroutines.test.TestScope.engineSettings(
        profile: Profile,
        vlcAvailable: Boolean = true,
        prefs: EnginePrefs = InMemoryEnginePrefs(),
        notices: String = "THIRD PARTY NOTICES",
    ): SettingsViewModel {
        val api = ScriptedApi().apply { onMe = { profile.toMe() } }
        val vm = testContainer(api, backgroundScope, vlcAvailable = vlcAvailable, enginePrefs = prefs, notices = notices).settingsViewModel()
        advanceUntilIdle()
        return vm
    }

    /** Counts writes; [gate] parks them until completed. */
    private class RecordingPrefs : EnginePrefs {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(false)
        override val forceVlc: kotlinx.coroutines.flow.StateFlow<Boolean> = state
        val writes = mutableListOf<Boolean>()
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun setForceVlc(on: Boolean) {
            writes += on
            gate?.await()
            state.value = on
        }
    }

    @Test
    fun `AC-13_1 an admin with libVLC is offered force VLC, and toggling twice writes on then off`() = runTest(main.dispatcher) {
        val prefs = InMemoryEnginePrefs()
        val vm = engineSettings(admin, prefs = prefs)
        assertTrue(vm.state.value.showForceVlc)
        assertFalse(vm.state.value.forceVlc)

        vm.onToggleForceVlc()
        advanceUntilIdle()
        assertTrue(prefs.forceVlc.value)
        assertTrue(vm.state.value.forceVlc)

        vm.onToggleForceVlc()
        advanceUntilIdle()
        assertFalse(prefs.forceVlc.value)
        assertFalse(vm.state.value.forceVlc)
    }

    @Test
    fun `AC-13_1 a viewer, or a device without libVLC, is not offered force VLC and the toggle writes nothing`() = runTest(main.dispatcher) {
        for ((profile, vlc) in listOf(viewer to true, admin to false)) {
            val prefs = RecordingPrefs()
            val vm = engineSettings(profile, vlcAvailable = vlc, prefs = prefs)
            assertFalse(vm.state.value.showForceVlc, "$profile vlc=$vlc")
            vm.onToggleForceVlc()
            advanceUntilIdle()
            assertTrue(prefs.writes.isEmpty(), "$profile vlc=$vlc")
        }
    }

    @Test
    fun `a second press while the write is pending is ignored`() = runTest(main.dispatcher) {
        val prefs = RecordingPrefs().apply { gate = kotlinx.coroutines.CompletableDeferred() }
        val vm = engineSettings(admin, prefs = prefs)

        vm.onToggleForceVlc()
        runCurrent()
        vm.onToggleForceVlc()
        runCurrent()
        assertEquals(listOf(true), prefs.writes)

        prefs.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.forceVlc)
        prefs.gate = null
        vm.onToggleForceVlc()
        advanceUntilIdle()
        assertEquals(listOf(true, false), prefs.writes)
    }

    @Test
    fun `the stored preference is shown`() = runTest(main.dispatcher) {
        assertTrue(engineSettings(admin, prefs = InMemoryEnginePrefs(initial = true)).state.value.forceVlc)
    }

    @Test
    fun `the licence notice opens with the notice text and closes`() = runTest(main.dispatcher) {
        val vm = engineSettings(viewer, vlcAvailable = false, notices = "LGPL text")
        assertNull(vm.state.value.licenses)

        vm.onOpenLicenses()
        advanceUntilIdle()
        assertEquals("LGPL text", vm.state.value.licenses)

        vm.onCloseLicenses()
        assertNull(vm.state.value.licenses)
    }

    @Test
    fun `a notice that cannot be read is logged and opens nothing`() = runTest(main.dispatcher) {
        val c = testContainer(ScriptedApi().apply { onMe = { admin.toMe() } }, backgroundScope)
        val logged = mutableListOf<Throwable?>()
        val vm = SettingsViewModel(
            c.store, c.account, c.profiles, c::deviceCaps, "0.1.0",
            notices = { throw java.io.IOException("missing raw resource") },
            log = { _, e -> logged += e },
        )
        advanceUntilIdle()

        vm.onOpenLicenses()
        advanceUntilIdle()

        assertNull(vm.state.value.licenses)
        assertTrue(logged.single() is java.io.IOException)
    }

    @Test
    fun `a second open while the notice loads, or while it is shown, reads nothing more`() = runTest(main.dispatcher) {
        val c = testContainer(ScriptedApi().apply { onMe = { admin.toMe() } }, backgroundScope)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var reads = 0
        val vm = SettingsViewModel(
            c.store, c.account, c.profiles, c::deviceCaps, "0.1.0",
            notices = {
                reads++
                gate.await()
                "LGPL text"
            },
            log = { _, _ -> },
        )
        advanceUntilIdle()

        vm.onOpenLicenses()
        runCurrent()
        vm.onOpenLicenses()
        runCurrent()
        assertEquals(1, reads, "a quick second OK while loading")
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("LGPL text", vm.state.value.licenses)

        vm.onOpenLicenses()
        advanceUntilIdle()
        assertEquals(1, reads, "already shown")

        vm.onCloseLicenses()
        vm.onOpenLicenses()
        advanceUntilIdle()
        assertEquals(2, reads, "a reopen after closing reads again")
        assertEquals("LGPL text", vm.state.value.licenses)
    }
}
