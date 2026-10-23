package com.hometv.tv.core.data

import com.hometv.tv.core.api.HomeTvApi
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.ProgressRequest
import com.hometv.tv.core.api.WatchedRequest
import com.hometv.tv.core.api.apiCall

enum class WatchTarget(val wire: String) { FILM("film"), EPISODE("episode"), SEASON("season"), SHOW("show") }

/** Watch state writes (prd3 §6–§8). */
class WatchRepository(
    private val api: () -> HomeTvApi,
    private val guard: SessionGuard,
) {
    /** A background caller by default: a renewal IOException drops the report (§2.5). */
    suspend fun putProgress(report: ProgressRequest, origin: Origin = Origin.Background): Progress =
        guard.withRenewal(origin) { apiCall { api().putProgress(report) } }

    /** `POST /api/watched`; returns the number of rows changed. */
    suspend fun setWatched(target: WatchTarget, id: Long, watched: Boolean, origin: Origin = Origin.Foreground): Int =
        guard.withRenewal(origin) { apiCall { api().setWatched(WatchedRequest(target.wire, id, watched)) } }.updated
}
