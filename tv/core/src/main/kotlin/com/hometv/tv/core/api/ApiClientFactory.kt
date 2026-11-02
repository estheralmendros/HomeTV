package com.hometv.tv.core.api

import com.hometv.tv.core.util.ServerUrl
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Lenient JSON. */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/** The clients for one server URL (rebuilt when the URL changes). */
class ApiClients(
    val serverUrl: String,
    /** Retrofit + Coil: connect 10 s, read 20 s, call 30 s. */
    val apiClient: OkHttpClient,
    /** Media3 + subtitle downloads: connect 10 s, read 45 s, no call timeout. */
    val streamClient: OkHttpClient,
    val api: HomeTvApi,
)

/** Builds the two OkHttp profiles (shared pool and interceptors) and Retrofit. */
object ApiClientFactory {
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val API_READ_TIMEOUT_MS = 20_000L
    const val API_CALL_TIMEOUT_MS = 30_000L

    /** Longer than the server's 30 s subtitle hold, 20 s segment / init.mp4 wait (+ 3 s ffmpeg kill). */
    const val STREAM_READ_TIMEOUT_MS = 45_000L
    const val VALIDATION_READ_TIMEOUT_MS = 10_000L

    fun create(
        serverUrl: String,
        token: () -> String?,
        userAgent: String,
        base: OkHttpClient = OkHttpClient(),
    ): ApiClients {
        val host = ServerUrl.host(serverUrl)
        val apiClient = base.newBuilder()
            .addInterceptor(UserAgentInterceptor(userAgent))
            .addInterceptor(AuthInterceptor(token) { host })
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(API_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(API_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val streamClient = apiClient.newBuilder()
            .readTimeout(STREAM_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val api = Retrofit.Builder()
            .baseUrl(serverUrl.trimEnd('/') + "/")
            .client(apiClient)
            .addConverterFactory(ApiJson.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
            .build()
            .create(HomeTvApi::class.java)
        return ApiClients(serverUrl, apiClient, streamClient, api)
    }

    /** For `ServerRepository.validate` before a URL is stored: connect 10 s, read 10 s. */
    fun validationClient(userAgent: String, base: OkHttpClient = OkHttpClient()): OkHttpClient = base.newBuilder()
        .addInterceptor(UserAgentInterceptor(userAgent))
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(VALIDATION_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(API_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
}
