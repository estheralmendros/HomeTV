package com.hometv.tv.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.api.ServerInfo
import com.hometv.tv.core.data.Origin
import com.hometv.tv.core.data.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The signed-in profile and the server info, for the top-nav avatar and Settings (in memory). */
class AccountState(private val profiles: ProfileRepository) {
    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile.asStateFlow()
    private val _info = MutableStateFlow<ServerInfo?>(null)
    val info: StateFlow<ServerInfo?> = _info.asStateFlow()

    fun signedIn(profile: Profile, info: ServerInfo?) {
        _profile.value = profile
        if (info != null) _info.value = info
    }

    fun clear() {
        _profile.value = null
        _info.value = null
    }

    /** `#RRGGBB` of the current profile's avatar, from `/api/info`. */
    fun avatarColor(): String? {
        val avatar = _profile.value?.avatar_id ?: return null
        return _info.value?.avatars?.firstOrNull { it.id == avatar }?.color
    }

    /**
     * [refresh] while the profile is still unknown (a cold-start `/api/me` failed, e.g. before
     * Tailscale was up): called after a foreground load succeeds, so the avatar and "Ajustes" follow.
     */
    suspend fun ensureLoaded() {
        if (_profile.value == null) refresh()
    }

    /** `GET /api/info` + `GET /api/me` as background calls: failures keep the previous values. */
    suspend fun refresh() {
        try {
            _info.value = profiles.info()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException) {
        }
        try {
            _profile.value = profiles.me(Origin.Background)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException) {
        }
    }
}
