package com.hometv.tv.core.data

import com.hometv.tv.core.api.HomeTvApi
import com.hometv.tv.core.api.ProgressReport
import com.hometv.tv.core.api.ProgressRequest
import com.hometv.tv.core.api.RatingPrompt
import com.hometv.tv.core.api.WatchedRequest
import com.hometv.tv.core.api.apiCall

enum class WatchTarget(val wire: String) { FILM("film"), EPISODE("episode"), SEASON("season"), SHOW("show") }

/** `POST /api/watched`: rows changed and the rating prompt when the mark finished a title. */
data class WatchedResult(val updated: Int, val ratingPrompt: RatingPrompt?)

/** Watch state writes. */
class WatchRepository(
    private val api: () -> HomeTvApi,
    private val guard: SessionGuard,
) {
    /** A background caller by default: a renewal IOException drops the report. */
    suspend fun putProgress(report: ProgressRequest, origin: Origin = Origin.Background): ProgressReport =
        guard.withRenewal(origin) { apiCall { api().putProgress(report) } }

    suspend fun setWatched(target: WatchTarget, id: Long, watched: Boolean, origin: Origin = Origin.Foreground): WatchedResult =
        guard.withRenewal(origin) { apiCall { api().setWatched(WatchedRequest(target.wire, id, watched)) } }
            .let { WatchedResult(it.updated, it.rating_prompt) }
}
