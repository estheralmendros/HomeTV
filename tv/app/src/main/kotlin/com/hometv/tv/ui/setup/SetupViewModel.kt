package com.hometv.tv.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.data.ServerValidation
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.util.ServerUrl
import com.hometv.tv.core.util.ServerUrlError
import com.hometv.tv.core.util.ServerUrlResult
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupUiState(
    val url: String,
    val validating: Boolean = false,
    /** Shown under the field (the field keeps focus). */
    val error: UiText? = null,
    /** `info.needs_bootstrap`: the message offers [Reintentar]. */
    val needsBootstrap: Boolean = false,
)

sealed interface SetupEvent {
    /** The URL is stored; [changed] = it differs from the previous one (token, profile and Home cache were cleared). */
    data class Connected(val changed: Boolean) : SetupEvent
}

/** Server setup (prd3 §1): normalize, validate (`/api/health` then `/api/info`), store. */
class SetupViewModel(
    private val validate: suspend (String) -> ServerValidation,
    private val store: SessionStore,
    /** Clears the Home cache and the in-memory account when the server changes. */
    private val onServerChanged: suspend () -> Unit,
    private val allowDebugCleartext: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState(url = store.session.value.serverUrl ?: PREFILL))
    val state: StateFlow<SetupUiState> = _state.asStateFlow()
    private val _events = Channel<SetupEvent>(Channel.BUFFERED)
    val events: Flow<SetupEvent> = _events.receiveAsFlow()
    private var job: Job? = null

    fun onUrlChange(text: String) {
        _state.update { it.copy(url = text, error = null, needsBootstrap = false) }
    }

    /** [Conectar], the IME "Done" action and [Reintentar] after `needs_bootstrap`. */
    fun submit() {
        if (job?.isActive == true) return
        val url = when (val result = ServerUrl.normalize(_state.value.url, allowDebugCleartext)) {
            is ServerUrlResult.Invalid -> {
                _state.update { it.copy(error = UiText.Res(invalidMessage(result.error)), needsBootstrap = false) }
                return
            }
            is ServerUrlResult.Valid -> result.url
        }
        _state.update { it.copy(validating = true, error = null, needsBootstrap = false) }
        job = viewModelScope.launch {
            when (val outcome = validate(url)) {
                is ServerValidation.Ok -> if (outcome.info.needs_bootstrap) {
                    _state.update { it.copy(validating = false, needsBootstrap = true, error = UiText.Res(R.string.setup_needs_bootstrap, listOf(url))) }
                } else {
                    val changed = store.setServerUrl(url)
                    if (changed) onServerChanged()
                    _state.update { it.copy(validating = false) }
                    _events.send(SetupEvent.Connected(changed))
                }
                else -> _state.update { it.copy(validating = false, error = UiText.Res(failureMessage(outcome))) }
            }
        }
    }

    companion object {
        const val PREFILL = "https://"

        private fun invalidMessage(error: ServerUrlError) = when (error) {
            ServerUrlError.EMPTY -> R.string.setup_error_empty
            ServerUrlError.INVALID -> R.string.setup_error_invalid
            ServerUrlError.CLEARTEXT_NOT_ALLOWED -> R.string.setup_error_cleartext
        }

        private fun failureMessage(outcome: ServerValidation) = when (outcome) {
            is ServerValidation.Unreachable -> R.string.setup_error_unreachable
            ServerValidation.TlsError -> R.string.setup_error_tls
            ServerValidation.ServerUnhealthy -> R.string.setup_error_unhealthy
            else -> R.string.setup_error_not_hometv
        }
    }
}
