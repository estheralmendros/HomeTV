package com.hometv.tv.core.api

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Response

/** An answer whose body has already been read, so the caller never touches the socket. */
class FetchedResponse(val code: Int, val body: ByteArray, private val headers: Headers) {
    val isSuccessful: Boolean get() = code in 200..299

    fun header(name: String): String? = headers[name]
}

/** The body is longer than the cap of [Call.fetch]: an [IOException], so callers treat it like a failed read. */
class ResponseTooLargeException(val limit: Long) : IOException("response body larger than $limit bytes")

/** The default body cap of [Call.fetch] (server validation answers are a few hundred bytes). */
const val DEFAULT_MAX_BODY_BYTES: Long = 1L shl 20

/**
 * Suspends until the call answers and its body is read. The body is read on OkHttp's thread, inside the
 * callback: the caller may resume on the main thread, where reading the socket throws
 * `NetworkOnMainThreadException` (prd3 Technical Requirements: no network on the main thread).
 * Cancelling the coroutine cancels the call; a body read failure is an [IOException] like a failed call.
 * At most [maxBytes] are read: a longer body (declared or streamed) is a [ResponseTooLargeException], so
 * a huge answer from a host that is not HomeTV never exhausts the heap. Any other [Throwable] while
 * reading resumes the caller with it instead of escaping on OkHttp's thread.
 */
suspend fun Call.fetch(maxBytes: Long = DEFAULT_MAX_BODY_BYTES): FetchedResponse = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val fetched = try {
                    response.use { FetchedResponse(it.code, it.readBody(maxBytes), it.headers) }
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                    return
                }
                if (cont.isActive) cont.resume(fetched)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        },
    )
}

private fun Response.readBody(maxBytes: Long): ByteArray {
    val body = body ?: return ByteArray(0)
    if (body.contentLength() > maxBytes) throw ResponseTooLargeException(maxBytes)
    val source = body.source()
    // request(n) buffers until n bytes are there or the body ends: never more than maxBytes + 1 in memory.
    if (source.request(maxBytes + 1)) throw ResponseTooLargeException(maxBytes)
    return source.buffer.readByteArray()
}
