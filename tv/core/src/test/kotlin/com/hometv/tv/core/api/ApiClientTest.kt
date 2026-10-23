package com.hometv.tv.core.api

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ApiClientTest {
    private val server = MockWebServer()
    private var token: String? = "tok-1"
    private lateinit var clients: ApiClients

    @BeforeEach
    fun setUp() {
        server.start()
        val url = server.url("/").newBuilder().host("localhost").build().toString().trimEnd('/')
        clients = ApiClientFactory.create(url, token = { token }, userAgent = "HomeTV-Android/0.1.0 (Android 34; Test)")
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private suspend fun <T> call(block: suspend HomeTvApi.() -> T): T = apiCall { clients.api.block() }

    @Test
    fun `adds the Bearer token and user agent for the server host only`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("ok"))
        call { health() }
        val first = server.takeRequest()
        assertEquals("Bearer tok-1", first.getHeader("Authorization"))
        assertEquals("HomeTV-Android/0.1.0 (Android 34; Test)", first.getHeader("User-Agent"))

        val otherHost = server.url("/x").newBuilder().host("127.0.0.1").build()
        clients.apiClient.newCall(Request.Builder().url(otherHost).build()).execute().close()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `sends no Authorization header without a token and reads the token per request`() = runTest {
        token = null
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        call { health() }
        assertNull(server.takeRequest().getHeader("Authorization"))
        token = "tok-2"
        call { health() }
        assertEquals("Bearer tok-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `the stream client shares the auth interceptor`() {
        server.enqueue(MockResponse().setBody("WEBVTT"))
        clients.streamClient.newCall(Request.Builder().url(server.url("/api/media-files/1/subtitles/emb-3.vtt").newBuilder().host("localhost").build()).build()).execute().close()
        assertEquals("Bearer tok-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `timeouts per profile`() {
        assertEquals(10_000, clients.apiClient.connectTimeoutMillis)
        assertEquals(20_000, clients.apiClient.readTimeoutMillis)
        assertEquals(30_000, clients.apiClient.callTimeoutMillis)
        assertEquals(10_000, clients.streamClient.connectTimeoutMillis)
        assertTrue(clients.streamClient.readTimeoutMillis >= 45_000)
        assertEquals(0, clients.streamClient.callTimeoutMillis)
        assertTrue(clients.apiClient.connectionPool === clients.streamClient.connectionPool)
        val validation = ApiClientFactory.validationClient("UA")
        assertEquals(10_000, validation.connectTimeoutMillis)
        assertEquals(10_000, validation.readTimeoutMillis)
    }

    @Test
    fun `decodes the polymorphic home payload leniently`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.home))
        val home = call { home(20) }
        assertEquals("/api/home?limit=20", server.takeRequest().path)

        val film = home.continue_watching[0] as FilmSummary
        assertEquals("film:12", film.key)
        assertEquals("/api/artwork/w780/b.jpg", film.backdrop?.small)
        assertEquals("Paul Atreides...", film.overview)
        assertEquals(91L, film.progress?.media_file_id)

        val episode = home.continue_watching[1] as EpisodeSummary
        assertEquals("/api/artwork/w780/ob.jpg", episode.show_backdrop?.small)
        assertEquals(listOf(91L, 92L), episode.media_files.map { it.id })
        assertEquals(listOf(null, 2), episode.media_files.map { it.part_number })
        assertEquals("hevc", episode.media_files[0].video?.codec)
        assertEquals("image", episode.media_files[0].subtitle_tracks[1].kind)

        val show = home.recently_added[0] as ShowSummary
        assertEquals(150, show.unwatched_episode_count)
        assertNull(show.backdrop)
        assertInstanceOf(UnknownSummary::class.java, home.recently_added[1])
        assertEquals(LibraryCounts(1234, 87, false), home.library)
    }

    @Test
    fun `maps the error envelope by status and code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody(Fixtures.error("unauthorized")))
        val unauthorized = assertThrows<ApiException.Unauthorized> { call { me() } }
        assertEquals("unauthorized", unauthorized.code)

        server.enqueue(MockResponse().setResponseCode(404).setBody(Fixtures.error("profile_not_found")))
        val gone = assertThrows<ApiException.Http> { call { createSession(5) } }
        assertEquals(404, gone.status)
        assertEquals("profile_not_found", gone.code)

        server.enqueue(MockResponse().setResponseCode(404).setBody(Fixtures.error("not_found")))
        assertEquals("not_found", assertThrows<ApiException.Http> { call { film(1) } }.code)

        server.enqueue(MockResponse().setResponseCode(404).setBody(Fixtures.error("file_missing")))
        assertEquals("file_missing", assertThrows<ApiException.Http> { call { film(1) } }.code)
    }

    @Test
    fun `503 codes are distinguished and retryAfter comes from the header, else details`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Retry-After", "5")
                .setBody(Fixtures.error("preparing", """{"retry_after_seconds": 7, "estimated_seconds": 412}""")),
        )
        val preparing = assertThrows<ApiException.Http> { call { film(1) } }
        assertEquals(503, preparing.status)
        assertEquals("preparing", preparing.code)
        assertEquals(5L, preparing.retryAfterSeconds)
        assertEquals("412", preparing.details["estimated_seconds"].toString())

        server.enqueue(MockResponse().setResponseCode(503).setBody(Fixtures.error("preparing", """{"retry_after_seconds": 7}""")))
        assertEquals(7L, assertThrows<ApiException.Http> { call { film(1) } }.retryAfterSeconds)

        server.enqueue(MockResponse().setResponseCode(503).setBody(Fixtures.error("too_many_sessions")))
        assertEquals("too_many_sessions", assertThrows<ApiException.Http> { call { film(1) } }.code)
        server.enqueue(MockResponse().setResponseCode(503).setBody(Fixtures.error("hls_disk_full")))
        assertEquals("hls_disk_full", assertThrows<ApiException.Http> { call { film(1) } }.code)

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "42").setBody(Fixtures.error("too_many_attempts")))
        val limited = assertThrows<ApiException.Http> { call { film(1) } }
        assertEquals(429, limited.status)
        assertEquals(42L, limited.retryAfterSeconds)
    }

    @Test
    fun `transcoder_failed keeps details detail for diagnostics`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setBody(Fixtures.error("transcoder_failed", """{"detail": "keyframe_index_failed: ffprobe exited 1"}""")),
        )
        val failed = assertThrows<ApiException.Http> { call { film(1) } }
        assertEquals("transcoder_failed", failed.code)
        assertEquals("keyframe_index_failed: ffprobe exited 1", failed.detail)
        assertNull(failed.retryAfterSeconds)
    }

    @Test
    fun `non-envelope error bodies still map by status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        val http = assertThrows<ApiException.Http> { call { film(1) } }
        assertEquals(502, http.status)
        assertNull(http.code)
    }

    @Test
    fun `an undecodable 2xx body is a Decode error`() = runTest {
        server.enqueue(MockResponse().setBody("not json"))
        assertThrows<ApiException.Decode> { call { info() } }
        server.enqueue(MockResponse().setBody("""{"version": "1"}"""))
        assertThrows<ApiException.Decode> { call { info() } }
    }

    @Test
    fun `connection refused is Unreachable`() = runTest {
        server.shutdown()
        assertThrows<ApiException.Unreachable> { call { health() } }
    }

    @Test
    fun `IO failures map to Unreachable and TLS is flagged`() {
        assertInstanceOf(ApiException.Unreachable::class.java, ApiErrors.from(SocketTimeoutException("timeout")))
        assertInstanceOf(ApiException.Unreachable::class.java, ApiErrors.from(UnknownHostException("x")))
        val tls = ApiErrors.from(SSLHandshakeException("bad cert")) as ApiException.Unreachable
        assertTrue(tls.isTls)
        assertInstanceOf(ApiException.Decode::class.java, ApiErrors.from(SerializationException("x")))
        val already = ApiException.ProfileGone()
        assertTrue(ApiErrors.from(already) === already)
        assertInstanceOf(ApiException.Unreachable::class.java, ApiErrors.from(IOException("reset")))
    }

    @Test
    fun `Retry-After that is not a number of seconds is ignored`() {
        val http = ApiErrors.fromResponse(503, Fixtures.error("preparing"), "Wed, 21 Oct 2026 07:28:00 GMT") as ApiException.Http
        assertNull(http.retryAfterSeconds)
    }

    @Test
    fun `request bodies omit null fields`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.progressJson))
        call { putProgress(ProgressRequest("film", 12, 91, 1234.5, null)) }
        val body = server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()
        assertEquals("""{"item_type":"film","item_id":12,"media_file_id":91,"position_seconds":1234.5}""", body)
    }
}
