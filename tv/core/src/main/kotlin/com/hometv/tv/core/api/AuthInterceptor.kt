package com.hometv.tv.core.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <token>` only when the request goes to the server host, so tokens
 * never leak to other hosts and never appear in URLs (no `?token=`). The token is read per request.
 */
class AuthInterceptor(
    private val token: () -> String?,
    private val serverHost: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = token()
        if (current.isNullOrEmpty() || request.url.host != serverHost()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $current").build())
    }
}

/** `User-Agent: HomeTV-Android/<version> (Android <sdk>; <model>)`. */
class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}
