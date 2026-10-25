package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiClientFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * prd3 Technical Requirements (no network on the main thread): the hand-rolled OkHttp calls read the
 * response body on OkHttp's thread, never on the caller's. On a TV the caller resumes on the main thread,
 * where reading the socket throws `NetworkOnMainThreadException` (the subtitle and server-check crashes).
 */
class BodyReadThreadTest {
    private val t = TestServer()

    @AfterEach
    fun tearDown() = t.shutdown()

    /** Records the thread of every read of a response body. */
    private class ReadThreads : Interceptor {
        val names = CopyOnWriteArrayList<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val body = response.body ?: return response
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    names += Thread.currentThread().name
                    return super.read(sink, byteCount)
                }
            }.buffer()
            return response.newBuilder().body(source.asResponseBody(body.contentType(), body.contentLength())).build()
        }
    }

    private fun OkHttpClient.recording(reads: ReadThreads) = newBuilder().addInterceptor(reads).build()

    @Test
    fun `the subtitle body is read off the caller's thread`() = runTest {
        val reads = ReadThreads()
        val source = OkHttpSubtitleSource(t.clients.streamClient.recording(reads))
        t.route("GET", "/api/media-files/91/subtitles/ext-1.vtt") { MockResponse().setBody("WEBVTT\n\n00:01.000 --> 00:02.000\nHola\n") }
        val caller = Thread.currentThread().name
        val result = source.fetch(t.url + "/api/media-files/91/subtitles/ext-1.vtt")
        assertTrue(result is SubtitleFetch.Ready, "$result")
        assertTrue(reads.names.isNotEmpty(), "the body was read")
        assertFalse(caller in reads.names, "body read on the caller's thread ($caller): ${reads.names}")
    }

    @Test
    fun `the server check reads its bodies off the caller's thread`() = runTest {
        val reads = ReadThreads()
        val repo = ServerRepository(ApiClientFactory.validationClient("UA").recording(reads))
        t.route("GET", "/api/health") { t.json("""{"status": "ok"}""") }
        t.route("GET", "/api/info") { t.json("""{"name": "HomeTV", "version": "0.1.0", "needs_bootstrap": false, "tmdb_configured": false, "avatars": []}""") }
        val caller = Thread.currentThread().name
        assertTrue(repo.validate(t.url) is ServerValidation.Ok)
        assertTrue(reads.names.isNotEmpty(), "the bodies were read")
        assertFalse(caller in reads.names, "body read on the caller's thread ($caller): ${reads.names}")
    }
}
