package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiJson
import com.hometv.tv.core.api.EpisodeDetail
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.Fixtures
import com.hometv.tv.core.api.Health
import com.hometv.tv.core.api.HomeResponse
import com.hometv.tv.core.api.HomeTvApi
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.api.PlaybackRequest
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.api.ProfileList
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.ProgressRequest
import com.hometv.tv.core.api.SearchResponse
import com.hometv.tv.core.api.SeasonDetail
import com.hometv.tv.core.api.ServerInfo
import com.hometv.tv.core.api.SessionResponse
import com.hometv.tv.core.api.ShowDetail
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.api.WatchedRequest
import com.hometv.tv.core.api.WatchedResponse
import com.hometv.tv.core.api.WatchlistEntry
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** A HomeTvApi for virtual-time tests: override what a test needs; everything else fails loudly. */
open class FakeHomeTvApi : HomeTvApi {
    override suspend fun health(): Health = unused()
    override suspend fun info(): ServerInfo = unused()
    override suspend fun profiles(): ProfileList = unused()
    override suspend fun createSession(profileId: Long): SessionResponse = unused()
    override suspend fun me(): Profile = unused()
    override suspend fun unlock(body: com.hometv.tv.core.api.UnlockRequest): com.hometv.tv.core.api.UnlockResponse = unused()
    override suspend fun home(limit: Int): HomeResponse = unused()
    override suspend fun films(page: Int, pageSize: Int, sort: String, order: String, genre: Long?, yearFrom: Int?, yearTo: Int?, watched: Boolean?): Page<FilmSummary> = unused()
    override suspend fun film(id: Long): FilmDetail = unused()
    override suspend fun person(id: Long): com.hometv.tv.core.api.PersonDetail = unused()
    override suspend fun shows(page: Int, pageSize: Int, sort: String, order: String, genre: Long?, yearFrom: Int?, yearTo: Int?, watched: Boolean?): Page<ShowSummary> = unused()
    override suspend fun genres(type: String): com.hometv.tv.core.api.GenreList = unused()
    override suspend fun show(id: Long): ShowDetail = unused()
    override suspend fun season(showId: Long, seasonNumber: Int): SeasonDetail = unused()
    override suspend fun episode(id: Long): EpisodeDetail = unused()
    override suspend fun search(query: String, limit: Int): SearchResponse = unused()
    override suspend fun putProgress(body: ProgressRequest): Progress = unused()
    override suspend fun setWatched(body: WatchedRequest): WatchedResponse = unused()
    override suspend fun watchlist(type: String, sort: String, page: Int, pageSize: Int): Page<WatchlistEntry> = unused()
    override suspend fun addToWatchlist(segment: String, id: Long): Unit = unused()
    override suspend fun removeFromWatchlist(segment: String, id: Long): Unit = unused()
    override suspend fun playback(mediaFileId: Long, body: PlaybackRequest): PlaybackResponse = unused()
    override suspend fun keepAlive(sessionId: String): Response<Unit> = unused()
    override suspend fun deleteHlsSession(sessionId: String): Response<Unit> = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by this test")

    companion object {
        /** The exception Retrofit throws for a non-2xx answer with the prd.md error envelope. */
        fun httpError(status: Int, code: String, details: String = "{}", retryAfter: String? = null): HttpException {
            val headers = if (retryAfter == null) headersOf() else headersOf("Retry-After", retryAfter)
            val raw = okhttp3.Response.Builder()
                .code(status).message("error").protocol(Protocol.HTTP_1_1).headers(headers)
                .request(Request.Builder().url("https://hometv.ts.net/api/x").build())
                .build()
            val body = Fixtures.error(code, details).toResponseBody("application/json".toMediaType())
            return HttpException(Response.error<Any>(body, raw))
        }

        fun empty(status: Int): Response<Unit> =
            if (status in 200..299) Response.success(status, Unit) else Response.error(status, "".toResponseBody(null))

        fun session(token: String, id: Long = 5) =
            ApiJson.decodeFromString(SessionResponse.serializer(), Fixtures.let { """{"profile": {"id": $id, "name": "E", "avatar_id": "avatar-01"}, "token": "$token", "expires_at": "2026-12-20T00:00:00Z"}""" })
    }
}
