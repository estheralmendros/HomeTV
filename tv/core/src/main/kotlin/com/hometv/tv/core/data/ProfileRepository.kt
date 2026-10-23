package com.hometv.tv.core.data

import com.hometv.tv.core.api.HomeTvApi
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.api.ServerInfo
import com.hometv.tv.core.api.SessionResponse
import com.hometv.tv.core.api.apiCall
import java.time.Instant

/** Profiles and sessions (prd3 §2). No PIN anywhere on the TV. */
class ProfileRepository(
    private val api: () -> HomeTvApi,
    private val store: SessionStore,
    private val guard: SessionGuard,
) {
    /** `GET /api/profiles` (public). */
    suspend fun list(): List<Profile> = apiCall { api().profiles() }.items

    /** `GET /api/info` (public): avatar colors, TMDB attribution, server name/version. */
    suspend fun info(): ServerInfo = apiCall { api().info() }

    /**
     * `POST /api/profiles/{id}/session` with no body, for any profile (admin or not); stores
     * profileId, token and expiry. 404 `profile_not_found` propagates as [com.hometv.tv.core.api.ApiException.Http].
     */
    suspend fun createSession(profileId: Long): SessionResponse {
        val response = apiCall { api().createSession(profileId) }
        store.saveSession(response.profile.id, response.token, Instant.parse(response.expires_at))
        return response
    }

    suspend fun me(origin: Origin = Origin.Foreground): Profile = guard.withRenewal(origin) { apiCall { api().me() } }
}
