package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiJson
import com.hometv.tv.core.api.Health
import com.hometv.tv.core.api.ServerInfo
import com.hometv.tv.core.api.await
import com.hometv.tv.core.util.ServerUrl
import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request

/** Outcome of validating a server address (message keys; prd3 §1.4/§1.6). */
sealed interface ServerValidation {
    data class Ok(val info: ServerInfo) : ServerValidation

    /** UnknownHost / ConnectException / timeout: the Tailscale hint. */
    data class Unreachable(val cause: Throwable) : ServerValidation

    /** Non-JSON, missing fields, 404... */
    data object NotHomeTvServer : ServerValidation

    /** `/api/health` answered 503 (database unavailable). */
    data object ServerUnhealthy : ServerValidation

    data object TlsError : ServerValidation
}

/**
 * Validates a normalized server URL before it is stored: `GET /api/health` must answer 200
 * `{"status":"ok"}`, then `GET /api/info` must decode with a `name`. Uses the validation client
 * (10 s connect / 10 s read, no token).
 */
class ServerRepository(private val client: OkHttpClient) {
    suspend fun validate(serverUrl: String): ServerValidation = try {
        val health = get(serverUrl, "/api/health")
        when {
            health.code == 503 -> ServerValidation.ServerUnhealthy
            health.code != 200 -> ServerValidation.NotHomeTvServer
            decode(health.body) { ApiJson.decodeFromString(Health.serializer(), it) }?.status != "ok" -> ServerValidation.NotHomeTvServer
            else -> {
                val info = get(serverUrl, "/api/info")
                val decoded = if (info.code == 200) decode(info.body) { ApiJson.decodeFromString(ServerInfo.serializer(), it) } else null
                if (decoded == null || decoded.name.isBlank()) ServerValidation.NotHomeTvServer else ServerValidation.Ok(decoded)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SSLException) {
        ServerValidation.TlsError
    } catch (e: IOException) {
        ServerValidation.Unreachable(e)
    }

    private class Answer(val code: Int, val body: String)

    private suspend fun get(serverUrl: String, path: String): Answer {
        val request = Request.Builder().url(ServerUrl.resolve(serverUrl, path)).header("Accept", "application/json").build()
        return client.newCall(request).await().use { Answer(it.code, it.body?.string().orEmpty()) }
    }

    private fun <T> decode(body: String, block: (String) -> T): T? = runCatching { block(body) }.getOrNull()
}
