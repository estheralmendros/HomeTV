package com.hometv.tv.core.api

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Suspends until the call answers; cancelling the coroutine cancels the call and closes a late response. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // Cancelled before or while resuming: nobody receives the response, so close it.
                if (!cont.isActive) response.close() else cont.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        },
    )
}
