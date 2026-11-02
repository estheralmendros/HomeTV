package com.hometv.tv.core.util

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Why a user-entered server address was rejected (message keys; `app` picks the Spanish text). */
enum class ServerUrlError {
    /** Nothing but a scheme (the field's pre-filled "https://") or blank input. */
    EMPTY,

    /** Not an http(s) URL, or an unparsable host. */
    INVALID,

    /** `http://` outside the debug allow-list (release: HTTPS only). */
    CLEARTEXT_NOT_ALLOWED,
}

sealed interface ServerUrlResult {
    data class Valid(val url: String) : ServerUrlResult

    data class Invalid(val error: ServerUrlError) : ServerUrlResult
}

/** Normalizes and validates the server address typed on the Setup screen. */
object ServerUrl {
    /** Hosts debug builds may reach over cleartext (emulator loopback and localhost). */
    private val DEBUG_CLEARTEXT_HOSTS = setOf("10.0.2.2", "localhost")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

    /**
     * Trim; prepend `https://` when there is no scheme; lower-case the scheme and host; drop the
     * path, query, fragment and default port. `http://` is accepted only when [allowDebugCleartext]
     * and the host is `10.0.2.2` or `localhost`.
     */
    fun normalize(input: String, allowDebugCleartext: Boolean = false): ServerUrlResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ServerUrlResult.Invalid(ServerUrlError.EMPTY)
        val match = SCHEME.find(trimmed)
        val scheme = match?.groupValues?.get(1)?.lowercase() ?: "https"
        val rest = if (match == null) trimmed else trimmed.substring(match.range.last + 1)
        if (scheme != "http" && scheme != "https") return ServerUrlResult.Invalid(ServerUrlError.INVALID)
        if (rest.isEmpty() || rest.first() in "/?#") return ServerUrlResult.Invalid(ServerUrlError.EMPTY)
        val url = "$scheme://$rest".toHttpUrlOrNull() ?: return ServerUrlResult.Invalid(ServerUrlError.INVALID)
        if (url.host.isEmpty()) return ServerUrlResult.Invalid(ServerUrlError.INVALID)
        if (scheme == "http" && !(allowDebugCleartext && url.host in DEBUG_CLEARTEXT_HOSTS)) {
            return ServerUrlResult.Invalid(ServerUrlError.CLEARTEXT_NOT_ALLOWED)
        }
        val port = if (url.port == HttpUrl.defaultPort(scheme)) "" else ":${url.port}"
        return ServerUrlResult.Valid("$scheme://${url.host}$port")
    }

    /** Resolves an API-relative URL (`/api/...`) against the server URL; absolute URLs pass through. */
    fun resolve(baseUrl: String, path: String): String {
        val base = baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid base URL: $baseUrl")
        return requireNotNull(base.resolve(path)) { "Cannot resolve $path against $baseUrl" }.toString()
    }

    /** The host of [url], or null when it is not an http(s) URL. */
    fun host(url: String): String? = url.toHttpUrlOrNull()?.host
}
