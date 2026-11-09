package com.hometv.tv.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.api.ServerInfo
import com.hometv.tv.core.data.Origin
import com.hometv.tv.core.data.ProfileRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.playback.SubtitleLook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The signed-in profile, the server info (top-nav avatar, Settings) and the profile's subtitle
 * style (the Player), in memory. A `Me` applies only while its id is the profile stored in [store]
 * when it lands (a late answer for a previous profile, or after sign-out, changes nothing), and its
 * style only when no local [setSubtitleStyle] happened since the request started.
 */
class AccountState(
    private val profiles: ProfileRepository,
    private val store: SessionStore,
    private val appScope: CoroutineScope,
) {
    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile.asStateFlow()
    private val _info = MutableStateFlow<ServerInfo?>(null)
    val info: StateFlow<ServerInfo?> = _info.asStateFlow()
    private val _style = MutableStateFlow(SubtitleLook.DEFAULT)

    /** The profile's subtitle style; the default until `/api/me` answers. */
    val subtitleStyle: StateFlow<SubtitleLook> = _style.asStateFlow()

    @Volatile
    private var styleLoaded = false

    /** Bumped by every local style change and reset: a `Me` requested before it never overwrites it. */
    @Volatile
    private var styleSeq = 0L

    /** The Picker's callback: a new profile starts on the default style and loads its own at once. */
    fun signedIn(profile: Profile, info: ServerInfo?) {
        if (_profile.value?.id != profile.id) resetStyle()
        _profile.value = profile
        if (info != null) _info.value = info
        appScope.launch { refresh() }
    }

    fun clear() {
        _profile.value = null
        _info.value = null
        resetStyle()
    }

    /** Applies a style for this session (the Player's "Formato de los subtítulos"). */
    fun setSubtitleStyle(look: SubtitleLook) {
        styleSeq++
        _style.value = look
    }

    /** `#RRGGBB` of the current profile's avatar, from `/api/info`. */
    fun avatarColor(): String? {
        val avatar = _profile.value?.avatar_id ?: return null
        return ProfileAvatar.color(avatar, _info.value?.avatars)
    }

    /**
     * [refresh] while the profile or its style is still unknown (a cold-start `/api/me` failed, e.g.
     * before Tailscale was up): called after a foreground load succeeds, so the avatar, "Ajustes" and
     * the subtitle style follow.
     */
    suspend fun ensureLoaded() {
        if (_profile.value == null || !styleLoaded) refresh()
    }

    /** `GET /api/info` + `GET /api/me` as background calls: failures keep the previous values. */
    suspend fun refresh() {
        try {
            _info.value = profiles.info()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException) {
        }
        val seq = styleSeq
        try {
            val me = profiles.me(Origin.Background)
            val stored = store.session.value.profileId
            if (stored == null || stored != me.id) return
            _profile.value = me.toProfile()
            if (seq == styleSeq) {
                _style.value = SubtitleLook.of(me.subtitle_style)
                styleLoaded = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException) {
        }
    }

    private fun resetStyle() {
        styleSeq++
        styleLoaded = false
        _style.value = SubtitleLook.DEFAULT
    }
}
