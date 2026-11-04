package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.SessionResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Who triggered a renewal: decides how an unreachable server surfaces. */
enum class Origin {
    /** A screen load, a user action, any `POST .../playback`, a player stream failure: Connection Error screen. */
    Foreground,

    /** A progress PUT, a keep-alive, the next-episode prefetch, a subtitle fetch: logged and dropped. */
    Background,
}

sealed interface RenewOutcome {
    data object Renewed : RenewOutcome

    /** 404 `profile_not_found` while the renewed profile is still stored: its token is cleared. */
    data object ProfileGone : RenewOutcome

    /**
     * The session the failed request belonged to is no longer stored (signed out, or another
     * profile signed in): nothing was stored, and the request must not be retried.
     */
    data object NoSession : RenewOutcome

    /** The session request failed: an IOException, or any other non-404 error answer. */
    data class Unreachable(val cause: ApiException) : RenewOutcome
}

/**
 * Silent re-session. Profile tokens are identity only (no PIN), so an expired or
 * rejected token is replaced by `POST /api/profiles/{profileId}/session` for the remembered
 * profile; the viewer only reaches the Profile Picker when that profile is gone. Renewal is
 * single-flight: concurrent 401s wait on the mutex and reuse the token the first renewal stored.
 * Nothing renews a signed-out session, and a request belongs to the profile that sent it: a
 * renewal stores its result only into that session (`saveRenewedSession` / `clearToken(profileId)`),
 * and a request is never retried under another profile.
 * The guard never navigates: [renew] returns an outcome and [withRenewal] maps it by origin.
 */
class SessionGuard(
    private val store: SessionStore,
    private val createSession: suspend (profileId: Long) -> SessionResponse,
    private val events: AppEventSink,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    /** The profile the current session belongs to (null when none is remembered). */
    fun currentProfileId(): Long? = store.session.value.profileId

    /** Renews the stored session: [renew] with its token and profile, read from one snapshot. */
    suspend fun renew(): RenewOutcome {
        val session = store.session.value
        return renew(session.token, session.profileId)
    }

    /**
     * Renews the session that [failedToken] (sent by [failedProfileId]) belonged to. When another
     * caller already replaced that token for the same profile while this one waited, returns
     * [RenewOutcome.Renewed] without a request. [RenewOutcome.NoSession] when no profile is stored,
     * another profile is, or the store refused the result (signed out or switched meanwhile).
     */
    suspend fun renew(failedToken: String?, failedProfileId: Long?): RenewOutcome = mutex.withLock {
        val current = store.session.value
        val profileId = current.profileId ?: return@withLock RenewOutcome.NoSession
        if (profileId != failedProfileId) return@withLock RenewOutcome.NoSession
        if (current.token != null && current.token != failedToken) return@withLock RenewOutcome.Renewed
        try {
            val response = createSession(profileId)
            val saved = store.saveRenewedSession(profileId, response.token, Instant.parse(response.expires_at))
            if (saved) RenewOutcome.Renewed else RenewOutcome.NoSession
        } catch (e: ApiException.Http) {
            if (e.status == 404 && e.code == "profile_not_found") {
                if (store.clearToken(profileId)) RenewOutcome.ProfileGone else RenewOutcome.NoSession
            } else {
                RenewOutcome.Unreachable(e)
            }
        } catch (e: ApiException) {
            RenewOutcome.Unreachable(e)
        }
    }

    /**
     * Proactive refresh (activity `ON_START`, the Player's `onStart`, before each `POST .../playback`,
     * and at startup for an expired token): renews when `expiresAt` is within 24 h. Returns null when
     * nothing was needed, when the session was signed out or switched meanwhile ([RenewOutcome.NoSession]),
     * or when the renewal failed while the current token is still valid (ignored).
     * A gone profile emits [AppEvent.AuthExpired].
     */
    suspend fun ensureFresh(): RenewOutcome? {
        val session = store.session.value
        val expiresAt = session.expiresAt ?: return null
        if (session.token == null) return null
        val now = clock.instant()
        if (Duration.between(now, expiresAt) > REFRESH_WINDOW) return null
        return when (val outcome = renew(session.token, session.profileId)) {
            RenewOutcome.Renewed -> outcome
            RenewOutcome.ProfileGone -> outcome.also { events.emit(AppEvent.AuthExpired) }
            RenewOutcome.NoSession -> null
            is RenewOutcome.Unreachable -> if (expiresAt.isAfter(now)) null else outcome
        }
    }

    /**
     * Runs [block] (an [com.hometv.tv.core.api.apiCall]); on a 401 renews once and retries once,
     * only while the profile that sent the request is still stored. A 401 on the retry propagates
     * (no second renewal). A 401 sent without a token, [RenewOutcome.NoSession], or a profile change
     * before the retry rethrow the 401 with no event. ProfileGone emits AuthExpired and throws
     * [ApiException.ProfileGone]; Unreachable emits ServerUnreachable only for [Origin.Foreground]
     * and throws the cause either way.
     */
    suspend fun <T> withRenewal(origin: Origin, block: suspend () -> T): T {
        val used = store.session.value
        try {
            return block()
        } catch (e: ApiException.Unauthorized) {
            if (used.token == null) throw e
            when (val outcome = renew(used.token, used.profileId)) {
                RenewOutcome.Renewed -> if (store.session.value.profileId == used.profileId) return block() else throw e
                RenewOutcome.NoSession -> throw e
                RenewOutcome.ProfileGone -> {
                    events.emit(AppEvent.AuthExpired)
                    throw ApiException.ProfileGone()
                }
                is RenewOutcome.Unreachable -> {
                    val cause = outcome.cause
                    if (origin == Origin.Foreground && cause is ApiException.Unreachable) events.emit(AppEvent.ServerUnreachable(cause))
                    throw cause
                }
            }
        }
    }

    companion object {
        val REFRESH_WINDOW: Duration = Duration.ofHours(24)
    }
}
