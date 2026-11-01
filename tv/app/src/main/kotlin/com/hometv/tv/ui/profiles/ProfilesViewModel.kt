package com.hometv.tv.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.api.ServerInfo
import com.hometv.tv.core.data.ProfileRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.components.AvatarUi
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** One avatar: circle in the avatar color (`#RRGGBB` from `/api/info`, null → theme color) with the initial. */
data class ProfileUi(val id: Long, val name: String, val initial: String, val color: String?)

sealed interface ProfilesUiState {
    data object Loading : ProfilesUiState

    data class Content(
        val profiles: List<ProfileUi>,
        val initialFocusId: Long?,
        val message: UiText? = null,
        val signingIn: Boolean = false,
    ) : ProfilesUiState

    data class Error(val error: ScreenError) : ProfilesUiState
}

sealed interface ProfilesEvent {
    data object SignedIn : ProfilesEvent
}

/** Profile Picker (prd3 §2): no PIN on the TV, any profile signs in with one OK press. */
class ProfilesViewModel(
    private val profiles: ProfileRepository,
    private val store: SessionStore,
    private val onSignedIn: (Profile, ServerInfo?) -> Unit,
    expired: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow<ProfilesUiState>(ProfilesUiState.Loading)
    val state: StateFlow<ProfilesUiState> = _state.asStateFlow()
    private val _events = Channel<ProfilesEvent>(Channel.BUFFERED)
    val events: Flow<ProfilesEvent> = _events.receiveAsFlow()
    private var info: ServerInfo? = null
    private var job: Job? = null

    init {
        load(if (expired) UiText.Res(R.string.profiles_session_expired) else null)
    }

    fun retry() = load(null)

    private fun load(message: UiText?) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val (list, serverInfo) = coroutineScope {
                    val list = async { profiles.list() }
                    // The avatar colors are optional: a failing /api/info only drops them.
                    val serverInfo = async {
                        try {
                            profiles.info()
                        } catch (e: ApiException) {
                            null
                        }
                    }
                    list.await() to serverInfo.await()
                }
                info = serverInfo
                val colors = serverInfo?.avatars.orEmpty().associate { it.id to it.color }
                val items = list.map { ProfileUi(it.id, it.name, AvatarUi.initial(it.name), colors[it.avatar_id]) }
                val remembered = store.session.value.profileId
                val focus = items.firstOrNull { it.id == remembered }?.id ?: items.firstOrNull()?.id
                _state.value = ProfilesUiState.Content(items, focus, message)
            } catch (e: ApiException) {
                _state.value = ProfilesUiState.Error(ScreenError.from(e, store.session.value.serverUrl))
            }
        }
    }

    /** `POST /api/profiles/{id}/session` (no body) → token stored → Home. */
    fun select(profileId: Long) {
        val content = _state.value as? ProfilesUiState.Content ?: return
        if (content.signingIn) return
        _state.value = content.copy(signingIn = true)
        viewModelScope.launch {
            try {
                val response = profiles.createSession(profileId)
                onSignedIn(response.profile, info)
                _events.send(ProfilesEvent.SignedIn)
            } catch (e: ApiException) {
                if (e is ApiException.Http && e.status == 404 && e.code == "profile_not_found") {
                    load(UiText.Res(R.string.profiles_gone))
                } else {
                    _state.value = ProfilesUiState.Error(ScreenError.from(e, store.session.value.serverUrl))
                }
            }
        }
    }
}
