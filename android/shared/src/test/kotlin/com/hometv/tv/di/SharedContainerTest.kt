package com.hometv.tv.di

import com.hometv.tv.core.api.ApiClients
import com.hometv.tv.core.data.InMemorySessionStore
import com.hometv.tv.core.data.Session
import com.hometv.tv.data.HomeCache
import com.hometv.tv.testing.FakeCodecs
import com.hometv.tv.testing.ScriptedApi
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.settings.SettingsPolicy
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SharedContainerTest {
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun container(policy: SettingsPolicy? = null): SharedContainer {
        val store = InMemorySessionStore(Session(serverUrl = TestRepos.SERVER, profileId = 5, token = "tok"))
        val homeCache = HomeCache(Files.createTempDirectory("home_cache").toFile())
        val config = AppConfig(versionName = "0.1.0", allowDebugCleartext = true)
        val clients = { url: String -> ApiClients(url, OkHttpClient(), OkHttpClient(), ScriptedApi()) }
        return if (policy == null) {
            SharedContainer(store, homeCache, scope, config, clients, OkHttpClient(), FakeCodecs())
        } else {
            SharedContainer(store, homeCache, scope, config, clients, OkHttpClient(), FakeCodecs(), settingsPolicy = policy)
        }
    }

    @Test
    fun `the settings policy defaults to TV`() {
        val container = container()
        assertEquals(SettingsPolicy.TV, container.settingsPolicy)
        assertEquals(SettingsPolicy.TV, container.settingsViewModel().policy)
    }

    @Test
    fun `the settings view model receives the container's policy`() {
        assertEquals(SettingsPolicy.PERSONAL, container(SettingsPolicy.PERSONAL).settingsViewModel().policy)
    }
}
