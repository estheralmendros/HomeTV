@file:Suppress("PropertyName") // DTO properties mirror the prd.md JSON field names (snake_case).

package com.hometv.tv.core.api

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// DTOs mirroring prd.md "REST API Reference" shared shapes (Rev 9). Decoding is lenient
// (`ApiJson`: unknown keys ignored, missing nullable fields → null), so additive server changes
// never break the app. Ids are Long, positions/durations Double (seconds).

@Serializable
data class Image(val small: String? = null, val large: String? = null)

@Serializable
data class Progress(
    val position_seconds: Double = 0.0,
    val duration_seconds: Double? = null,
    val watched: Boolean = false,
    val watched_at: String? = null,
    val media_file_id: Long? = null,
    val updated_at: String? = null,
)

@Serializable
data class Profile(
    val id: Long,
    val name: String,
    val avatar_id: String,
    val is_admin: Boolean = false,
    val created_at: String? = null,
)

@Serializable
data class ProfileList(val items: List<Profile>)

/** `POST /api/admin/unlock` (prd.md §8): the TV only uses it to check an admin PIN (prd3 §11 step 3). */
@Serializable
data class UnlockRequest(val pin: String)

@Serializable
data class UnlockResponse(val admin_token: String, val expires_at: String)

/** `GET /api/genres` (prd.md): the library's genres, each with its item `count`. */
@Serializable
data class GenreList(val items: List<Genre> = emptyList())

@Serializable
data class SessionResponse(val profile: Profile, val token: String, val expires_at: String)

@Serializable
data class Avatar(val id: String, val color: String)

@Serializable
data class ServerInfo(
    val name: String,
    val version: String? = null,
    val needs_bootstrap: Boolean = false,
    val tmdb_configured: Boolean = false,
    val tmdb_attribution: String? = null,
    val avatars: List<Avatar> = emptyList(),
)

@Serializable
data class Health(val status: String)

// ---- Media files ---------------------------------------------------------------------------

@Serializable
data class VideoInfo(
    val codec: String,
    val width: Int? = null,
    val height: Int? = null,
    val hdr: String? = null,
    val frame_rate: Double? = null,
)

@Serializable
data class AudioTrack(
    val index: Int,
    val codec: String,
    val channels: Int? = null,
    val channel_layout: String? = null,
    val language: String? = null,
    val title: String? = null,
    val default: Boolean = false,
)

@Serializable
data class SubtitleTrack(
    val id: String,
    val source: String,
    val kind: String,
    val codec: String? = null,
    val language: String? = null,
    val title: String? = null,
    val forced: Boolean = false,
    val hearing_impaired: Boolean = false,
    val default: Boolean = false,
    val url: String? = null,
) {
    val isText: Boolean get() = kind == "text"
    val isImage: Boolean get() = kind == "image"
    val isExternal: Boolean get() = source == "external"
}

/** A "version" of a film or an episode. */
@Serializable
data class MediaFile(
    val id: Long,
    val file_name: String,
    val size_bytes: Long = 0,
    val container: String? = null,
    val duration_seconds: Double? = null,
    val label: String = "",
    val video: VideoInfo? = null,
    val audio_tracks: List<AudioTrack> = emptyList(),
    val subtitle_tracks: List<SubtitleTrack> = emptyList(),
    val part_number: Int? = null,
    val probe_error: String? = null,
    val added_at: String? = null,
)

// ---- Catalog summaries (polymorphic on `type`) ----------------------------------------------

/** A card item: `FilmSummary`, `ShowSummary` or `EpisodeSummary`/`HomeEpisode`, decoded by `type`. */
@Serializable(with = SummarySerializer::class)
sealed interface Summary {
    val type: String
    val id: Long

    /** "type:id", the focus/refresh key (prd3 §3.6). */
    val key: String get() = "$type:$id"
}

@Serializable
data class FilmSummary(
    override val id: Long,
    val title: String,
    val year: Int? = null,
    val poster: Image? = null,
    val backdrop: Image? = null,
    val overview: String? = null,
    val match_status: String? = null,
    val added_at: String? = null,
    val progress: Progress? = null,
    /** This profile's rating (ratings prd.md FR-6), null when unrated or from an older server. */
    val my_rating: Double? = null,
    override val type: String = "film",
) : Summary

@Serializable
data class ShowSummary(
    override val id: Long,
    val title: String,
    val year: Int? = null,
    val poster: Image? = null,
    val backdrop: Image? = null,
    val overview: String? = null,
    val match_status: String? = null,
    val added_at: String? = null,
    val season_count: Int = 0,
    val episode_count: Int = 0,
    val unwatched_episode_count: Int = 0,
    /** This profile's rating (ratings prd.md FR-6), null when unrated or from an older server. */
    val my_rating: Double? = null,
    override val type: String = "show",
) : Summary

/** `EpisodeSummary`; on `/api/home` (`HomeEpisode`) it also carries `media_files`. */
@Serializable
data class EpisodeSummary(
    override val id: Long,
    val show_id: Long,
    val show_title: String,
    val season_id: Long? = null,
    val season_number: Int,
    val episode_number: Int,
    val title: String? = null,
    val overview: String? = null,
    val still: Image? = null,
    val air_date: String? = null,
    val runtime_minutes: Int? = null,
    val duration_seconds: Double? = null,
    val show_poster: Image? = null,
    val show_backdrop: Image? = null,
    val progress: Progress? = null,
    val media_files: List<MediaFile> = emptyList(),
    override val type: String = "episode",
) : Summary

/** A summary whose `type` this app does not know (a future server addition); callers drop it. */
@Serializable
data class UnknownSummary(override val id: Long = 0, override val type: String = "unknown") : Summary

object SummarySerializer : JsonContentPolymorphicSerializer<Summary>(Summary::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Summary> =
        when ((element as? JsonObject)?.get("type")?.jsonPrimitive?.content) {
            "film" -> FilmSummary.serializer()
            "show" -> ShowSummary.serializer()
            "episode" -> EpisodeSummary.serializer()
            else -> UnknownSummary.serializer()
        }
}

@Serializable
data class LibraryCounts(val films: Long = 0, val shows: Long = 0, val empty: Boolean = false)

@Serializable
data class HomeResponse(
    val continue_watching: List<Summary> = emptyList(),
    val recently_added: List<Summary> = emptyList(),
    val library: LibraryCounts = LibraryCounts(),
)

@Serializable
data class Page<T>(val items: List<T>, val page: Int, val page_size: Int, val total: Int)

/** A TMDB genre; `count` is only sent by `GET /api/genres`. */
@Serializable
data class Genre(val id: Long, val name: String, val count: Int = 0)

@Serializable
data class CastMember(
    val person_id: Long,
    val name: String,
    val character: String? = null,
    val profile_image: Image? = null,
)

@Serializable
data class FilmDetail(
    val id: Long,
    val title: String,
    val original_title: String? = null,
    val year: Int? = null,
    val release_date: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val runtime_minutes: Int? = null,
    val vote_average: Double? = null,
    val genres: List<Genre> = emptyList(),
    val poster: Image? = null,
    val backdrop: Image? = null,
    val match_status: String? = null,
    val tmdb_id: Long? = null,
    val cast: List<CastMember> = emptyList(),
    val media_files: List<MediaFile> = emptyList(),
    val progress: Progress? = null,
    val added_at: String? = null,
    /** In this profile's "Mi lista" (watchlist prd.md FR-4); false when an older server omits it. */
    val in_watchlist: Boolean = false,
    /** Ratings (ratings prd.md FR-6): this profile's value, the others newest first, the average from 2 ratings. */
    val my_rating: Double? = null,
    val ratings: List<OtherRating> = emptyList(),
    val household_average: HouseholdAverage? = null,
    val type: String = "film",
)

@Serializable
data class SeasonSummary(
    val id: Long,
    val season_number: Int,
    val name: String? = null,
    val poster: Image? = null,
    val air_date: String? = null,
    val episode_count: Int = 0,
    val unwatched_episode_count: Int = 0,
)

@Serializable
data class ShowDetail(
    val id: Long,
    val title: String,
    val original_title: String? = null,
    val year: Int? = null,
    val first_air_date: String? = null,
    val status: String? = null,
    val overview: String? = null,
    val vote_average: Double? = null,
    val genres: List<Genre> = emptyList(),
    val poster: Image? = null,
    val backdrop: Image? = null,
    val match_status: String? = null,
    val tmdb_id: Long? = null,
    val cast: List<CastMember> = emptyList(),
    val seasons: List<SeasonSummary> = emptyList(),
    val next_episode: EpisodeSummary? = null,
    val unwatched_episode_count: Int = 0,
    val added_at: String? = null,
    /** In this profile's "Mi lista" (watchlist prd.md FR-4); false when an older server omits it. */
    val in_watchlist: Boolean = false,
    /** Ratings (ratings prd.md FR-6): this profile's value, the others newest first, the average from 2 ratings. */
    val my_rating: Double? = null,
    val ratings: List<OtherRating> = emptyList(),
    val household_average: HouseholdAverage? = null,
    val type: String = "show",
)

@Serializable
data class SeasonDetail(
    val id: Long,
    val show_id: Long,
    val show_title: String,
    val season_number: Int,
    val name: String? = null,
    val overview: String? = null,
    val poster: Image? = null,
    val air_date: String? = null,
    val episodes: List<EpisodeSummary> = emptyList(),
)

@Serializable
data class EpisodeDetail(
    val id: Long,
    val show_id: Long,
    val show_title: String,
    val season_id: Long? = null,
    val season_number: Int,
    val episode_number: Int,
    val title: String? = null,
    val overview: String? = null,
    val still: Image? = null,
    val air_date: String? = null,
    val runtime_minutes: Int? = null,
    val duration_seconds: Double? = null,
    val show_poster: Image? = null,
    val show_backdrop: Image? = null,
    val progress: Progress? = null,
    val media_files: List<MediaFile> = emptyList(),
    val previous_episode_id: Long? = null,
    val next_episode_id: Long? = null,
    val type: String = "episode",
)

/** One title of an actor's "En tu catálogo" row (`GET /api/people/{id}`): `kind` = `film` | `show`. */
@Serializable
data class PersonAppearance(
    val kind: String,
    /** Polymorphic on `type` ([SummarySerializer]); `CatalogRepository.person` drops other types. */
    val item: Summary,
    val character: String? = null,
)

/** `GET /api/people/{id}` (cast-appearances prd.md): dates are `YYYY-MM-DD`; `credits_complete` false = TMDB not consulted. */
@Serializable
data class PersonDetail(
    val person_id: Long,
    val name: String,
    val profile_image: Image? = null,
    val biography: String? = null,
    val biography_language: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    val place_of_birth: String? = null,
    val credits_complete: Boolean = false,
    val appearances: List<PersonAppearance> = emptyList(),
)

/** One title of "Mi lista" (`GET /api/watchlist`, watchlist prd.md FR-7): `kind` = `film` | `show`. */
@Serializable
data class WatchlistEntry(
    val kind: String,
    /** Polymorphic on `type` ([SummarySerializer]); `WatchlistRepository.list` drops other types. */
    val item: Summary,
    val added_to_list_at: String,
)

@Serializable
data class SearchResponse(val films: List<FilmSummary> = emptyList(), val shows: List<ShowSummary> = emptyList())

// ---- Watch state ---------------------------------------------------------------------------

@Serializable
data class ProgressRequest(
    val item_type: String,
    val item_id: Long,
    val media_file_id: Long,
    val position_seconds: Double,
    /** Omitted while unknown (`ApiJson` does not encode nulls). */
    val duration_seconds: Double? = null,
)

@Serializable
data class WatchedRequest(val target: String, val id: Long, val watched: Boolean)

@Serializable
data class WatchedResponse(val updated: Int = 0, val rating_prompt: RatingPrompt? = null)

/** `PUT /api/progress` answer: the stored [Progress] plus the rating prompt (ratings prd.md FR-8). */
@Serializable
data class ProgressReport(
    val position_seconds: Double = 0.0,
    val duration_seconds: Double? = null,
    val watched: Boolean = false,
    val watched_at: String? = null,
    val media_file_id: Long? = null,
    val updated_at: String? = null,
    val rating_prompt: RatingPrompt? = null,
) {
    fun toProgress(): Progress = Progress(position_seconds, duration_seconds, watched, watched_at, media_file_id, updated_at)
}

// ---- Ratings (ratings prd.md) --------------------------------------------------------------

/** Another profile, as shown next to its rating. */
@Serializable
data class ProfileRef(val id: Long, val name: String, val avatar_id: String)

/** "¿Qué te ha parecido?": the film or show (`kind` = `film` | `show`) a request just finished. */
@Serializable
data class RatingPrompt(val kind: String, val id: Long, val title: String, val poster: Image? = null)

/** `PUT /api/ratings/{segment}/{id}`: ½..5 in ½ steps. */
@Serializable
data class RatingRequest(val stars: Double)

/** Another profile's rating on a detail (`ratings`, newest first). */
@Serializable
data class OtherRating(val profile: ProfileRef, val stars: Double, val rated_at: String? = null)

/** Every profile's average (one decimal, half up), sent from two ratings. */
@Serializable
data class HouseholdAverage(val stars: Double, val count: Int)

/** One rating of the household (`GET /api/activity`, newest first): `kind` = `film` | `show`. */
@Serializable
data class ActivityEntry(
    val profile: ProfileRef,
    val kind: String,
    /** Polymorphic on `type` ([SummarySerializer]), the requester's summary; `RatingsRepository.activity` drops other types. */
    val item: Summary,
    val stars: Double,
    val rated_at: String? = null,
)

// ---- Playback ------------------------------------------------------------------------------

/** prd.md §7 Client capabilities (ffprobe codec names, normalized container names). */
@Serializable
data class ClientCaps(
    val containers: List<String>,
    val video_codecs: List<String>,
    val audio_codecs: List<String>,
    val max_audio_channels: Int,
    val hls: Boolean,
    val can_select_audio_track: Boolean,
)

/** `POST /api/media-files/{id}/playback`. Null fields are omitted on the wire (the server's defaults are null). */
@Serializable
data class PlaybackRequest(
    val client_caps: ClientCaps,
    val audio_stream_index: Int? = null,
    val start_position_seconds: Double = 0.0,
    val previous_session_id: String? = null,
)

/** The 200 answer of a playback request: `mode` = `direct` | `hls` | `unsupported`. */
@Serializable
data class PlaybackResponse(
    val mode: String,
    val url: String? = null,
    val session_id: String? = null,
    val audio_stream_index: Int? = null,
    val audio_action: String? = null,
    val duration_seconds: Double? = null,
    val subtitle_tracks: List<SubtitleTrack> = emptyList(),
    val reason: String? = null,
    val detail: String? = null,
)

/** prd.md error envelope `{"error": {"code", "message", "details"}}`. */
@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String? = null, val message: String? = null, val details: JsonObject? = null)

/** A field of `error.details` as text (numbers included), or null. */
fun JsonObject?.stringField(name: String): String? = this?.get(name)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
