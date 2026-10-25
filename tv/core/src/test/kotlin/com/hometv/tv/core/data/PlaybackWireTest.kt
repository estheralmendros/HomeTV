package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.api.PlaybackRequest
import com.hometv.tv.core.api.apiCall
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Playback endpoints on the wire (MockWebServer): paths and bodies match prd.md. */
class PlaybackWireTest {
    private val t = TestServer()
    private val guard = SessionGuard(t.store, { id -> apiCall { t.clients.api.createSession(id) } }, {})
    private val caps = ClientCaps(listOf("mp4", "mkv", "webm", "ts"), listOf("h264", "hevc"), listOf("aac", "ac3"), 6, true, true)
    private val source = OkHttpSubtitleSource(t.clients.streamClient)

    @AfterEach
    fun tearDown() = t.shutdown()

    @Test
    fun `playback POST body and hls answer`() = runTest {
        val repo = PlaybackRepository({ t.clients.api }, guard, backgroundScope, source)
        t.route("POST", "/api/media-files/91/playback") {
            t.json("""{"mode": "hls", "url": "/api/hls/5f2c/index.m3u8", "session_id": "5f2c", "audio_stream_index": 1, "audio_action": "aac_5_1", "duration_seconds": 9360.5, "subtitle_tracks": []}""")
        }
        val events = repo.requestPlayback(91, PlaybackRequest(caps, null, 1234.5, "old")).toList()
        val success = events.last() as PlaybackProgress.Success
        assertEquals("5f2c", success.response.session_id)
        val request = t.requests.single()
        assertEquals("Bearer old", request.getHeader("Authorization"))
        assertEquals(
            Json.parseToJsonElement(
                """{"client_caps": {"containers": ["mp4","mkv","webm","ts"], "video_codecs": ["h264","hevc"], "audio_codecs": ["aac","ac3"],
                   "max_audio_channels": 6, "hls": true, "can_select_audio_track": true},
                   "start_position_seconds": 1234.5, "previous_session_id": "old"}""",
            ),
            Json.parseToJsonElement(request.body.readUtf8()),
        )
    }

    @Test
    fun `playback error codes reach the caller`() = runTest {
        val repo = PlaybackRepository({ t.clients.api }, guard, backgroundScope, source)
        t.route("POST", "/api/media-files/91/playback") {
            t.error(500, "transcoder_failed", """{"detail": "keyframe_index_failed: no cues"}""")
        }
        val failure = repo.requestPlayback(91, PlaybackRequest(caps)).toList().last() as PlaybackProgress.Failure
        assertEquals("keyframe_index_failed: no cues", (failure.error as ApiException.Http).detail)
        t.route("POST", "/api/media-files/91/playback") { t.error(404, "file_missing") }
        val missing = repo.requestPlayback(91, PlaybackRequest(caps)).toList().last() as PlaybackProgress.Failure
        assertEquals("file_missing", (missing.error as ApiException.Http).code)
    }

    @Test
    fun `keep-alive and delete paths`() = runTest {
        val repo = PlaybackRepository({ t.clients.api }, guard, backgroundScope, source)
        t.route("POST", "/api/hls/5f2c/keepalive") { MockResponse().setResponseCode(204) }
        t.route("DELETE", "/api/hls/5f2c") { MockResponse().setResponseCode(204) }
        t.route("POST", "/api/hls/gone/keepalive") { t.error(404, "session_not_found") }
        assertEquals(KeepAliveResult.ALIVE, repo.keepAliveHlsSession("5f2c"))
        assertEquals(KeepAliveResult.SESSION_LOST, repo.keepAliveHlsSession("gone"))
        assertTrue(repo.deleteHlsSession("5f2c"))
        assertEquals(listOf("POST /api/hls/5f2c/keepalive", "POST /api/hls/gone/keepalive", "DELETE /api/hls/5f2c"), t.paths())
        assertEquals(0L, t.requests[0].bodySize)
    }

    @Test
    fun `subtitle source maps the subtitle answers`() = runTest {
        val url = t.url + "/api/media-files/91/subtitles/emb-3.vtt"
        val path = "/api/media-files/91/subtitles/emb-3.vtt"

        t.route("GET", path) { MockResponse().setBody("WEBVTT\n\n00:00.000 --> 00:01.000\nHola") }
        assertTrue((source.fetch(url) as SubtitleFetch.Ready).bytes.decodeToString().startsWith("WEBVTT"))
        assertEquals("Bearer old", t.requests.last().getHeader("Authorization"))

        t.route("GET", path) { t.error(503, "subtitle_pending").setHeader("Retry-After", "10") }
        assertEquals(SubtitleFetch.Pending(10), source.fetch(url))
        t.route("GET", path) { t.error(503, "subtitle_pending") }
        assertEquals(SubtitleFetch.Pending(10), source.fetch(url))

        for ((status, code) in listOf(500 to "subtitle_extraction_failed", 504 to "subtitle_timeout", 404 to "subtitle_not_found", 422 to "subtitle_not_text")) {
            t.route("GET", path) { t.error(status, code) }
            assertEquals(SubtitleFetch.Unavailable(code), source.fetch(url))
        }
        t.route("GET", path) { MockResponse().setResponseCode(502) }
        assertEquals(SubtitleFetch.Unavailable(null), source.fetch(url))

        // Over the 16 MiB subtitle cap: not a network failure (no retries), the track is unavailable.
        t.route("GET", path) { MockResponse().setBody("WEBVTT\n\n" + "a".repeat(17 shl 20)) }
        assertEquals(SubtitleFetch.Unavailable(null), source.fetch(url))

        t.route("GET", path) { t.error(401, "unauthorized") }
        assertThrows<ApiException.Unauthorized> { source.fetch(url) }
        t.route("GET", path) { t.networkFailure() }
        assertThrows<ApiException.Unreachable> { source.fetch(url) }
    }

    @Test
    fun `the subtitle loop over the wire - pending then ready`() = runTest {
        val repo = PlaybackRepository({ t.clients.api }, guard, backgroundScope, source, clockMs = { testScheduler.currentTime })
        val path = "/api/media-files/91/subtitles/emb-3.vtt"
        var calls = 0
        t.route("GET", path) { if (++calls == 1) t.error(503, "subtitle_pending").setHeader("Retry-After", "10") else MockResponse().setBody("WEBVTT") }
        val events = repo.subtitleLoop(t.url + path).toList()
        assertEquals(SubtitleFetch.Pending(10), events[0])
        assertTrue(events[1] is SubtitleFetch.Ready)
        assertEquals(10_000L, testScheduler.currentTime)
        assertNull(t.requests.firstOrNull { it.getHeader("Authorization") == null })
    }
}
