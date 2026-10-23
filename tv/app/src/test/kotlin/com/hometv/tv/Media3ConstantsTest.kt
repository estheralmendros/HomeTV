package com.hometv.tv

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import com.hometv.tv.core.playback.AudioEncodings
import com.hometv.tv.core.playback.Mimes
import com.hometv.tv.core.playback.PlaybackErrorCodes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `core` mirrors Media3 constants (it has no Android dependency); they must stay equal. */
class Media3ConstantsTest {
    @Test
    fun `audio encodings`() {
        assertEquals(C.ENCODING_AC3, AudioEncodings.AC3)
        assertEquals(C.ENCODING_E_AC3, AudioEncodings.E_AC3)
        assertEquals(C.ENCODING_E_AC3_JOC, AudioEncodings.E_AC3_JOC)
        assertEquals(C.ENCODING_DTS, AudioEncodings.DTS)
        assertEquals(C.ENCODING_DTS_HD, AudioEncodings.DTS_HD)
        assertEquals(C.ENCODING_DOLBY_TRUEHD, AudioEncodings.DOLBY_TRUEHD)
    }

    @Test
    fun `playback error codes`() {
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackErrorCodes.IO_UNSPECIFIED)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackErrorCodes.IO_NETWORK_CONNECTION_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackErrorCodes.IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackErrorCodes.IO_BAD_HTTP_STATUS)
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackErrorCodes.IO_FILE_NOT_FOUND)
        assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, PlaybackErrorCodes.IO_READ_POSITION_OUT_OF_RANGE)
        assertEquals(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackErrorCodes.PARSING_CONTAINER_MALFORMED)
        assertEquals(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackErrorCodes.PARSING_MANIFEST_MALFORMED)
        assertEquals(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackErrorCodes.PARSING_CONTAINER_UNSUPPORTED)
        assertEquals(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED, PlaybackErrorCodes.PARSING_MANIFEST_UNSUPPORTED)
        assertEquals(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackErrorCodes.DECODER_INIT_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackErrorCodes.DECODER_QUERY_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackErrorCodes.DECODING_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, PlaybackErrorCodes.DECODING_FORMAT_EXCEEDS_CAPABILITIES)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackErrorCodes.DECODING_FORMAT_UNSUPPORTED)
        assertEquals(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackErrorCodes.AUDIO_TRACK_INIT_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED, PlaybackErrorCodes.AUDIO_TRACK_WRITE_FAILED)
    }

    @Test
    fun `mime types`() {
        assertEquals(MimeTypes.VIDEO_H264, Mimes.VIDEO_H264)
        assertEquals(MimeTypes.VIDEO_H265, Mimes.VIDEO_HEVC)
        assertEquals(MimeTypes.VIDEO_VP9, Mimes.VIDEO_VP9)
        assertEquals(MimeTypes.VIDEO_AV1, Mimes.VIDEO_AV1)
        assertEquals(MimeTypes.VIDEO_MPEG2, Mimes.VIDEO_MPEG2)
        assertEquals(MimeTypes.VIDEO_MP4V, Mimes.VIDEO_MP4V)
        assertEquals(MimeTypes.AUDIO_OPUS, Mimes.AUDIO_OPUS)
        assertEquals(MimeTypes.AUDIO_VORBIS, Mimes.AUDIO_VORBIS)
        assertEquals(MimeTypes.AUDIO_FLAC, Mimes.AUDIO_FLAC)
        assertEquals(MimeTypes.AUDIO_AC3, Mimes.AUDIO_AC3)
        assertEquals(MimeTypes.AUDIO_E_AC3, Mimes.AUDIO_E_AC3)
        assertEquals(MimeTypes.AUDIO_DTS, Mimes.AUDIO_DTS)
        assertEquals(MimeTypes.AUDIO_TRUEHD, Mimes.AUDIO_TRUEHD)
        assertEquals(MimeTypes.APPLICATION_M3U8, Mimes.APPLICATION_M3U8)
        assertEquals(MimeTypes.TEXT_VTT, Mimes.TEXT_VTT)
        assertEquals(MimeTypes.VIDEO_MATROSKA, "video/x-matroska")
        assertEquals(MimeTypes.VIDEO_MP2T, "video/mp2t")
    }
}
