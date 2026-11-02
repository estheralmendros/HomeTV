package com.hometv.tv.core.api

import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException

/**
 * Every API failure, parsed from the API error envelope. `serverMessage` and `detail` are
 * English diagnostics: logged, never shown; the UI picks its text
 * from `code`.
 */
sealed class ApiException(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    /** No answer: UnknownHost, Connect, SocketTimeout, NoRouteToHost, TLS... */
    class Unreachable(override val cause: IOException) : ApiException(cause.toString(), cause) {
        val isTls: Boolean get() = cause is SSLException
    }

    /** 401 on a user endpoint (missing, invalid or expired profile token). */
    class Unauthorized(val code: String?, val serverMessage: String?) : ApiException("401 $code")

    /** Any other non-2xx answer. `retryAfterSeconds` = `Retry-After` header ?: `details.retry_after_seconds`. */
    class Http(
        val status: Int,
        val code: String?,
        val serverMessage: String?,
        val details: JsonObject,
        val retryAfterSeconds: Long?,
    ) : ApiException("$status $code") {
        /** `error.details.detail` (e.g. "keyframe_index_failed: …"), diagnostics only. */
        val detail: String? get() = details.stringField("detail")
    }

    /** A 2xx body that does not decode. */
    class Decode(cause: Throwable) : ApiException(cause.toString(), cause)

    /** Session renewal answered 404 `profile_not_found`: the only path back to the Profile Picker. */
    class ProfileGone : ApiException("profile_not_found")
}

object ApiErrors {
    /** Maps any failure of a Retrofit/OkHttp call. */
    fun from(t: Throwable): ApiException = when (t) {
        is ApiException -> t
        is HttpException -> {
            val response = t.response()
            fromResponse(t.code(), response?.errorBody()?.use { runCatching { it.string() }.getOrNull() }, response?.headers()?.get("Retry-After"))
        }
        is IOException -> Unreachable(t)
        else -> Decode(t)
    }

    /** Parses the error envelope of a non-2xx answer (a body that is not an envelope keeps code null). */
    fun fromResponse(status: Int, body: String?, retryAfterHeader: String?): ApiException {
        val envelope = body?.let { runCatching { ApiJson.decodeFromString(ErrorEnvelope.serializer(), it) }.getOrNull() }?.error
        if (status == 401) return ApiException.Unauthorized(envelope?.code, envelope?.message)
        val details = envelope?.details ?: JsonObject(emptyMap())
        val retryAfter = retryAfterHeader?.trim()?.toLongOrNull() ?: details.stringField("retry_after_seconds")?.toDoubleOrNull()?.toLong()
        return ApiException.Http(status, envelope?.code, envelope?.message, details, retryAfter)
    }

    private fun Unreachable(cause: IOException) = ApiException.Unreachable(cause)

    private fun Decode(cause: Throwable) = ApiException.Decode(cause)
}

/** Runs an API call and maps every failure to [ApiException] (cancellation passes through). */
suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    throw ApiErrors.from(e)
}
