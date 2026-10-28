package com.hometv.tv.testing

import com.hometv.tv.core.api.EpisodeDetail
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.HomeResponse
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.api.PlaybackRequest
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.ProgressRequest
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.api.ProfileList
import com.hometv.tv.core.api.SearchResponse
import com.hometv.tv.core.api.SeasonDetail
import com.hometv.tv.core.api.ServerInfo
import com.hometv.tv.core.api.SessionResponse
import com.hometv.tv.core.api.ShowDetail
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.api.WatchedRequest
import com.hometv.tv.core.api.WatchedResponse
import com.hometv.tv.core.data.FakeHomeTvApi
import java.util.Collections
import retrofit2.Response

/** The server URL of the test sessions. */
const val TEST_SERVER = "https://hometv.tail1234.ts.net"

/**
 * A HomeTvApi whose answers are lambdas (they may `delay` for virtual-time tests or throw
 * `FakeHomeTvApi.httpError(...)` / an IOException), with a log of the calls made ("GET /api/home").
 */
open class ScriptedApi : FakeHomeTvApi() {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    var onInfo: suspend () -> ServerInfo = { ServerInfo(name = "HomeTV", version = "1.0") }
    var onProfiles: suspend () -> ProfileList = { ProfileList(emptyList()) }
    var onCreateSession: suspend (Long) -> SessionResponse = { id -> session("tok-$id", id) }
    var onMe: suspend () -> Profile = { Profile(5, "Esther", "avatar-05") }
    /** `POST /api/admin/unlock`: the PIN check behind the server change (prd3 §11 step 3). */
    var onUnlock: suspend (pin: String) -> com.hometv.tv.core.api.UnlockResponse = { throw httpError(401, "invalid_pin") }
    var onHome: suspend (Int) -> HomeResponse = { HomeResponse() }
    var onFilms: suspend (page: Int, pageSize: Int, sort: String, order: String) -> Page<FilmSummary> = { p, s, _, _ -> Page(emptyList(), p, s, 0) }
    var onGenres: suspend (type: String) -> com.hometv.tv.core.api.GenreList = { com.hometv.tv.core.api.GenreList() }
    var onShows: suspend (page: Int, pageSize: Int, sort: String, order: String) -> Page<ShowSummary> = { p, s, _, _ -> Page(emptyList(), p, s, 0) }
    var onFilm: suspend (Long) -> FilmDetail = { throw httpError(404, "not_found") }
    /** `GET /api/people/{id}` (the Person screen, cast-appearances prd3). */
    var onPerson: suspend (Long) -> com.hometv.tv.core.api.PersonDetail = { throw httpError(404, "not_found") }
    var onShow: suspend (Long) -> ShowDetail = { throw httpError(404, "not_found") }
    var onSeason: suspend (Long, Int) -> SeasonDetail = { _, _ -> throw httpError(404, "not_found") }
    var onEpisode: suspend (Long) -> EpisodeDetail = { throw httpError(404, "not_found") }
    var onSearch: suspend (String, Int) -> SearchResponse = { _, _ -> SearchResponse() }
    var onWatched: suspend (WatchedRequest) -> WatchedResponse = { WatchedResponse(1) }
    val watchedBodies: MutableList<WatchedRequest> = Collections.synchronizedList(mutableListOf())

    // Playback: `POST .../playback`, `PUT /api/progress`, HLS keep-alive and DELETE.
    var onPlayback: suspend (Long, PlaybackRequest) -> PlaybackResponse = { _, _ -> throw httpError(404, "not_found") }
    val playbackBodies: MutableList<Pair<Long, PlaybackRequest>> = Collections.synchronizedList(mutableListOf())
    var onProgress: suspend (ProgressRequest) -> Progress = { Progress(it.position_seconds, it.duration_seconds, media_file_id = it.media_file_id) }
    val progressBodies: MutableList<ProgressRequest> = Collections.synchronizedList(mutableListOf())
    var onKeepAlive: suspend (String) -> Response<Unit> = { empty(200) }
    var onDeleteHls: suspend (String) -> Response<Unit> = { empty(204) }

    fun count(call: String) = calls.count { it == call }

    override suspend fun info(): ServerInfo = log("GET /api/info") { onInfo() }
    override suspend fun profiles(): ProfileList = log("GET /api/profiles") { onProfiles() }
    override suspend fun createSession(profileId: Long): SessionResponse = log("POST /api/profiles/$profileId/session") { onCreateSession(profileId) }
    override suspend fun me(): Profile = log("GET /api/me") { onMe() }
    override suspend fun unlock(body: com.hometv.tv.core.api.UnlockRequest): com.hometv.tv.core.api.UnlockResponse = log("POST /api/admin/unlock") { onUnlock(body.pin) }
    override suspend fun home(limit: Int): HomeResponse = log("GET /api/home?limit=$limit") { onHome(limit) }
    override suspend fun films(page: Int, pageSize: Int, sort: String, order: String, genre: Long?, yearFrom: Int?, yearTo: Int?, watched: Boolean?): Page<FilmSummary> =
        log("GET /api/films?page=$page&page_size=$pageSize&sort=$sort&order=$order${filters(genre, yearFrom, yearTo, watched)}") { onFilms(page, pageSize, sort, order) }
    override suspend fun shows(page: Int, pageSize: Int, sort: String, order: String, genre: Long?, yearFrom: Int?, yearTo: Int?, watched: Boolean?): Page<ShowSummary> =
        log("GET /api/shows?page=$page&page_size=$pageSize&sort=$sort&order=$order${filters(genre, yearFrom, yearTo, watched)}") { onShows(page, pageSize, sort, order) }
    override suspend fun genres(type: String): com.hometv.tv.core.api.GenreList = log("GET /api/genres?type=$type") { onGenres(type) }

    /** The optional list filters, in the order the server's query string uses them. */
    private fun filters(genre: Long?, yearFrom: Int?, yearTo: Int?, watched: Boolean?): String =
        listOfNotNull(genre?.let { "&genre=$it" }, yearFrom?.let { "&year_from=$it" }, yearTo?.let { "&year_to=$it" }, watched?.let { "&watched=$it" }).joinToString("")
    override suspend fun film(id: Long): FilmDetail = log("GET /api/films/$id") { onFilm(id) }
    override suspend fun person(id: Long): com.hometv.tv.core.api.PersonDetail = log("GET /api/people/$id") { onPerson(id) }
    override suspend fun show(id: Long): ShowDetail = log("GET /api/shows/$id") { onShow(id) }
    override suspend fun season(showId: Long, seasonNumber: Int): SeasonDetail = log("GET /api/shows/$showId/seasons/$seasonNumber") { onSeason(showId, seasonNumber) }
    override suspend fun episode(id: Long): EpisodeDetail = log("GET /api/episodes/$id") { onEpisode(id) }
    override suspend fun search(query: String, limit: Int): SearchResponse = log("GET /api/search?q=$query&limit=$limit") { onSearch(query, limit) }
    override suspend fun setWatched(body: WatchedRequest): WatchedResponse = log("POST /api/watched") {
        watchedBodies += body
        onWatched(body)
    }

    override suspend fun playback(mediaFileId: Long, body: PlaybackRequest): PlaybackResponse = log("POST /api/media-files/$mediaFileId/playback") {
        playbackBodies += mediaFileId to body
        onPlayback(mediaFileId, body)
    }
    override suspend fun putProgress(body: ProgressRequest): Progress = log("PUT /api/progress") {
        progressBodies += body
        onProgress(body)
    }
    override suspend fun keepAlive(sessionId: String): Response<Unit> = log("POST /api/hls/$sessionId/keepalive") { onKeepAlive(sessionId) }
    override suspend fun deleteHlsSession(sessionId: String): Response<Unit> = log("DELETE /api/hls/$sessionId") { onDeleteHls(sessionId) }

    private suspend fun <T> log(call: String, block: suspend () -> T): T {
        calls += call
        return block()
    }
}
