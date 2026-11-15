package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiClientFactory
import com.hometv.tv.core.api.ApiClients
import com.hometv.tv.core.api.Fixtures
import java.time.Instant
import java.util.Collections
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy

/** A MockWebServer with a routing dispatcher and a request log, plus a session store and API clients on it. */
class TestServer(initialToken: String? = "old", profileId: Long? = 5, expiresAt: Instant? = Instant.parse("2026-11-20T00:00:00Z")) {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val routes = mutableListOf<Pair<(RecordedRequest) -> Boolean, (RecordedRequest) -> MockResponse>>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val route = synchronized(routes) { routes.lastOrNull { it.first(request) } }
                return route?.second?.invoke(request) ?: MockResponse().setResponseCode(599).setBody("no route")
            }
        }
        server.start()
    }

    val url: String = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
    val store = InMemorySessionStore(Session(serverUrl = url, profileId = profileId, token = initialToken, expiresAt = expiresAt))
    val clients: ApiClients = ApiClientFactory.create(url, token = { store.session.value.token }, userAgent = "test")

    fun route(method: String, path: String, respond: (RecordedRequest) -> MockResponse) {
        synchronized(routes) { routes += Pair({ r: RecordedRequest -> r.method == method && r.path == path }, respond) }
    }

    fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")

    fun error(code: Int, errorCode: String, details: String = "{}") = json(Fixtures.error(errorCode, details), code)

    fun sessionJson(token: String, expiresAt: String = "2026-12-20T00:00:00Z", id: Long = 5) =
        """{"profile": {"id": $id, "name": "Esther", "avatar_id": "avatar-05", "is_admin": true}, "token": "$token", "expires_at": "$expiresAt"}"""

    /** An IOException OkHttp does not retry: headers sent, then the connection drops during the body. */
    fun networkFailure(): MockResponse = MockResponse().setBody("{\"padding\": \"" + "x".repeat(4096) + "\"}")
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)

    fun count(method: String, path: String) = requests.count { it.method == method && it.path == path }

    fun paths() = requests.map { "${it.method} ${it.path}" }

    fun shutdown() = server.shutdown()
}
