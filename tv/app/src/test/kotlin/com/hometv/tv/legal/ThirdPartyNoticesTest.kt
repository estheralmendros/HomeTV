package com.hometv.tv.legal

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `res/raw/third_party_notices.txt` (Ajustes → Acerca de → "Licencias de código abierto"): the libVLC LGPL
 * notice names the exact pinned version, so a bump in `libs.versions.toml` without a notice update fails.
 */
class ThirdPartyNoticesTest {
    /** The working directory is the app module (Gradle) or the `tv/` root (some IDE runs). */
    private fun file(fromApp: String, fromRoot: String): File =
        listOf(File(fromApp), File(fromRoot)).first { it.isFile }

    private val notice = file("src/main/res/raw/third_party_notices.txt", "app/src/main/res/raw/third_party_notices.txt").readText()

    private val libVlcVersion: String =
        Regex("""^libvlc\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(file("../gradle/libs.versions.toml", "gradle/libs.versions.toml").readText())!!
            .groupValues[1]

    @Test
    fun `AC-14_1 the notice names libVLC, its pinned version, its source and the full LGPL v2_1`() {
        for (expected in listOf(
            "GNU LESSER GENERAL PUBLIC LICENSE",
            "Version 2.1",
            "VideoLAN",
            "https://code.videolan.org/videolan/libvlcjni",
            "https://code.videolan.org/videolan/vlc",
            "org.videolan.android:libvlc-all $libVlcVersion",
            // The last section of the LGPL text: the notice carries the whole licence.
            "END OF TERMS AND CONDITIONS",
            "How to Apply These Terms to Your New Libraries",
            "https://www.apache.org/licenses/LICENSE-2.0",
        )) {
            assertTrue(expected in notice, "missing: $expected")
        }
    }

    @Test
    fun `the notice never points at the GPL vlc-android application`() {
        assertFalse("vlc-android" in notice)
    }

    @Test
    fun `the notice stays small enough for the scrollable dialog`() {
        assertTrue(notice.toByteArray().size < 50_000, "${notice.toByteArray().size} bytes")
    }
}
