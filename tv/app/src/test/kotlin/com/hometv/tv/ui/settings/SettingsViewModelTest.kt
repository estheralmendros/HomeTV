package com.hometv.tv.ui.settings

import com.hometv.tv.R
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.api.ServerInfo
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.ScriptedApi
import com.hometv.tv.testing.testContainer
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class SettingsViewModelTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    @Test
    fun `shows the server, the profile, versions, the TMDB notice and the caps summary`() = runTest(main.dispatcher) {
        val api = ScriptedApi().apply {
            onInfo = { ServerInfo(name = "Casa", version = "1.2.0", tmdb_attribution = "Este producto usa la API de TMDB pero no está avalado ni certificado por TMDB.") }
            onMe = { Profile(5, "Esther", "avatar-05") }
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
}
