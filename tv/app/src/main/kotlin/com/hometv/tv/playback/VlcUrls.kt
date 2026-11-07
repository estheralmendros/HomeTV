package com.hometv.tv.playback

import java.net.URLEncoder

/** libVLC can't send a Bearer header: the direct stream URL carries `?token=` (never logged). */
object VlcUrls {
    private const val TOKEN = "token"

    /** [url] + `token=<urlencoded token>` (`?` or `&`); an existing `token` parameter is replaced; null token → [url]. */
    fun withToken(url: String, token: String?): String {
        if (token == null) return url
        val base = withoutToken(url)
        val separator = if ('?' in base) "&" else "?"
        return "$base$separator$TOKEN=${URLEncoder.encode(token, Charsets.UTF_8.name())}"
    }

    /** For logs only: the URL with the `token` value replaced by `***`. */
    fun redacted(url: String): String {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return url
        val params = query.split('&').joinToString("&") { if (it.substringBefore('=') == TOKEN) "$TOKEN=***" else it }
        return "${url.substringBefore('?')}?$params"
    }

    private fun withoutToken(url: String): String {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return url
        val kept = query.split('&').filter { it.isNotEmpty() && it.substringBefore('=') != TOKEN }
        val path = url.substringBefore('?')
        return if (kept.isEmpty()) path else "$path?${kept.joinToString("&")}"
    }
}
