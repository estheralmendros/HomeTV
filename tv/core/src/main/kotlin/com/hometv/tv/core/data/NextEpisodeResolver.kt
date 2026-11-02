package com.hometv.tv.core.data

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.EpisodeDetail

sealed interface NextEpisodeResult {
    data class Found(val episode: EpisodeDetail) : NextEpisodeResult

    /** No candidate left (null `next_episode_id`, or the hop cap): the current item is the last one. */
    data object None : NextEpisodeResult

    /** A hop failed (network, 404…): counts as a failed prefetch. */
    data class Failed(val cause: ApiException) : NextEpisodeResult
}

/**
 * Next episode via `next_episode_id`, skipping candidates whose `media_files` contain the file that
 * is playing (multi-episode files), one `GET /api/episodes/{id}` per hop, at most
 * [maxHops] requests. `fetch` is normally `catalog.episode(id, Origin.Background)` (the prefetch is a
 * background caller).
 */
class NextEpisodeResolver(
    private val fetch: suspend (episodeId: Long) -> EpisodeDetail,
    private val maxHops: Int = MAX_HOPS,
) {
    suspend fun resolve(nextEpisodeId: Long?, playingFileId: Long): NextEpisodeResult {
        var candidate = nextEpisodeId
        var hops = 0
        while (candidate != null && hops < maxHops) {
            val episode = try {
                fetch(candidate)
            } catch (e: ApiException) {
                return NextEpisodeResult.Failed(e)
            }
            hops++
            if (episode.media_files.none { it.id == playingFileId }) return NextEpisodeResult.Found(episode)
            candidate = episode.next_episode_id
        }
        return NextEpisodeResult.None
    }

    companion object {
        const val MAX_HOPS = 10
    }
}
