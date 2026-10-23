package com.hometv.tv.core.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The prd.md endpoints the TV uses (prd3 Technical Requirements "API surface used"). Paths are
 * relative to the server URL (Retrofit base URL with a trailing slash). Stream, subtitle, HLS
 * media and artwork URLs are loaded by Media3/Coil/`PlaybackRepository` directly, not here.
 * Non-2xx answers throw `retrofit2.HttpException`; callers go through [apiCall].
 */
interface HomeTvApi {
    @GET("api/health")
    suspend fun health(): Health

    @GET("api/info")
    suspend fun info(): ServerInfo

    @GET("api/profiles")
    suspend fun profiles(): ProfileList

    /** No request body (the server ignores any body; no PIN on the TV). */
    @POST("api/profiles/{id}/session")
    suspend fun createSession(@Path("id") profileId: Long): SessionResponse

    @GET("api/me")
    suspend fun me(): Profile

    @GET("api/home")
    suspend fun home(@Query("limit") limit: Int): HomeResponse

    @GET("api/films")
    suspend fun films(
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int,
        @Query("sort") sort: String,
        @Query("order") order: String,
    ): Page<FilmSummary>

    @GET("api/films/{id}")
    suspend fun film(@Path("id") id: Long): FilmDetail

    @GET("api/shows")
    suspend fun shows(
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int,
        @Query("sort") sort: String,
        @Query("order") order: String,
    ): Page<ShowSummary>

    @GET("api/shows/{id}")
    suspend fun show(@Path("id") id: Long): ShowDetail

    @GET("api/shows/{id}/seasons/{n}")
    suspend fun season(@Path("id") showId: Long, @Path("n") seasonNumber: Int): SeasonDetail

    @GET("api/episodes/{id}")
    suspend fun episode(@Path("id") id: Long): EpisodeDetail

    @GET("api/search")
    suspend fun search(@Query("q") query: String, @Query("limit") limit: Int): SearchResponse

    @PUT("api/progress")
    suspend fun putProgress(@Body body: ProgressRequest): Progress

    @POST("api/watched")
    suspend fun setWatched(@Body body: WatchedRequest): WatchedResponse

    @POST("api/media-files/{id}/playback")
    suspend fun playback(@Path("id") mediaFileId: Long, @Body body: PlaybackRequest): PlaybackResponse

    @POST("api/hls/{sid}/keepalive")
    suspend fun keepAlive(@Path("sid") sessionId: String): Response<Unit>

    @DELETE("api/hls/{sid}")
    suspend fun deleteHlsSession(@Path("sid") sessionId: String): Response<Unit>
}
