package com.hometv.tv.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.data.PinCheck
import com.hometv.tv.core.data.ProfileRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.util.Format
import com.hometv.tv.data.AccountState
import com.hometv.tv.data.EnginePrefs
import com.hometv.tv.data.InMemoryEnginePrefs
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
    /** "Usar siempre VLC (experimental)" is offered (admin and libVLC available). */
    val showForceVlc: Boolean = false,
    val forceVlc: Boolean = false,
    /** The licence dialog text (raw legal text, not catalog UI text), null while closed. */
    val licenses: String? = null,
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
    private val enginePrefs: EnginePrefs = InMemoryEnginePrefs(),
    private val vlcAvailable: Boolean = false,
    private val notices: suspend () -> String = { "" },
    private val log: (String, Throwable?) -> Unit = { msg, e -> runCatching { Log.w("HomeTvSettings", msg, e) } },
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

    /** The force-VLC write in flight: a second press before it lands is ignored (it would write the same value). */
    private var toggleJob: Job? = null

    /**
     * [Usar siempre VLC (experimental)]: flips the device preference (admins with libVLC only, no PIN: it only
     * changes how this TV decodes). Applies to the next playback request.
     */
    fun onToggleForceVlc() {
        val s = _state.value
        if (!s.showForceVlc || toggleJob?.isActive == true) return
        toggleJob = viewModelScope.launch { enginePrefs.setForceVlc(!s.forceVlc) }
    }

    /** The notice read in flight: a second OK before it lands is ignored. */
    private var licensesJob: Job? = null

    /**
     * [Licencias de código abierto]: loads the notice into the dialog; a load failure is logged and opens nothing.
     * Ignored while a load is in flight or the dialog is already shown.
     */
    fun onOpenLicenses() {
        if (licensesJob?.isActive == true || _state.value.licenses != null) return
        licensesJob = viewModelScope.launch {
            val text = try {
                notices()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Licence notice unreadable", e)
                return@launch
            }
            _state.update { it.copy(licenses = text) }
        }
    }

    fun onCloseLicenses() {
        _state.update { it.copy(licenses = null) }
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
                        showForceVlc = profile?.is_admin == true && vlcAvailable,
                        serverVersion = info?.let { i -> UiText.Res(R.string.settings_server_version, listOf(i.name, i.version.orEmpty())) },
                        tmdbAttribution = info?.tmdb_attribution,
                    )
                }
            }
        }
        viewModelScope.launch { enginePrefs.forceVlc.collect { on -> _state.update { it.copy(forceVlc = on) } } }
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
