package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiClientFactory
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `ServerRepository.validate` outcome matrix (prd3 §1.4). */
class ServerRepositoryTest {
    private val t = TestServer()
    private val repo = ServerRepository(ApiClientFactory.validationClient("UA"))
    private val info = """{"name": "HomeTV", "version": "0.1.0", "needs_bootstrap": true, "tmdb_configured": false, "avatars": []}"""

    @AfterEach
    fun tearDown() = t.shutdown()

    private fun failing(e: IOException) = ServerRepository(OkHttpClient.Builder().addInterceptor(Interceptor { throw e }).build())

    @Test
    fun `health then info gives Ok with the info`() = runTest {
        t.route("GET", "/api/health") { t.json("""{"status": "ok"}""") }
        t.route("GET", "/api/info") { t.json(info) }
        val result = repo.validate(t.url)
        assertTrue(result is ServerValidation.Ok, "$result")
        assertEquals(true, (result as ServerValidation.Ok).info.needs_bootstrap)
        assertEquals(listOf("GET /api/health", "GET /api/info"), t.paths())
        assertEquals(null, t.requests[0].getHeader("Authorization"))
    }

    @Test
    fun `503 health is ServerUnhealthy`() = runTest {
        t.route("GET", "/api/health") { t.json("""{"status": "error"}""", 503) }
        assertEquals(ServerValidation.ServerUnhealthy, repo.validate(t.url))
    }

    @Test
    fun `anything that is not a HomeTV server is NotHomeTvServer`() = runTest {
        t.route("GET", "/api/health") { MockResponse().setResponseCode(404).setBody("<html/>") }
        assertEquals(ServerValidation.NotHomeTvServer, repo.validate(t.url))

        t.route("GET", "/api/health") { MockResponse().setBody("<html>router login</html>") }
        assertEquals(ServerValidation.NotHomeTvServer, repo.validate(t.url))

        t.route("GET", "/api/health") { t.json("""{"status": "degraded"}""") }
        assertEquals(ServerValidation.NotHomeTvServer, repo.validate(t.url))

        t.route("GET", "/api/health") { t.json("""{"status": "ok"}""") }
        t.route("GET", "/api/info") { t.json("""{"version": "1"}""") }
        assertEquals(ServerValidation.NotHomeTvServer, repo.validate(t.url))

        t.route("GET", "/api/info") { t.json("""{"name": "  "}""") }
        assertEquals(ServerValidation.NotHomeTvServer, repo.validate(t.url))

        t.route("GET", "/api/info") { MockResponse().setResponseCode(404) }
        assertEquals(ServerValidation.NotHomeTvServer, repo.validate(t.url))
    }

    @Test
    fun `connection failures are Unreachable and TLS failures TlsError`() = runTest {
        t.shutdown()
        assertTrue(repo.validate(t.url) is ServerValidation.Unreachable)
        assertTrue(failing(UnknownHostException("hometv.ts.net")).validate("https://hometv.ts.net") is ServerValidation.Unreachable)
        assertEquals(ServerValidation.TlsError, failing(SSLHandshakeException("cert")).validate("https://hometv.ts.net"))
    }
}
