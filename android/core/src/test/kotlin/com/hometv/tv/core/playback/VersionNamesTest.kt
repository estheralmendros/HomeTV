package com.hometv.tv.core.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VersionNamesTest {
    private fun name(fileName: String) = VersionNames.name(F.file(1, fileName = fileName))

    @Test
    fun `is the file name without its extension`() {
        assertEquals("Dune (2021) 4K", name("Dune (2021) 4K.mkv"))
        assertEquals("Dune.Part.Two.2024.2160p.HEVC", name("Dune.Part.Two.2024.2160p.HEVC.mp4"))
        assertEquals("Dune.tar", name("Dune.tar.mkv"))
    }

    @Test
    fun `keeps a name with no extension, or whose only dot leads or ends it, as is`() {
        assertEquals("Dune", name("Dune"))
        assertEquals(".mkv", name(".mkv"))
        assertEquals("Dune.", name("Dune."))
    }

    @Test
    fun `is null for a blank name (the app shows Desconocido)`() {
        assertNull(name(""))
        assertNull(name("  "))
    }
}
