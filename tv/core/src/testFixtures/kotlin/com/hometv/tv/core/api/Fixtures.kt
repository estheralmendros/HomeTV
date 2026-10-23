package com.hometv.tv.core.api

/** JSON payloads in the prd.md shapes, with extra unknown fields to prove lenient decoding. */
object Fixtures {
    fun mediaFileJson(id: Long = 91, partNumber: Int? = null): String = """
        { "id": $id, "file_name": "Dune (2021).mkv", "size_bytes": 23456789012, "container": "mkv",
          "duration_seconds": 9360.5, "label": "2160p HEVC HDR10 · EAC3 5.1",
          "video": { "codec": "hevc", "width": 3840, "height": 2160, "hdr": "hdr10", "frame_rate": 23.976 },
          "audio_tracks": [ { "index": 1, "codec": "eac3", "channels": 6, "channel_layout": "5.1(side)",
                              "language": "eng", "title": "Surround", "default": true } ],
          "subtitle_tracks": [
            { "id": "emb-3", "source": "embedded", "kind": "text", "codec": "subrip", "language": "spa", "title": null,
              "forced": false, "hearing_impaired": false, "default": false, "url": "/api/media-files/$id/subtitles/emb-3.vtt" },
            { "id": "emb-4", "source": "embedded", "kind": "image", "codec": "hdmv_pgs_subtitle", "language": "eng",
              "title": null, "forced": false, "hearing_impaired": false, "default": false, "url": null } ],
          "part_number": ${partNumber ?: "null"}, "probe_error": null, "added_at": "2026-10-01T10:00:00Z",
          "some_future_field": {"x": 1} }
    """.trimIndent()

    val progressJson = """
        { "position_seconds": 1234.5, "duration_seconds": 9360.0, "watched": false, "watched_at": null,
          "media_file_id": 91, "updated_at": "2026-10-21T18:04:05Z" }
    """.trimIndent()

    val home = """
        { "continue_watching": [
            { "type": "film", "id": 12, "title": "Dune", "year": 2021,
              "poster": { "small": "/api/artwork/w342/p.jpg", "large": "/api/artwork/w780/p.jpg" },
              "backdrop": { "small": "/api/artwork/w780/b.jpg", "large": "/api/artwork/w1280/b.jpg" },
              "overview": "Paul Atreides...", "match_status": "matched", "added_at": "2026-10-01T10:00:00Z",
              "progress": $progressJson, "brand_new": true },
            { "type": "episode", "id": 345, "show_id": 7, "show_title": "The Office", "season_id": 70,
              "season_number": 2, "episode_number": 3, "title": "Office Olympics", "overview": "Michael...",
              "still": null, "air_date": "2005-10-04", "runtime_minutes": 22, "duration_seconds": 1320.4,
              "show_poster": null, "show_backdrop": { "small": "/api/artwork/w780/ob.jpg", "large": "/api/artwork/w1280/ob.jpg" },
              "progress": $progressJson, "media_files": [ ${mediaFileJson(91, partNumber = null)}, ${mediaFileJson(92, partNumber = 2)} ] } ],
          "next_up": [],
          "recently_added": [
            { "type": "show", "id": 7, "title": "The Office", "year": 2005, "poster": null, "backdrop": null,
              "overview": null, "match_status": "matched", "added_at": "2026-10-01T10:00:00Z",
              "season_count": 9, "episode_count": 201, "unwatched_episode_count": 150 },
            { "type": "podcast", "id": 99 } ],
          "library": { "films": 1234, "shows": 87, "empty": false } }
    """.trimIndent()

    fun error(code: String, details: String = "{}", message: String = "Some English diagnostic") =
        """{ "error": { "code": "$code", "message": "$message", "details": $details } }"""
}
