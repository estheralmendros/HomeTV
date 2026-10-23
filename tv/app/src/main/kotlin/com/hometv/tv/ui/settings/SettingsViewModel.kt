package com.hometv.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.data.AccountState
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String = "",
    val profileName: String? = null,
    val appVersion: UiText,
    val serverVersion: UiText? = null,
    /** `tmdb_attribution` (Spanish since prd.md Rev 9), shown verbatim. */
    val tmdbAttribution: String? = null,
    /** "Vídeo: h264, hevc · Audio: aac, ac3, …". */
    val caps: UiText? = null,
)

/** Settings / About (prd3 §11): server, profile, app + server versions, TMDB notice and logo, caps summary. */
class SettingsViewModel(
    store: SessionStore,
    private val account: AccountState,
    private val caps: suspend () -> ClientCaps,
    versionName: String,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SettingsUiState(serverUrl = store.session.value.serverUrl.orEmpty(), appVersion = UiText.Res(R.string.settings_app_version, listOf(versionName))),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(account.profile, account.info) { p, i -> p to i }.collect { (profile, info) ->
                _state.update {
                    it.copy(
                        profileName = profile?.name,
                        serverVersion = info?.let { i -> UiText.Res(R.string.settings_server_version, listOf(i.name, i.version.orEmpty())) },
                        tmdbAttribution = info?.tmdb_attribution,
                    )
                }
            }
        }
        viewModelScope.launch { account.refresh() }
        viewModelScope.launch {
            // A codec scan failure (MediaCodecList) only hides the caps line; cancellation still propagates.
            val c = try {
                caps()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            _state.update { it.copy(caps = UiText.Res(R.string.settings_caps, listOf(c.video_codecs.joinToString(", "), c.audio_codecs.joinToString(", ")))) }
        }
    }
}
