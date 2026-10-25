package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.HomeTvApi
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.api.ServerInfo
import com.hometv.tv.core.api.SessionResponse
import com.hometv.tv.core.api.UnlockRequest
import com.hometv.tv.core.api.apiCall
import java.time.Instant

/** Profiles and sessions (prd3 §2). The only PIN on the TV guards the server change (§11 step 3). */
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

    /**
     * Checks an admin PIN with `POST /api/admin/unlock` and the current profile token (prd3 §11 step 3).
     * A 401 `invalid_pin` is a wrong PIN and never renews the session. Any other 401 is an expired or
     * rejected profile token: the session is renewed once (§2.5) and the check retried. The renewal runs
     * as [Origin.Background], so a failure shows in the dialog ("No se ha podido comprobar el PIN"), not
     * as the Connection Error screen; a gone profile still emits AuthExpired (Profile Picker).
     * The returned admin token is not kept (the TV asks for the PIN every time).
     */
    suspend fun verifyAdminPin(pin: String): PinCheck = try {
        guard.withRenewal(Origin.Background) {
            try {
                apiCall { api().unlock(UnlockRequest(pin)) }
                PinCheck.Ok
            } catch (e: ApiException.Unauthorized) {
                if (e.code == "invalid_pin") PinCheck.Wrong else throw e
            }
        }
    } catch (e: ApiException.Http) {
        if (e.status == 429) PinCheck.Locked(e.retryAfterSeconds ?: DEFAULT_LOCK_SECONDS) else PinCheck.Failed
    } catch (_: ApiException.Unreachable) {
        PinCheck.Unreachable
    } catch (_: ApiException) {
        PinCheck.Failed
    }

    private companion object {
        /** prd.md §8: the unlock limiter's window. */
        const val DEFAULT_LOCK_SECONDS = 60L
    }
}

/** Outcome of checking an admin PIN (prd3 §11 step 3). */
sealed interface PinCheck {
    data object Ok : PinCheck
    data object Wrong : PinCheck
    data class Locked(val retryAfterSeconds: Long) : PinCheck
    data object Unreachable : PinCheck
    data object Failed : PinCheck
}
