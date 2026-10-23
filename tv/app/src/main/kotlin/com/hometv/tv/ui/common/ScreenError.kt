package com.hometv.tv.ui.common

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.util.ServerUrl

/** A screen-level failure (prd3 §10 API table). The screen renders it with the shared error screens. */
sealed interface ScreenError {
    /** Full-screen Connection Error ([Reintentar] / [Abrir Tailscale] / [Cambiar servidor]); [tls] swaps in the §1.6 TLS text. */
    data class Connection(val host: String, val tls: Boolean) : ScreenError

    /** 404 `not_found`: "Este título ya no está disponible." [Volver] (+ `AppEvent.ItemGone`). */
    data object ItemGone : ScreenError

    /** A 401 on the retried call: "No se ha podido iniciar sesión en el servidor" [Reintentar]. */
    data object SignIn : ScreenError

    /** The renewal answered 404 `profile_not_found`: `HomeTvRoot` already navigates to the Picker. */
    data object ProfileGone : ScreenError

    /** "Se ha producido un error inesperado (%1$s)." for an `error.code` the app does not know. */
    data class Unexpected(val code: String) : ScreenError

    /** "No se ha podido cargar el contenido." [Reintentar]. */
    data object LoadFailed : ScreenError

    companion object {
        /** `error.code`s defined by prd.md; any other code is shown as [Unexpected]. */
        private val KNOWN_CODES = setOf(
            "unauthorized", "forbidden", "not_found", "validation_error", "profile_not_found", "admin_locked", "pin_required",
            "preparing", "too_many_sessions", "hls_disk_full", "transcoder_failed", "file_missing", "invalid_audio_stream",
            "session_not_found", "segment_timeout", "segment_not_found", "media_file_not_linked", "subtitle_pending",
            "subtitle_extraction_failed", "subtitle_timeout", "subtitle_not_found", "subtitle_not_text", "artwork_not_found",
            "tmdb_unavailable", "tmdb_not_found", "tmdb_auth_failed", "root_unavailable", "internal_error", "conflict",
        )

        fun from(e: ApiException, serverUrl: String?): ScreenError = when (e) {
            is ApiException.Unreachable -> Connection(serverUrl?.let(ServerUrl::host) ?: serverUrl.orEmpty(), e.isTls)
            is ApiException.Unauthorized -> SignIn
            is ApiException.ProfileGone -> ProfileGone
            is ApiException.Decode -> LoadFailed
            is ApiException.Http -> {
                val code = e.code
                when {
                    e.status == 404 && code == "not_found" -> ItemGone
                    code != null && code !in KNOWN_CODES -> Unexpected(code)
                    else -> LoadFailed
                }
            }
        }
    }
}
