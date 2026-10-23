package com.hometv.tv.core.api

import kotlinx.coroutines.launch
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
    fun `the answer is returned to the caller`() = runTest {
        val call = ManualCall()
        var got: Response? = null
        launch { got = call.await() }
        runCurrent()
        call.callback!!.onResponse(call, response(call))
        advanceUntilIdle()
        assertEquals(200, got!!.code)
        assertEquals(false, closed)
    }

    @Test
    fun `a response racing with cancellation is closed`() = runTest(StandardTestDispatcher()) {
        val call = ManualCall()
        val job = launch { call.await().close() }
        runCurrent()
        call.callback!!.onResponse(call, response(call)) // resumed, not yet dispatched
        job.cancel()
        advanceUntilIdle()
        assertTrue(call.canceled)
        assertTrue(closed, "the dropped response must be closed")
    }
}
