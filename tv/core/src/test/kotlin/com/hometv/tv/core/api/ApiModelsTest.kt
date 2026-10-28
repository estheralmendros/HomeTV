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

    @Test
    fun `a PersonDetail with a film, a show and an unknown item decodes, missing optional fields are null`() {
        val json = """
            {"person_id": 3, "name": "Timothée Chalamet", "profile_image": {"small": "/api/artwork/w185/p.jpg", "large": "/api/artwork/h632/p.jpg"},
             "biography": "Actor.", "biography_language": "es-ES", "birthday": "1995-12-27", "deathday": null,
             "place_of_birth": "Manhattan, New York City, New York, USA", "credits_complete": true, "extra": 1,
             "appearances": [
               {"kind": "film", "item": {"type": "film", "id": 12, "title": "Dune", "year": 2021}, "character": "Paul Atreides"},
               {"kind": "show", "item": {"type": "show", "id": 7, "title": "Homeland", "unwatched_episode_count": 90}, "character": "Finn Walden"},
               {"kind": "game", "item": {"type": "game", "id": 1}}
             ]}
        """.trimIndent()
        val person = ApiJson.decodeFromString(PersonDetail.serializer(), json)
        assertEquals("/api/artwork/h632/p.jpg", person.profile_image?.large)
        assertEquals("1995-12-27", person.birthday)
        assertEquals(null, person.deathday)
        assertTrue(person.credits_complete)
        assertEquals(listOf("film:12", "show:7", "game:1"), person.appearances.map { it.item.key })
        assertEquals(listOf("Paul Atreides", "Finn Walden", null), person.appearances.map { it.character })
        assertTrue(person.appearances[0].item is FilmSummary)
        assertTrue(person.appearances[1].item is ShowSummary)
        assertTrue(person.appearances[2].item is UnknownSummary)

        val bare = ApiJson.decodeFromString(PersonDetail.serializer(), """{"person_id": 4, "name": "Jane Doe"}""")
        assertEquals(PersonDetail(person_id = 4, name = "Jane Doe"), bare)
        assertEquals(null, bare.profile_image)
        assertEquals(null, bare.biography)
        assertEquals(false, bare.credits_complete)
        assertEquals(emptyList<PersonAppearance>(), bare.appearances)
    }
}
