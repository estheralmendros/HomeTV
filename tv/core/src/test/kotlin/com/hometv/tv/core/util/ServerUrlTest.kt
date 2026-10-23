package com.hometv.tv.core.util

import com.hometv.tv.core.util.ServerUrlError.CLEARTEXT_NOT_ALLOWED
import com.hometv.tv.core.util.ServerUrlError.EMPTY
import com.hometv.tv.core.util.ServerUrlError.INVALID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ServerUrlTest {
    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "hometv.tail1234.ts.net                     | https://hometv.tail1234.ts.net",
            "  hometv.tail1234.ts.net  \t               | https://hometv.tail1234.ts.net",
            "https://hometv.tail1234.ts.net/            | https://hometv.tail1234.ts.net",
            "https://HomeTV.Tail1234.TS.net             | https://hometv.tail1234.ts.net",
            "HTTPS://hometv.tail1234.ts.net             | https://hometv.tail1234.ts.net",
            "https://hometv.tail1234.ts.net/api/info?x=1#f | https://hometv.tail1234.ts.net",
            "hometv.tail1234.ts.net/web/                | https://hometv.tail1234.ts.net",
            "https://hometv.tail1234.ts.net:8443/x      | https://hometv.tail1234.ts.net:8443",
            "https://hometv.tail1234.ts.net:443         | https://hometv.tail1234.ts.net",
            "100.101.102.103                            | https://100.101.102.103",
        ],
    )
    fun `normalizes valid addresses`(input: String, expected: String) {
        assertEquals(ServerUrlResult.Valid(expected), ServerUrl.normalize(input))
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "''                         | EMPTY",
            "'   '                      | EMPTY",
            "https://                   | EMPTY",
            "http://                    | EMPTY",
            "ftp://hometv.ts.net        | INVALID",
            "https://host name.ts.net   | INVALID",
            "https://:8000              | INVALID",
            "http://hometv.tail1234.ts.net | CLEARTEXT_NOT_ALLOWED",
            "http://10.0.2.2:8000       | CLEARTEXT_NOT_ALLOWED",
            "http://localhost:8000      | CLEARTEXT_NOT_ALLOWED",
        ],
    )
    fun `rejects invalid addresses in release builds`(input: String, error: ServerUrlError) {
        assertEquals(ServerUrlResult.Invalid(error), ServerUrl.normalize(input))
    }

    @Test
    fun `debug builds accept cleartext only to the emulator host and localhost`() {
        assertEquals(ServerUrlResult.Valid("http://10.0.2.2:8000"), ServerUrl.normalize("http://10.0.2.2:8000/", allowDebugCleartext = true))
        assertEquals(ServerUrlResult.Valid("http://localhost:8000"), ServerUrl.normalize("http://LOCALHOST:8000", allowDebugCleartext = true))
        assertEquals(
            ServerUrlResult.Invalid(CLEARTEXT_NOT_ALLOWED),
            ServerUrl.normalize("http://hometv.tail1234.ts.net", allowDebugCleartext = true),
        )
        assertEquals(ServerUrlResult.Valid("https://hometv.ts.net"), ServerUrl.normalize("hometv.ts.net", allowDebugCleartext = true))
        assertEquals(ServerUrlResult.Invalid(EMPTY), ServerUrl.normalize("", allowDebugCleartext = true))
        assertEquals(ServerUrlResult.Invalid(INVALID), ServerUrl.normalize("gopher://x", allowDebugCleartext = true))
    }

    @Test
    fun `resolves relative API urls against the server url`() {
        assertEquals(
            "https://hometv.ts.net/api/artwork/w342/abc.jpg",
            ServerUrl.resolve("https://hometv.ts.net", "/api/artwork/w342/abc.jpg"),
        )
        assertEquals(
            "http://10.0.2.2:8000/api/hls/5f2c/index.m3u8",
            ServerUrl.resolve("http://10.0.2.2:8000", "/api/hls/5f2c/index.m3u8"),
        )
        assertEquals("https://other.example/x.vtt", ServerUrl.resolve("https://hometv.ts.net", "https://other.example/x.vtt"))
    }

    @Test
    fun `extracts the host`() {
        assertEquals("hometv.ts.net", ServerUrl.host("https://hometv.ts.net:8443"))
        assertEquals(null, ServerUrl.host("not a url"))
    }
}
