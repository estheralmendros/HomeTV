package com.hometv.tv.core.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OkHttpAwaitTest {
    /** A call whose answer the test delivers by hand through the captured [callback]. */
    private class ManualCall : Call {
        private val request = Request.Builder().url("https://hometv.ts.net/api/health").build()
        var callback: Callback? = null
        var canceled = false

        override fun request() = request
        override fun execute(): Response = error("not used")
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }
        override fun cancel() {
            canceled = true
        }
        override fun isExecuted() = callback != null
        override fun isCanceled() = canceled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = ManualCall()
    }

    private var closed = false

    private fun response(call: Call): Response {
        val source = object : ForwardingSource(Buffer().writeUtf8("{}")) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        return Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(source.asResponseBody(null, 2)).build()
    }

    @Test
    fun `the answer and its already-read body are returned, the response closed`() = runTest {
        val call = ManualCall()
        var got: FetchedResponse? = null
        launch { got = call.fetch() }
        runCurrent()
        call.callback!!.onResponse(call, response(call))
        advanceUntilIdle()
        assertEquals(200, got!!.code)
        assertEquals("{}", got!!.body.decodeToString())
        assertTrue(closed, "the response is closed once its body is read")
    }

    /** A 200 answer whose body is [body] with an unknown length (chunked: no Content-Length to reject early). */
    private fun streamed(call: Call, body: okio.Source): Response = Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(object : ForwardingSource(body) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer().asResponseBody(null, -1)).build()

    private fun fetchWith(call: ManualCall, maxBytes: Long, response: Response): Result<FetchedResponse> = runBlocking {
        val result = async(Dispatchers.Unconfined) { runCatching { call.fetch(maxBytes) } }
        call.callback!!.onResponse(call, response)
        result.await()
    }

    @Test
    fun `a body up to the cap is read`() {
        val call = ManualCall()
        val got = fetchWith(call, maxBytes = 10, streamed(call, Buffer().writeUtf8("0123456789")))
        assertEquals("0123456789", got.getOrThrow().body.decodeToString())
    }

    @Test
    fun `a body over the cap is an IOException and the response is closed`() {
        val call = ManualCall()
        val got = fetchWith(call, maxBytes = 10, streamed(call, Buffer().writeUtf8("0123456789A")))
        val error = got.exceptionOrNull()
        assertTrue(error is ResponseTooLargeException, "$error")
        assertTrue(error is java.io.IOException)
        assertTrue(closed, "the oversized response is closed")
    }

    @Test
    fun `a declared Content-Length over the cap fails before reading`() {
        val call = ManualCall()
        var reads = 0
        val source = object : ForwardingSource(Buffer().writeUtf8("x".repeat(20))) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { reads++ }
        }
        val response = Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(source.buffer().asResponseBody(null, 20)).build()
        assertTrue(fetchWith(call, maxBytes = 10, response).exceptionOrNull() is ResponseTooLargeException)
        assertEquals(0, reads)
    }

    @Test
    fun `the default cap is 1 MiB`() {
        assertEquals(1L shl 20, DEFAULT_MAX_BODY_BYTES)
    }

    @Test
    fun `any Throwable while reading the body resumes the caller instead of escaping on OkHttp's thread`() {
        val call = ManualCall()
        val failing = object : okio.Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw OutOfMemoryError("test: huge body")
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        val got = fetchWith(call, maxBytes = 10, streamed(call, failing))
        assertTrue(got.exceptionOrNull() is OutOfMemoryError, "${got.exceptionOrNull()}")
        assertTrue(closed)
    }

    @Test
    fun `cancelling the caller cancels the call and closes a late response`() = runTest(StandardTestDispatcher()) {
        val call = ManualCall()
        val job = launch { call.fetch() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertTrue(call.canceled)
        call.callback!!.onResponse(call, response(call)) // arrives after the cancellation
        assertTrue(closed, "the dropped response must be closed")
    }
}
