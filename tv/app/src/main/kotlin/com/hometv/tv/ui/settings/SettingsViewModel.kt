package com.hometv.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.data.PinCheck
import com.hometv.tv.core.data.ProfileRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.util.Format
import com.hometv.tv.data.AccountState
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String = "",
    val profileName: String? = null,
    val appVersion: UiText,
    val serverVersion: UiText? = null,
    /** `tmdb_attribution` (Spanish), shown verbatim. */
    val tmdbAttribution: String? = null,
    /** "Vídeo: h264, hevc · Audio: aac, ac3, …". */
    val caps: UiText? = null,
    /**
     * [Cambiar servidor] is offered to admin profiles only. Viewers never reach Settings
     * (no "Ajustes" tab; a demoted profile is sent back to Home), so this is a guard in depth.
     */
    val canChangeServer: Boolean = false,
    /** The admin PIN dialog, when open. */
    val pin: PinUi? = null,
)

/** The PIN dialog: how many digits are entered, the message under the dots, and the lockout. */
data class PinUi(val entered: Int = 0, val message: UiText? = null, val locked: Boolean = false)

/**
 * Settings / About: server, profile, app + server versions, TMDB notice and logo, caps
 * summary, and the server change (admin profiles only, behind the admin PIN, step 3).
 */
class SettingsViewModel(
    store: SessionStore,
    private val account: AccountState,
    private val profiles: ProfileRepository,
    private val caps: suspend () -> ClientCaps,
    versionName: String,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SettingsUiState(serverUrl = store.session.value.serverUrl.orEmpty(), appVersion = UiText.Res(R.string.settings_app_version, listOf(versionName))),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _changeServer = Channel<Unit>(Channel.BUFFERED)

    /** Emits once the admin PIN was accepted: the screen opens Setup. */
    val changeServer: Flow<Unit> = _changeServer.receiveAsFlow()

    private val digits = StringBuilder()
    private var countdown: Job? = null

    /** The PIN check in flight; Back on the dialog cancels it. */
    private var submitJob: Job? = null
    private val checking: Boolean get() = submitJob?.isActive == true

    /** [Cambiar servidor]: opens the admin PIN dialog (admins only; asked every time). */
    fun onChangeServer() {
        if (!_state.value.canChangeServer) return
        digits.clear()
        _state.update { it.copy(pin = PinUi()) }
    }

    /** A keypad or remote digit; the 4th digit submits. Ignored while checking or locked. */
    fun onPinDigit(digit: Int) {
        val pin = _state.value.pin ?: return
        if (pin.locked || checking || digits.length >= PIN_LENGTH) return
        digits.append(digit)
        _state.update { it.copy(pin = pin.copy(entered = digits.length, message = null)) }
        if (digits.length == PIN_LENGTH) submit(digits.toString())
    }

    fun onPinDelete() {
        val pin = _state.value.pin ?: return
        if (pin.locked || checking || digits.isEmpty()) return
        digits.setLength(digits.length - 1)
        _state.update { it.copy(pin = pin.copy(entered = digits.length)) }
    }

    /** Back on the dialog: also cancels a check in flight (its result must not open Setup or reach a new dialog). */
    fun onPinDismiss() {
        countdown?.cancel()
        submitJob?.cancel()
        submitJob = null
        digits.clear()
        _state.update { it.copy(pin = null) }
    }

    private fun submit(pin: String) {
        submitJob = viewModelScope.launch {
            val result = profiles.verifyAdminPin(pin)
            digits.clear()
            when (result) {
                PinCheck.Ok -> {
                    // Only while the dialog is still open (Back cancels the check, this is a second guard).
                    if (_state.value.pin == null) return@launch
                    _state.update { it.copy(pin = null) }
                    _changeServer.trySend(Unit)
                }
                PinCheck.Wrong -> showPinMessage(UiText.Res(R.string.pin_wrong))
                is PinCheck.Locked -> lock(result.retryAfterSeconds)
                PinCheck.Unreachable, PinCheck.Failed -> showPinMessage(UiText.Res(R.string.pin_check_failed))
            }
        }
    }

    private fun showPinMessage(message: UiText) {
        _state.update { s -> s.copy(pin = s.pin?.copy(entered = 0, message = message)) }
    }

    /** 429: keypad disabled, "Demasiados intentos… 0:ss" counting down to zero (virtual time in tests). */
    private fun lock(seconds: Long) {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (left in seconds downTo 1) {
                _state.update { s -> s.copy(pin = s.pin?.copy(entered = 0, locked = true, message = UiText.Res(R.string.pin_locked, listOf(Format.clock(left.toDouble()))))) }
                delay(1_000)
            }
            _state.update { s -> s.copy(pin = s.pin?.let { PinUi() }) }
        }
    }

    init {
        viewModelScope.launch {
            combine(account.profile, account.info) { p, i -> p to i }.collect { (profile, info) ->
                _state.update {
                    it.copy(
                        profileName = profile?.name,
                        canChangeServer = profile?.is_admin == true,
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

private const val PIN_LENGTH = 4
