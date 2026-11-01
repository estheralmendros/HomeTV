package com.hometv.tv.core.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `a Mi lista page with a film and a show entry decodes`() {
        val json = """
            {"items": [
               {"kind": "film", "item": {"type": "film", "id": 12, "title": "Dune", "year": 2021, "progress": {"position_seconds": 10.0, "watched": false}}, "added_to_list_at": "2026-10-27T10:00:00Z", "extra": 1},
               {"kind": "show", "item": {"type": "show", "id": 7, "title": "The Office", "unwatched_episode_count": 150}, "added_to_list_at": "2026-10-26T10:00:00Z"}
             ], "page": 1, "page_size": 50, "total": 2}
        """.trimIndent()
        val page = ApiJson.decodeFromString(Page.serializer(WatchlistEntry.serializer()), json)
        assertEquals(2, page.total)
        assertEquals(listOf("film", "show"), page.items.map { it.kind })
        assertEquals(listOf("film:12", "show:7"), page.items.map { it.item.key })
        assertTrue(page.items[0].item is FilmSummary)
        assertEquals(150, (page.items[1].item as ShowSummary).unwatched_episode_count)
        assertEquals("2026-10-27T10:00:00Z", page.items[0].added_to_list_at)
    }

    @Test
    fun `in_watchlist decodes on film and show detail and defaults to false for older servers`() {
        assertTrue(ApiJson.decodeFromString(FilmDetail.serializer(), """{"id": 12, "title": "Dune", "in_watchlist": true}""").in_watchlist)
        assertTrue(ApiJson.decodeFromString(ShowDetail.serializer(), """{"id": 7, "title": "The Office", "in_watchlist": true}""").in_watchlist)
        assertEquals(false, ApiJson.decodeFromString(FilmDetail.serializer(), """{"id": 12, "title": "Dune"}""").in_watchlist)
        assertEquals(false, ApiJson.decodeFromString(ShowDetail.serializer(), """{"id": 7, "title": "The Office"}""").in_watchlist)
    }

    // ---- Ratings (ratings prd3 FR-T1) ------------------------------------------------------

    @Test
    fun `detail rating fields decode and default for older servers`() {
        val json = """
            {"id": 12, "title": "Dune", "my_rating": 4.5,
             "ratings": [{"profile": {"id": 2, "name": "Luis", "avatar_id": "avatar-02"}, "stars": 2.0, "rated_at": "2026-10-31T10:00:00Z"}],
             "household_average": {"stars": 3.3, "count": 2}}
        """.trimIndent()
        val film = ApiJson.decodeFromString(FilmDetail.serializer(), json)
        assertEquals(4.5, film.my_rating)
        assertEquals(listOf(OtherRating(ProfileRef(2, "Luis", "avatar-02"), 2.0, "2026-10-31T10:00:00Z")), film.ratings)
        assertEquals(HouseholdAverage(3.3, 2), film.household_average)
        val show = ApiJson.decodeFromString(ShowDetail.serializer(), json.replace("Dune", "The Office"))
        assertEquals(4.5, show.my_rating)
        assertEquals(1, show.ratings.size)

        val old = ApiJson.decodeFromString(FilmDetail.serializer(), """{"id": 12, "title": "Dune"}""")
        assertNull(old.my_rating)
        assertEquals(emptyList<OtherRating>(), old.ratings)
        assertNull(old.household_average)
        val nulls = ApiJson.decodeFromString(ShowDetail.serializer(), """{"id": 7, "title": "X", "my_rating": null, "household_average": null}""")
        assertNull(nulls.my_rating)
        assertNull(nulls.household_average)
    }

    @Test
    fun `summaries carry my_rating`() {
        assertEquals(3.5, ApiJson.decodeFromString(FilmSummary.serializer(), """{"id": 12, "title": "Dune", "my_rating": 3.5}""").my_rating)
        assertEquals(5.0, ApiJson.decodeFromString(ShowSummary.serializer(), """{"id": 7, "title": "O", "my_rating": 5}""").my_rating)
        assertNull(ApiJson.decodeFromString(FilmSummary.serializer(), """{"id": 12, "title": "Dune"}""").my_rating)
    }

    @Test
    fun `progress report and watched response carry the rating prompt`() {
        val prompt = """{"kind": "film", "id": 12, "title": "Dune", "poster": {"small": "/s.jpg", "large": "/l.jpg"}}"""
        val report = ApiJson.decodeFromString(ProgressReport.serializer(), """{"position_seconds": 9000.0, "duration_seconds": 9360.0, "watched": true, "media_file_id": 91, "rating_prompt": $prompt}""")
        assertEquals(RatingPrompt("film", 12, "Dune", Image("/s.jpg", "/l.jpg")), report.rating_prompt)
        assertEquals(Progress(9000.0, 9360.0, true, null, 91, null), report.toProgress())
        assertNull(ApiJson.decodeFromString(ProgressReport.serializer(), Fixtures.progressJson).rating_prompt)

        val watched = ApiJson.decodeFromString(WatchedResponse.serializer(), """{"updated": 3, "rating_prompt": $prompt}""")
        assertEquals(3, watched.updated)
        assertEquals(12L, watched.rating_prompt?.id)
        assertNull(ApiJson.decodeFromString(WatchedResponse.serializer(), """{"updated": 3}""").rating_prompt)
    }

    @Test
    fun `an activity page decodes its film and show entries`() {
        val json = """
            {"items": [
               {"profile": {"id": 2, "name": "Luis", "avatar_id": "avatar-02"}, "kind": "film",
                "item": {"type": "film", "id": 12, "title": "Dune", "my_rating": 4.0}, "stars": 2.0, "rated_at": "2026-10-31T10:00:00Z"},
               {"profile": {"id": 1, "name": "Ana", "avatar_id": "avatar-01"}, "kind": "show",
                "item": {"type": "show", "id": 7, "title": "The Office"}, "stars": 4.5, "rated_at": "2026-10-30T10:00:00Z", "extra": 1}
             ], "page": 1, "page_size": 50, "total": 2}
        """.trimIndent()
        val page = ApiJson.decodeFromString(Page.serializer(ActivityEntry.serializer()), json)
        assertEquals(listOf("film:12", "show:7"), page.items.map { it.item.key })
        assertEquals(listOf(2.0, 4.5), page.items.map { it.stars })
        assertEquals("Luis", page.items[0].profile.name)
        assertEquals(4.0, (page.items[0].item as FilmSummary).my_rating)
    }

    @Test
    fun `a rating request encodes its stars`() {
        assertEquals("""{"stars":4.5}""", ApiJson.encodeToString(RatingRequest.serializer(), RatingRequest(4.5)))
    }
}
