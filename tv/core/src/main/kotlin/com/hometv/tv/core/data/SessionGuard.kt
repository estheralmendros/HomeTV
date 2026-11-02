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

    /** 404 `profile_not_found` (or no remembered profile): the token is cleared. */
    data object ProfileGone : RenewOutcome

    /** The session request failed: an IOException, or any other non-404 error answer. */
    data class Unreachable(val cause: ApiException) : RenewOutcome
}

/**
 * Silent re-session. Profile tokens are identity only (no PIN), so an expired or
 * rejected token is replaced by `POST /api/profiles/{profileId}/session` for the remembered
 * profile; the viewer only reaches the Profile Picker when that profile is gone. Renewal is
 * single-flight: concurrent 401s wait on the mutex and reuse the token the first renewal stored.
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

    /**
     * Renews the session that [failedToken] belonged to. When another caller already replaced that
     * token while this one waited, returns [RenewOutcome.Renewed] without a request.
     */
    suspend fun renew(failedToken: String? = store.session.value.token): RenewOutcome = mutex.withLock {
        val current = store.session.value
        if (current.token != null && current.token != failedToken) return@withLock RenewOutcome.Renewed
        val profileId = current.profileId ?: run {
            store.clearToken()
            return@withLock RenewOutcome.ProfileGone
        }
        try {
            val response = createSession(profileId)
            store.saveSession(response.profile.id, response.token, Instant.parse(response.expires_at))
            RenewOutcome.Renewed
        } catch (e: ApiException.Http) {
            if (e.status == 404 && e.code == "profile_not_found") {
                store.clearToken()
                RenewOutcome.ProfileGone
            } else {
                RenewOutcome.Unreachable(e)
            }
        } catch (e: ApiException) {
            RenewOutcome.Unreachable(e)
        }
    }

    /**
     * Proactive refresh (activity `ON_START`, before each `POST .../playback`, and at startup for
     * an expired token): renews when `expiresAt` is within 24 h. Returns null when nothing was
     * needed, or when the renewal failed while the current token is still valid (ignored).
     * A gone profile emits [AppEvent.AuthExpired].
     */
    suspend fun ensureFresh(): RenewOutcome? {
        val session = store.session.value
        val expiresAt = session.expiresAt ?: return null
        if (session.token == null) return null
        val now = clock.instant()
        if (Duration.between(now, expiresAt) > REFRESH_WINDOW) return null
        return when (val outcome = renew(session.token)) {
            RenewOutcome.Renewed -> outcome
            RenewOutcome.ProfileGone -> outcome.also { events.emit(AppEvent.AuthExpired) }
            is RenewOutcome.Unreachable -> if (expiresAt.isAfter(now)) null else outcome
        }
    }

    /**
     * Runs [block] (an [com.hometv.tv.core.api.apiCall]); on a 401 renews once and retries once.
     * A 401 on the retry propagates (no second renewal). ProfileGone emits AuthExpired and throws
     * [ApiException.ProfileGone]; Unreachable emits ServerUnreachable only for [Origin.Foreground]
     * and throws the cause either way.
     */
    suspend fun <T> withRenewal(origin: Origin, block: suspend () -> T): T {
        val usedToken = store.session.value.token
        try {
            return block()
        } catch (e: ApiException.Unauthorized) {
            when (val outcome = renew(usedToken)) {
                RenewOutcome.Renewed -> return block()
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
