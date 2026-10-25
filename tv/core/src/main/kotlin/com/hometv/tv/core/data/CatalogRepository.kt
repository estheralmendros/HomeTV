package com.hometv.tv.core.data

import com.hometv.tv.core.api.EpisodeDetail
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.Genre
import com.hometv.tv.core.api.HomeResponse
import com.hometv.tv.core.api.HomeTvApi
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.api.SearchResponse
import com.hometv.tv.core.api.SeasonDetail
import com.hometv.tv.core.api.ShowDetail
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.api.UnknownSummary
import com.hometv.tv.core.api.apiCall

/** The five Browse sort options (prd3 §4.2), in the order the sort button cycles through them. */
enum class BrowseSort(val sort: String, val order: String) {
    TITLE_ASC("title", "asc"),
    TITLE_DESC("title", "desc"),
    YEAR_DESC("year", "desc"),
    YEAR_ASC("year", "asc"),
    ADDED_DESC("added", "desc"),
}

/** Browse filters (prd3 §4 step 2): a TMDB genre id and an inclusive year range; null = not set. */
data class BrowseFilter(val genreId: Long? = null, val yearFrom: Int? = null, val yearTo: Int? = null, val watched: Boolean? = null)

/** Catalog reads (prd3 §3–§7). Every call renews the session silently on a 401 (§2.5). */
class CatalogRepository(
    private val api: () -> HomeTvApi,
    private val guard: SessionGuard,
) {
    private suspend fun <T> call(origin: Origin = Origin.Foreground, block: suspend HomeTvApi.() -> T): T =
        guard.withRenewal(origin) { apiCall { api().block() } }

    /** `GET /api/home?limit=20`; summaries of unknown `type`s are dropped. */
    suspend fun home(limit: Int = HOME_ROW_LIMIT): HomeResponse = call { home(limit) }.let { home ->
        home.copy(
            continue_watching = home.continue_watching.filterNot { it is UnknownSummary },
            recently_added = home.recently_added.filterNot { it is UnknownSummary },
        )
    }

    /** Home's Films row uses `films(1, 20, ADDED_DESC)`; Browse uses pages of 50. */
    suspend fun films(page: Int = 1, pageSize: Int = BROWSE_PAGE_SIZE, sort: BrowseSort = BrowseSort.TITLE_ASC, filter: BrowseFilter = BrowseFilter()): Page<FilmSummary> =
        call { films(page, pageSize, sort.sort, sort.order, filter.genreId, filter.yearFrom, filter.yearTo, filter.watched) }

    suspend fun shows(page: Int = 1, pageSize: Int = BROWSE_PAGE_SIZE, sort: BrowseSort = BrowseSort.TITLE_ASC, filter: BrowseFilter = BrowseFilter()): Page<ShowSummary> =
        call { shows(page, pageSize, sort.sort, sort.order, filter.genreId, filter.yearFrom, filter.yearTo, filter.watched) }

    /** `GET /api/genres?type=films|shows` (the Browse genre filter). */
    suspend fun genres(type: String): List<Genre> = call { genres(type) }.items

    suspend fun film(id: Long): FilmDetail = call { film(id) }

    suspend fun show(id: Long): ShowDetail = call { show(id) }

    suspend fun season(showId: Long, seasonNumber: Int): SeasonDetail = call { season(showId, seasonNumber) }

    /** [Origin.Background] for the next-episode prefetch. */
    suspend fun episode(id: Long, origin: Origin = Origin.Foreground): EpisodeDetail = call(origin) { episode(id) }

    /** Trimmed and truncated to 100 characters; a blank query answers empty without a request. */
    suspend fun search(query: String, limit: Int = SEARCH_LIMIT): SearchResponse {
        val q = query.trim().take(MAX_QUERY_LENGTH)
        if (q.isEmpty()) return SearchResponse()
        return call { search(q, limit) }
    }

    companion object {
        const val HOME_ROW_LIMIT = 20
        const val BROWSE_PAGE_SIZE = 50
        const val SEARCH_LIMIT = 20
        const val MAX_QUERY_LENGTH = 100
    }
}
