package com.hometv.tv.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AC-7.1: libVLC can't send a Bearer header, so the stream URL carries `?token=`; logs never show it. */
class VlcUrlsTest {
    private val stream = "http://s/api/media-files/3/stream"

    @Test
    fun `the token is url-encoded and appended with a question mark`() {
        assertEquals("$stream?token=a+b", VlcUrls.withToken(stream, "a b"))
        assertEquals("$stream?token=a%2Fb%3D%26c", VlcUrls.withToken(stream, "a/b=&c"))
    }

    @Test
    fun `an existing query gets an ampersand`() {
        assertEquals("$stream?x=1&token=tok", VlcUrls.withToken("$stream?x=1", "tok"))
    }

    @Test
    fun `an existing token parameter is replaced`() {
        assertEquals("$stream?token=new", VlcUrls.withToken("$stream?token=old", "new"))
        assertEquals("$stream?x=1&y=2&token=new", VlcUrls.withToken("$stream?x=1&token=old&y=2", "new"))
    }

    @Test
    fun `a null token leaves the url as it is`() {
        assertEquals(stream, VlcUrls.withToken(stream, null))
        assertEquals("$stream?x=1", VlcUrls.withToken("$stream?x=1", null))
    }

    @Test
    fun `redacted never contains the token value`() {
        val secret = "s3cr3t-T0k3n"
        for (url in listOf(VlcUrls.withToken(stream, secret), VlcUrls.withToken("$stream?x=1", secret), "$stream?token=$secret&y=2")) {
            val redacted = VlcUrls.redacted(url)
            assertFalse(secret in redacted, redacted)
            assertTrue("token=***" in redacted, redacted)
        }
        assertEquals(stream, VlcUrls.redacted(stream))
    }
}
