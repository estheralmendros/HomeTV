package com.hometv.tv.core.playback

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.ApiJson
import com.hometv.tv.core.playback.MessageKey.AUDIO_PREPARING_TIMEOUT
import com.hometv.tv.core.playback.MessageKey.AUDIO_SWITCH_FAILED
import com.hometv.tv.core.playback.MessageKey.AUDIO_TRACK_UNPLAYABLE
import com.hometv.tv.core.playback.PlaybackDecision.Preparing
import com.hometv.tv.core.playback.PlaybackDecision.Recover
import com.hometv.tv.core.playback.PlaybackDecision.RenewAndRecover
import com.hometv.tv.core.playback.PlaybackDecision.RetryExcludingAudio
import com.hometv.tv.core.playback.PlaybackDecision.RetryExcludingContainer
import com.hometv.tv.core.playback.PlaybackDecision.RetryExcludingVideo
import com.hometv.tv.core.playback.PlaybackDecision.RetryWithoutAudioIndex
import com.hometv.tv.core.playback.PlaybackDecision.ShowError
import com.hometv.tv.core.playback.PlaybackDecision.SubtitleUnavailable
import com.hometv.tv.core.playback.PlaybackErrorCodes as C
import com.hometv.tv.core.playback.PlayerAction.BACK
import com.hometv.tv.core.playback.PlayerAction.RETRY
import com.hometv.tv.core.playback.PlayerAction.RETRY_FROM_HERE
import com.hometv.tv.core.playback.PlayerAction.TRY_OTHER_VERSION
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Every playback failure case; outcomes carry message keys, never the server `detail` or `error.message`. */
class PlaybackErrorMapperTest {
    private val mapper = PlaybackErrorMapper

    private fun http(status: Int, code: String?, details: String = "{}") =
        ApiException.Http(status, code, "English diagnostic", ApiJson.parseToJsonElement(details).jsonObject, null)

    private fun shown(decision: PlaybackDecision): PlayerError = (decision as ShowError).error

    @Nested
    inner class RequestErrors {
        @Test
        fun `503 is told apart by code`() {
            assertEquals(Preparing, mapper.forRequestError(http(503, "preparing"), false))
            assertEquals(PlayerError(MessageKey.TOO_MANY_SESSIONS, actions = listOf(RETRY, BACK)), shown(mapper.forRequestError(http(503, "too_many_sessions"), false)))
            assertEquals(PlayerError(MessageKey.HLS_DISK_FULL, actions = listOf(RETRY, BACK)), shown(mapper.forRequestError(http(503, "hls_disk_full"), false)))
            assertEquals(PlayerError(MessageKey.SERVER_UNAVAILABLE, actions = listOf(RETRY, BACK)), shown(mapper.forRequestError(http(503, "something_new"), false)))
            assertEquals(PlayerError(MessageKey.SERVER_UNAVAILABLE, actions = listOf(RETRY, BACK)), shown(mapper.forRequestError(http(503, null), false)))
        }

        @Test
        fun `preparing timeout`() {
            assertEquals(PlayerError(MessageKey.PREPARING_TIMEOUT, actions = listOf(RETRY, BACK)), mapper.forPreparingTimeout())
        }

        @Test
        fun `keyframe_index_failed is terminal for the version, with no retry action`() {
            val e = http(500, "transcoder_failed", """{"detail": "keyframe_index_failed: ffprobe exited 1"}""")
            assertEquals(ShowError(PlayerError(MessageKey.KEYFRAME_INDEX_FAILED, actions = listOf(TRY_OTHER_VERSION, BACK)), refuseVersion = true), mapper.forRequestError(e, true))
            assertEquals(listOf(BACK), shown(mapper.forRequestError(e, false)).actions)
            // Another transcoder failure is a generic player error.
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forRequestError(http(500, "transcoder_failed", """{"detail": "ffmpeg died"}"""), false)).message)
        }

        @Test
        fun `404 file_missing and not_found`() {
            assertEquals(ShowError(PlayerError(MessageKey.FILE_MISSING, actions = listOf(TRY_OTHER_VERSION, BACK)), refuseVersion = true), mapper.forRequestError(http(404, "file_missing"), true))
            assertEquals(ShowError(PlayerError(MessageKey.ITEM_GONE, actions = listOf(BACK)), itemGone = true), mapper.forRequestError(http(404, "not_found"), true))
        }

        @Test
        fun `422 invalid_audio_stream retries once without the index`() {
            assertEquals(RetryWithoutAudioIndex, mapper.forRequestError(http(422, "invalid_audio_stream"), false))
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forRequestError(http(422, "invalid_audio_stream"), false, audioIndexRetried = true)).message)
        }

        @Test
        fun `network, TLS, auth and unknown codes`() {
            assertEquals(PlayerError(MessageKey.CONNECTION_ERROR, actions = listOf(RETRY, BACK)), shown(mapper.forRequestError(ApiException.Unreachable(IOException("x")), false)))
            assertEquals(MessageKey.TLS_ERROR, shown(mapper.forRequestError(ApiException.Unreachable(SSLHandshakeException("x")), false)).message)
            assertEquals(PlaybackDecision.AuthExpired, mapper.forRequestError(ApiException.ProfileGone(), false))
            assertEquals(MessageKey.SIGN_IN_FAILED, shown(mapper.forRequestError(ApiException.Unauthorized("unauthorized", null), false)).message)
            assertEquals(PlayerError(MessageKey.UNEXPECTED_ERROR, args = listOf("brand_new_code"), actions = listOf(RETRY, BACK)), shown(mapper.forRequestError(http(400, "brand_new_code"), false)))
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forRequestError(http(502, null), false)).message)
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forRequestError(ApiException.Decode(IllegalStateException()), false)).message)
        }
    }

    @Nested
    inner class Unsupported {
        @ParameterizedTest
        @CsvSource(
            "VIDEO_CODEC_UNSUPPORTED, REASON_VIDEO_CODEC_UNSUPPORTED",
            "NO_VIDEO_STREAM, REASON_NO_VIDEO_STREAM",
            "HLS_NOT_SUPPORTED, REASON_HLS_NOT_SUPPORTED",
            "PROBE_FAILED, REASON_PROBE_FAILED",
            "DURATION_UNKNOWN, REASON_DURATION_UNKNOWN",
            "KEYFRAMES_TOO_SPARSE, REASON_KEYFRAMES_TOO_SPARSE",
            "UNKNOWN, REASON_UNKNOWN",
        )
        fun `each reason has its own catalog key`(reason: UnsupportedReason, key: MessageKey) {
            val error = mapper.forUnsupported(reason, F.hevc(1), hasOtherVersion = true)
            assertEquals(MessageKey.PLAYBACK_UNSUPPORTED, error.message)
            assertEquals(key, error.reason)
            assertEquals(listOf(TRY_OTHER_VERSION, BACK), error.actions)
        }

        @Test
        fun `the codec argument is the file's video codec in upper case, and no other version means Back only`() {
            assertEquals(listOf("HEVC"), mapper.forUnsupported(UnsupportedReason.VIDEO_CODEC_UNSUPPORTED, F.hevc(1), false).args)
            assertEquals(listOf(BACK), mapper.forUnsupported(UnsupportedReason.PROBE_FAILED, F.hevc(1), false).actions)
            assertEquals(emptyList<String>(), mapper.forUnsupported(UnsupportedReason.DURATION_UNKNOWN, F.hevc(1), false).args)
        }
    }

    @Nested
    inner class PlayerFailures {
        private val direct = FailureContext(PlaybackMode.DIRECT, nowMs = 100_000, videoCodec = "hevc", audioCodec = "dts", container = "mkv")
        private val hls = FailureContext(PlaybackMode.HLS, nowMs = 100_000, videoCodec = "h264", audioCodec = "aac", audioAction = "copy")

        @Test
        fun `stream 401 renews and recovers (never the Picker), at most once per 30 s`() {
            val f = PlayerFailure(C.IO_BAD_HTTP_STATUS, httpStatus = 401, urlKind = UrlKind.STREAM)
            assertEquals(RenewAndRecover, mapper.forPlayerFailure(f, direct))
            assertEquals(RenewAndRecover, mapper.forPlayerFailure(f.copy(urlKind = UrlKind.HLS), hls.copy(lastRenewalAtMs = 69_000)))
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(f, direct.copy(lastRenewalAtMs = 80_000))).message)
        }

        @Test
        fun `404 on the stream is file missing, on HLS a rate-limited recovery`() {
            val stream404 = PlayerFailure(C.IO_BAD_HTTP_STATUS, httpStatus = 404, urlKind = UrlKind.STREAM, bodyCode = "file_missing")
            assertEquals(PlayerError(MessageKey.FILE_MISSING, actions = listOf(BACK)), shown(mapper.forPlayerFailure(stream404, direct)))
            val hls404 = PlayerFailure(C.IO_BAD_HTTP_STATUS, httpStatus = 404, urlKind = UrlKind.HLS, bodyCode = "session_not_found")
            assertEquals(Recover, mapper.forPlayerFailure(hls404, hls))
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(hls404, hls.copy(lastRecoveryAtMs = 90_000))).message)
        }

        @ParameterizedTest
        @ValueSource(ints = [500, 504])
        fun `HLS 500 and 504 offer retry from here`(status: Int) {
            val f = PlayerFailure(C.IO_BAD_HTTP_STATUS, httpStatus = status, urlKind = UrlKind.HLS)
            assertEquals(PlayerError(MessageKey.HLS_FAILED, actions = listOf(RETRY_FROM_HERE, BACK)), shown(mapper.forPlayerFailure(f, hls)))
        }

        @ParameterizedTest
        @ValueSource(ints = [C.IO_NETWORK_CONNECTION_FAILED, C.IO_NETWORK_CONNECTION_TIMEOUT])
        fun `network loss is the connection error with retry from here`(code: Int) {
            assertEquals(PlayerError(MessageKey.CONNECTION_ERROR, actions = listOf(RETRY_FROM_HERE, BACK)), shown(mapper.forPlayerFailure(PlayerFailure(code, urlKind = UrlKind.STREAM), direct)))
        }

        @Test
        fun `direct renderer failures retry with the failing codec type excluded, once`() {
            val video = PlayerFailure(C.DECODER_INIT_FAILED, RendererType.VIDEO, "video/hevc")
            assertEquals(RetryExcludingVideo("hevc"), mapper.forPlayerFailure(video, direct))
            val audio = PlayerFailure(C.DECODING_FAILED, RendererType.AUDIO, "audio/vnd.dts")
            assertEquals(RetryExcludingAudio("dts"), mapper.forPlayerFailure(audio, direct))
            assertEquals(RetryExcludingAudio("dts"), mapper.forPlayerFailure(PlayerFailure(C.AUDIO_TRACK_INIT_FAILED), direct))
            // The second failure of the same type is generic; the other type still retries.
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(video, direct.copy(videoRetried = true))).message)
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(audio, direct.copy(audioRetried = true))).message)
            assertEquals(RetryExcludingVideo("hevc"), mapper.forPlayerFailure(video, direct.copy(audioRetried = true)))
            // A renderer error of unknown type never removes the video codec.
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(PlayerFailure(C.DECODING_FAILED, RendererType.OTHER, "text/x-unknown"), direct)).message)
        }

        @Test
        fun `HLS decoder failure retries only an audio failure with audio_action copy`() {
            val audio = PlayerFailure(C.DECODING_FAILED, RendererType.AUDIO, "audio/mp4a-latm")
            assertEquals(RetryExcludingAudio("aac"), mapper.forPlayerFailure(audio, hls))
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(audio, hls.copy(audioAction = "aac_stereo"))).message)
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(audio, hls.copy(audioRetried = true))).message)
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(PlayerFailure(C.DECODING_FAILED, RendererType.VIDEO, "video/avc"), hls)).message)
        }

        @ParameterizedTest
        @ValueSource(ints = [C.PARSING_CONTAINER_UNSUPPORTED, C.PARSING_CONTAINER_MALFORMED])
        fun `direct container parse failures on the stream retry without the container, once`(code: Int) {
            val f = PlayerFailure(code, urlKind = UrlKind.STREAM)
            assertEquals(RetryExcludingContainer("mkv"), mapper.forPlayerFailure(f, direct))
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(f, direct.copy(containerRetried = true))).message)
            // urlKind OTHER in direct mode goes to the generic row, never to the fallback.
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(f.copy(urlKind = UrlKind.OTHER), direct)).message)
        }

        @Test
        fun `HLS manifest parse errors are generic`() {
            val f = PlayerFailure(C.PARSING_MANIFEST_MALFORMED, urlKind = UrlKind.HLS)
            assertEquals(PlayerError(MessageKey.PLAYBACK_FAILED, actions = listOf(RETRY_FROM_HERE, BACK)), shown(mapper.forPlayerFailure(f, hls)))
        }

        @Test
        fun `side-loaded subtitle parse or IO failures make the subtitle unavailable in any mode`() {
            for (code in listOf(C.PARSING_CONTAINER_MALFORMED, C.PARSING_CONTAINER_UNSUPPORTED, C.IO_FILE_NOT_FOUND, C.IO_UNSPECIFIED)) {
                assertEquals(SubtitleUnavailable, mapper.forPlayerFailure(PlayerFailure(code, urlKind = UrlKind.SUBTITLE), direct))
                assertEquals(SubtitleUnavailable, mapper.forPlayerFailure(PlayerFailure(code, urlKind = UrlKind.SUBTITLE), hls))
            }
        }

        @Test
        fun `generic errors offer another version when there is one`() {
            assertEquals(listOf(RETRY_FROM_HERE, TRY_OTHER_VERSION, BACK), shown(mapper.forPlayerFailure(PlayerFailure(1000), direct.copy(hasOtherVersion = true))).actions)
        }

        @Test
        fun `silent track failures are handled as decoder failures of that type`() {
            assertEquals(RetryExcludingVideo("hevc"), mapper.forTrackFailure(TrackFailure.VIDEO, direct))
            assertEquals(RetryExcludingAudio("dts"), mapper.forTrackFailure(TrackFailure.AUDIO, direct))
            assertEquals(null, mapper.forTrackFailure(TrackFailure.NONE, direct))
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forTrackFailure(TrackFailure.AUDIO, direct.copy(audioRetried = true))!!).message)
        }
    }

    @Nested
    inner class Switch {
        @Test
        fun `a failed audio switch toasts by outcome`() {
            assertEquals(SwitchFailure.Toast(AUDIO_TRACK_UNPLAYABLE), mapper.forSwitchUnsupported())
            assertEquals(SwitchFailure.Toast(AUDIO_TRACK_UNPLAYABLE), mapper.forSwitchError(http(500, "transcoder_failed", """{"detail": "keyframe_index_failed: x"}""")))
            assertEquals(SwitchFailure.Toast(AUDIO_TRACK_UNPLAYABLE), mapper.forSwitchError(http(422, "invalid_audio_stream")))
            assertEquals(SwitchFailure.Toast(AUDIO_SWITCH_FAILED), mapper.forSwitchError(http(500, "transcoder_failed", """{"detail": "ffmpeg"}""")))
            assertEquals(SwitchFailure.Toast(AUDIO_SWITCH_FAILED), mapper.forSwitchError(http(503, "busy")))
            assertEquals(SwitchFailure.Toast(MessageKey.TOO_MANY_SESSIONS), mapper.forSwitchError(http(503, "too_many_sessions")))
            assertEquals(SwitchFailure.Toast(MessageKey.HLS_DISK_FULL), mapper.forSwitchError(http(503, "hls_disk_full")))
            assertEquals(SwitchFailure.Toast(AUDIO_PREPARING_TIMEOUT), mapper.forSwitchPreparingTimeout())
            assertEquals(SwitchFailure.Toast(AUDIO_SWITCH_FAILED), mapper.forSwitchError(http(404, "file_missing")))
            assertEquals(SwitchFailure.Toast(AUDIO_SWITCH_FAILED), mapper.forSwitchError(http(404, "not_found")))
            assertEquals(SwitchFailure.Toast(AUDIO_SWITCH_FAILED), mapper.forSwitchError(ApiException.Unreachable(IOException())))
            assertEquals(SwitchFailure.Toast(AUDIO_SWITCH_FAILED), mapper.forSwitchError(ApiException.Unauthorized(null, null)))
            assertEquals(SwitchFailure.AuthExpired, mapper.forSwitchError(ApiException.ProfileGone()))
        }
    }

    @Test
    fun `preparing estimate`() {
        assertEquals(PreparingEstimate.None, mapper.preparingEstimate(null))
        assertEquals(PreparingEstimate.LessThanAMinute, mapper.preparingEstimate(59))
        assertEquals(PreparingEstimate.Minutes(1), mapper.preparingEstimate(60))
        assertEquals(PreparingEstimate.Minutes(7), mapper.preparingEstimate(412))
    }

    @Test
    fun `outcomes never carry the server message or detail`() {
        val e = ApiException.Http(500, "transcoder_failed", "Transcoder failed badly", JsonObject(emptyMap()), null)
        val error = shown(mapper.forRequestError(e, false))
        assertFalse(error.args.any { "Transcoder" in it })
        assertTrue(error.args.isEmpty())
    }

    /** libVLC fallback rules (FR-2.2 rows 1–10, FR-2.3). */
    @Nested
    inner class Engines {
        private val direct = FailureContext(PlaybackMode.DIRECT, nowMs = 100_000, videoCodec = "mpeg4", audioCodec = "mp3", container = "mkv")
        private val hls = FailureContext(PlaybackMode.HLS, nowMs = 100_000, videoCodec = "h264", audioCodec = "aac", audioAction = "copy")
        private val vlcCtx = direct.copy(engine = PlaybackEngine.VLC)
        private val vlcFailure = PlayerFailure(C.VLC_ERROR, engine = PlaybackEngine.VLC)

        @Test
        fun `AC-2_1 a VLC failure renews once (no 30 s limit), then the generic screen`() {
            assertEquals(RenewAndRecover, mapper.forPlayerFailure(vlcFailure, vlcCtx))
            assertEquals(RenewAndRecover, mapper.forPlayerFailure(vlcFailure, vlcCtx.copy(lastRenewalAtMs = 95_000)))
            assertEquals(
                ShowError(PlayerError(MessageKey.PLAYBACK_FAILED, actions = listOf(RETRY_FROM_HERE, TRY_OTHER_VERSION, BACK))),
                mapper.forPlayerFailure(vlcFailure, vlcCtx.copy(vlcRecovered = true, hasOtherVersion = true)),
            )
            assertEquals(
                ShowError(PlayerError(MessageKey.PLAYBACK_FAILED, actions = listOf(RETRY_FROM_HERE, BACK))),
                mapper.forPlayerFailure(vlcFailure, vlcCtx.copy(vlcRecovered = true)),
            )
        }

        @Test
        fun `VLC_ERROR lies outside every Media3 range`() {
            assertFalse(C.isIo(C.VLC_ERROR))
            assertFalse(C.isParsing(C.VLC_ERROR))
            assertFalse(C.VLC_ERROR in C.DECODER_ERRORS)
        }

        @Test
        fun `AC-2_2 an EXO video decoder failure falls back to VLC in DIRECT and HLS when allowed`() {
            val video = PlayerFailure(C.DECODING_FAILED, RendererType.VIDEO, "video/mp4v-es")
            assertEquals(PlaybackDecision.FallBackToVlc, mapper.forPlayerFailure(video, direct.copy(vlcFallbackAllowed = true)))
            assertEquals(PlaybackDecision.FallBackToVlc, mapper.forPlayerFailure(video, hls.copy(vlcFallbackAllowed = true)))
            assertEquals(RetryExcludingVideo("mpeg4"), mapper.forPlayerFailure(video, direct))
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(video, hls)).message)
        }

        @Test
        fun `AC-2_3 a silent video track falls back on EXO, is generic on VLC`() {
            assertEquals(PlaybackDecision.FallBackToVlc, mapper.forTrackFailure(TrackFailure.VIDEO, direct.copy(vlcFallbackAllowed = true)))
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forTrackFailure(TrackFailure.VIDEO, vlcCtx)!!).message)
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forTrackFailure(TrackFailure.AUDIO, vlcCtx)!!).message)
            assertEquals(null, mapper.forTrackFailure(TrackFailure.NONE, vlcCtx))
        }

        @Test
        fun `AC-2_4 an EXO audio decoder failure still retries without the audio codec`() {
            val audio = PlayerFailure(C.DECODING_FAILED, RendererType.AUDIO, "audio/mpeg")
            assertEquals(RetryExcludingAudio("mp3"), mapper.forPlayerFailure(audio, direct.copy(vlcFallbackAllowed = true)))
            assertEquals(RetryExcludingAudio("mp3"), mapper.forTrackFailure(TrackFailure.AUDIO, direct.copy(vlcFallbackAllowed = true)))
        }

        @Test
        fun `AC-2_5 container parsing - the container retry first, then VLC, subtitles unchanged`() {
            val f = PlayerFailure(C.PARSING_CONTAINER_MALFORMED, urlKind = UrlKind.STREAM)
            val allowed = direct.copy(vlcFallbackAllowed = true)
            assertEquals(RetryExcludingContainer("mkv"), mapper.forPlayerFailure(f, allowed))
            assertEquals(PlaybackDecision.FallBackToVlc, mapper.forPlayerFailure(f, allowed.copy(containerRetried = true)))
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(f, direct.copy(containerRetried = true))).message)
            assertEquals(SubtitleUnavailable, mapper.forPlayerFailure(f.copy(urlKind = UrlKind.SUBTITLE), allowed))
            // Row 8: any parsing failure that would end on the generic screen.
            assertEquals(PlaybackDecision.FallBackToVlc, mapper.forPlayerFailure(PlayerFailure(C.PARSING_MANIFEST_MALFORMED, urlKind = UrlKind.HLS), hls.copy(vlcFallbackAllowed = true)))
            // A non-parsing failure that would be generic tries VLC first (AC-2.14); without libVLC it stays generic.
            assertEquals(PlaybackDecision.FallBackToVlc, mapper.forPlayerFailure(PlayerFailure(1000), allowed))
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(PlayerFailure(1000), direct)).message)
        }

        @Test
        fun `AC-2_7 an HLS 5xx falls back to VLC before today's HLS_FAILED early return`() {
            val f = PlayerFailure(C.IO_BAD_HTTP_STATUS, httpStatus = 500, urlKind = UrlKind.HLS)
            assertEquals(PlaybackDecision.FallBackToVlc, mapper.forPlayerFailure(f, hls.copy(vlcFallbackAllowed = true)))
            assertEquals(PlayerError(MessageKey.HLS_FAILED, actions = listOf(RETRY_FROM_HERE, BACK)), shown(mapper.forPlayerFailure(f, hls)))
            // A stream 401 keeps its renew rule.
            assertEquals(RenewAndRecover, mapper.forPlayerFailure(PlayerFailure(C.IO_BAD_HTTP_STATUS, httpStatus = 401, urlKind = UrlKind.STREAM), direct.copy(vlcFallbackAllowed = true)))
        }

        @Test
        fun `AC-2_8 only the remux reasons fall back, and only when allowed`() {
            for (reason in UnsupportedReason.entries) {
                val remux = reason in setOf(UnsupportedReason.HLS_NOT_SUPPORTED, UnsupportedReason.DURATION_UNKNOWN, UnsupportedReason.KEYFRAMES_TOO_SPARSE)
                assertEquals(remux, mapper.fallsBackToVlc(reason, vlcFallbackAllowed = true), "$reason")
                assertFalse(mapper.fallsBackToVlc(reason, vlcFallbackAllowed = false), "$reason")
            }
            assertEquals(setOf(UnsupportedReason.HLS_NOT_SUPPORTED, UnsupportedReason.DURATION_UNKNOWN, UnsupportedReason.KEYFRAMES_TOO_SPARSE), mapper.REMUX_REASONS)
        }

        @Test
        fun `AC-2_9 a keyframe index failure falls back to VLC when allowed`() {
            val e = http(500, "transcoder_failed", """{"detail": "keyframe_index_failed: ffprobe exited 1"}""")
            assertEquals(PlaybackDecision.FallBackToVlc, mapper.forRequestError(e, false, vlcFallbackAllowed = true))
            assertEquals(ShowError(PlayerError(MessageKey.KEYFRAME_INDEX_FAILED, actions = listOf(BACK)), refuseVersion = true), mapper.forRequestError(e, false, vlcFallbackAllowed = false))
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forRequestError(http(500, "transcoder_failed", """{"detail": "ffmpeg died"}"""), false)).message)
        }

        // ---- Addendum: libVLC as the last resort before the generic screen (prd.md FR-2.4) ----

        private val generic = ShowError(PlayerError(MessageKey.PLAYBACK_FAILED, actions = listOf(RETRY_FROM_HERE, BACK)))

        @Test
        fun `AC-2_13 an HLS 500 whose load was not recorded (urlKind OTHER) falls back to VLC`() {
            // "Annie (1982).avi": the server conversion died; the failure reached the mapper without an HLS load record.
            val f = PlayerFailure(C.IO_BAD_HTTP_STATUS, httpStatus = 500, urlKind = UrlKind.OTHER)
            assertEquals(PlaybackDecision.FallBackToVlc, mapper.forPlayerFailure(f, hls.copy(vlcFallbackAllowed = true)))
            assertEquals(generic, mapper.forPlayerFailure(f, hls))
        }

        @Test
        fun `AC-2_14 every EXO failure that would end on the generic screen tries VLC once first`() {
            val allowedDirect = direct.copy(vlcFallbackAllowed = true)
            val allowedHls = hls.copy(vlcFallbackAllowed = true)
            val generics = listOf(
                PlayerFailure(1000) to allowedDirect, // ERROR_CODE_UNSPECIFIED
                PlayerFailure(C.IO_UNSPECIFIED, urlKind = UrlKind.HLS) to allowedHls,
                PlayerFailure(C.IO_READ_POSITION_OUT_OF_RANGE, urlKind = UrlKind.STREAM) to allowedDirect,
                // The second audio decoder failure (the exclusion retry was used).
                PlayerFailure(C.DECODING_FAILED, RendererType.AUDIO, "audio/mpeg") to allowedDirect.copy(audioRetried = true),
                // An HLS audio failure the server can't fix (already converted to AAC).
                PlayerFailure(C.DECODING_FAILED, RendererType.AUDIO, "audio/mp4a-latm") to allowedHls.copy(audioAction = "aac_stereo"),
                PlayerFailure(C.DECODING_FAILED, RendererType.OTHER) to allowedDirect,
            )
            for ((failure, ctx) in generics) {
                assertEquals(PlaybackDecision.FallBackToVlc, mapper.forPlayerFailure(failure, ctx), "$failure")
                assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forPlayerFailure(failure, ctx.copy(vlcFallbackAllowed = false))).message, "$failure")
            }
            assertEquals(PlaybackDecision.FallBackToVlc, mapper.forTrackFailure(TrackFailure.AUDIO, allowedDirect.copy(audioRetried = true)))
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forTrackFailure(TrackFailure.AUDIO, direct.copy(audioRetried = true))!!).message)
        }

        @Test
        fun `AC-2_15 network, auth, missing file and session recovery keep their rules`() {
            val allowedDirect = direct.copy(vlcFallbackAllowed = true)
            val allowedHls = hls.copy(vlcFallbackAllowed = true)
            for (code in listOf(C.IO_NETWORK_CONNECTION_FAILED, C.IO_NETWORK_CONNECTION_TIMEOUT)) {
                assertEquals(MessageKey.CONNECTION_ERROR, shown(mapper.forPlayerFailure(PlayerFailure(code, urlKind = UrlKind.HLS), allowedHls)).message)
            }
            val stream401 = PlayerFailure(C.IO_BAD_HTTP_STATUS, httpStatus = 401, urlKind = UrlKind.STREAM)
            assertEquals(generic, mapper.forPlayerFailure(stream401, allowedDirect.copy(lastRenewalAtMs = 95_000)))
            val stream404 = PlayerFailure(C.IO_BAD_HTTP_STATUS, httpStatus = 404, urlKind = UrlKind.STREAM)
            assertEquals(MessageKey.FILE_MISSING, shown(mapper.forPlayerFailure(stream404, allowedDirect)).message)
            val hls404 = PlayerFailure(C.IO_BAD_HTTP_STATUS, httpStatus = 404, urlKind = UrlKind.HLS)
            assertEquals(Recover, mapper.forPlayerFailure(hls404, allowedHls))
            assertEquals(generic, mapper.forPlayerFailure(hls404, allowedHls.copy(lastRecoveryAtMs = 95_000)))
            // libVLC's own failures never fall back (vlcFallbackAllowed is false on VLC; the VLC rows win anyway).
            assertEquals(generic, mapper.forPlayerFailure(vlcFailure, vlcCtx.copy(vlcRecovered = true)))
        }

        @Test
        fun `AC-2_16 a server 5xx answer to the EXO request falls back to VLC, 4xx and 503 keep their screens`() {
            for (e in listOf(http(500, "transcoder_failed", """{"detail": "ffmpeg died"}"""), http(500, "internal_error"), http(502, null))) {
                assertEquals(PlaybackDecision.FallBackToVlc, mapper.forRequestError(e, false, vlcFallbackAllowed = true), "${e.status} ${e.code}")
                assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forRequestError(e, false)).message, "${e.status} ${e.code}")
            }
            assertEquals(MessageKey.FILE_MISSING, shown(mapper.forRequestError(http(404, "file_missing"), false, vlcFallbackAllowed = true)).message)
            assertEquals(MessageKey.ITEM_GONE, shown(mapper.forRequestError(http(404, "not_found"), false, vlcFallbackAllowed = true)).message)
            assertEquals(MessageKey.SERVER_UNAVAILABLE, shown(mapper.forRequestError(http(503, "internal_error"), false, vlcFallbackAllowed = true)).message)
            assertEquals(MessageKey.PLAYBACK_FAILED, shown(mapper.forRequestError(http(422, "validation_error"), false, vlcFallbackAllowed = true)).message)
            assertEquals(MessageKey.CONNECTION_ERROR, shown(mapper.forRequestError(ApiException.Unreachable(IOException("down")), false, vlcFallbackAllowed = true)).message)
        }
    }
}
