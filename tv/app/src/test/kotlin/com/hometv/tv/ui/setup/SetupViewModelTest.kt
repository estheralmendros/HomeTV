package com.hometv.tv.ui.setup

import com.hometv.tv.R
import com.hometv.tv.core.api.ServerInfo
import com.hometv.tv.core.data.InMemorySessionStore
import com.hometv.tv.core.data.ServerValidation
import com.hometv.tv.core.data.Session
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.ui.text.UiText
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class SetupViewModelTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    private val expires = Instant.parse("2026-12-01T00:00:00Z")
    private val validated = mutableListOf<String>()
    private var answer: suspend (String) -> ServerValidation = { ServerValidation.Ok(ServerInfo(name = "HomeTV")) }
    private var serverChanges = 0

    private fun vm(store: InMemorySessionStore = InMemorySessionStore(), debug: Boolean = false) = SetupViewModel(
        validate = { url -> validated += url; answer(url) },
        store = store,
        onServerChanged = { serverChanges++ },
        allowDebugCleartext = debug,
    )

    @Test
    fun `the field starts with https and keeps the stored URL when opened from Settings`() {
        assertEquals("https://", vm().state.value.url)
        assertEquals("https://a.ts.net", vm(InMemorySessionStore(Session(serverUrl = "https://a.ts.net"))).state.value.url)
    }

    @Test
    fun `a URL without scheme is normalized before validation and stored on Ok`() = runTest(main.dispatcher) {
        val store = InMemorySessionStore()
        val vm = vm(store)
        vm.onUrlChange("  HomeTV.Tail1234.ts.net/some/path ")
        vm.submit()
        val event = vm.events.first()
        assertEquals(listOf("https://hometv.tail1234.ts.net"), validated)
        assertEquals("https://hometv.tail1234.ts.net", store.session.value.serverUrl)
        assertEquals(SetupEvent.Connected(changed = true), event)
        assertEquals(1, serverChanges)
    }

    @Test
    fun `invalid input shows a catalog message under the field and validates nothing`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onUrlChange("https://")
        vm.submit()
        assertEquals(UiText.Res(R.string.setup_error_empty), vm.state.value.error)
        vm.onUrlChange("http://hometv.tail1234.ts.net")
        vm.submit()
        assertEquals(UiText.Res(R.string.setup_error_cleartext), vm.state.value.error)
        vm.onUrlChange("ftp://x")
        vm.submit()
        assertEquals(UiText.Res(R.string.setup_error_invalid), vm.state.value.error)
        assertEquals(emptyList<String>(), validated)
    }

    @Test
    fun `debug builds accept the emulator loopback over http`() = runTest(main.dispatcher) {
        val vm = vm(debug = true)
        vm.onUrlChange("http://10.0.2.2:8000")
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("http://10.0.2.2:8000"), validated)
    }

    @Test
    fun `validation outcomes map to the section 1 messages and keep the URL unstored`() = runTest(main.dispatcher) {
        val cases = listOf(
            ServerValidation.Unreachable(IOException("x")) to R.string.setup_error_unreachable,
            ServerValidation.TlsError to R.string.setup_error_tls,
            ServerValidation.NotHomeTvServer to R.string.setup_error_not_hometv,
            ServerValidation.ServerUnhealthy to R.string.setup_error_unhealthy,
        )
        cases.forEach { (outcome, message) ->
            val store = InMemorySessionStore()
            answer = { outcome }
            val vm = vm(store)
            vm.onUrlChange("hometv.ts.net")
            vm.submit()
            advanceUntilIdle()
            assertEquals(UiText.Res(message), vm.state.value.error)
            assertFalse(vm.state.value.validating)
            assertNull(store.session.value.serverUrl)
        }
    }

    @Test
    fun `the field shows validating while the request runs`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<ServerValidation>()
        answer = { gate.await() }
        val vm = vm()
        vm.onUrlChange("hometv.ts.net")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.validating)
        gate.complete(ServerValidation.NotHomeTvServer)
        advanceUntilIdle()
        assertFalse(vm.state.value.validating)
    }

    @Test
    fun `needs_bootstrap shows the web-app message and Retry validates again`() = runTest(main.dispatcher) {
        val store = InMemorySessionStore()
        answer = { ServerValidation.Ok(ServerInfo(name = "HomeTV", needs_bootstrap = true)) }
        val vm = vm(store)
        vm.onUrlChange("hometv.ts.net")
        vm.submit()
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.setup_needs_bootstrap, listOf("https://hometv.ts.net")), vm.state.value.error)
        assertTrue(vm.state.value.needsBootstrap)
        assertNull(store.session.value.serverUrl)

        answer = { ServerValidation.Ok(ServerInfo(name = "HomeTV")) }
        vm.submit()
        assertEquals(SetupEvent.Connected(changed = true), vm.events.first())
        assertEquals(2, validated.size)
    }

    @Test
    fun `a different URL clears the session, the same URL keeps token and cache`() = runTest(main.dispatcher) {
        val store = InMemorySessionStore(Session("https://a.ts.net", 5, "tok", expires))
        val same = vm(store)
        same.submit()
        assertEquals(SetupEvent.Connected(changed = false), same.events.first())
        assertEquals("tok", store.session.value.token)
        assertEquals(0, serverChanges)

        val other = vm(store)
        other.onUrlChange("https://b.ts.net")
        other.submit()
        assertEquals(SetupEvent.Connected(changed = true), other.events.first())
        assertEquals(Session(serverUrl = "https://b.ts.net"), store.session.value)
        assertEquals(1, serverChanges)
    }
}
