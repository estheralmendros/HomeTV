package com.hometv.tv.core.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TrackCheckTest {
    @ParameterizedTest(name = "video={0} audio={1} vSup={2} vSel={3} aSup={4} aSel={5} -> {6}")
    @CsvSource(
        // all fine
        "true,  true,  true,  true,  true,  true,  NONE",
        // video present but not selected / not supported
        "true,  true,  true,  false, true,  true,  VIDEO",
        "true,  true,  false, false, true,  true,  VIDEO",
        "true,  false, false, true,  false, false, VIDEO",
        // audio expected but unsupported / not selected
        "true,  true,  true,  true,  false, false, AUDIO",
        "true,  true,  true,  true,  true,  false, AUDIO",
        // no audio in the file: never an audio failure
        "true,  false, true,  true,  false, false, NONE",
        // audio-only file (no video stream): only audio is checked
        "false, true,  false, false, true,  true,  NONE",
        "false, true,  false, false, false, false, AUDIO",
        // both fail: the video failure wins (it leads to the unsupported screen)
        "true,  true,  false, false, false, false, VIDEO",
    )
    fun `silent track failures`(fileHasVideo: Boolean, expectedAudio: Boolean, vSup: Boolean, vSel: Boolean, aSup: Boolean, aSel: Boolean, expected: TrackFailure) {
        assertEquals(expected, TrackCheck.check(TrackCheckInput(fileHasVideo, expectedAudio, vSup, vSel, aSup, aSel)))
    }
}
