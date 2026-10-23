package com.hometv.tv.core.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiModelsTest {
    @Test
    fun `home payload round-trips through ApiJson (HomeCache writes it back)`() {
        val home = ApiJson.decodeFromString(HomeResponse.serializer(), Fixtures.home)
        val encoded = ApiJson.encodeToString(HomeResponse.serializer(), home)
        assertTrue(""""type":"episode"""" in encoded)
        assertEquals(home, ApiJson.decodeFromString(HomeResponse.serializer(), encoded))
    }

    @Test
    fun `nulls for non-null fields with defaults are coerced`() {
        val file = ApiJson.decodeFromString(MediaFile.serializer(), """{"id": 1, "file_name": "a.mkv", "label": null, "audio_tracks": null}""")
        assertEquals("", file.label)
        assertEquals(emptyList<AudioTrack>(), file.audio_tracks)
    }
}
