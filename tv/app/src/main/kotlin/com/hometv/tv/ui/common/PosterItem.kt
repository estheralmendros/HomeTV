package com.hometv.tv.ui.common

import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.api.Summary
import com.hometv.tv.core.util.Format
import com.hometv.tv.ui.home.HomeViewModel

/** A poster card of a grid or a search row: poster, title, year, watched check / progress (films) or unwatched badge (shows). */
data class PosterItem(
    val key: String,
    val summary: Summary,
    val title: String,
    val year: String?,
    val image: String?,
    val progress: Float?,
    val watched: Boolean,
    val badge: String?,
) {
    companion object {
        fun of(s: Summary): PosterItem? = when (s) {
            is FilmSummary -> PosterItem(s.key, s, s.title, s.year?.toString(), s.poster?.small, HomeViewModel.progressOf(s.progress), s.progress?.watched == true, null)
            is ShowSummary -> PosterItem(s.key, s, s.title, s.year?.toString(), s.poster?.small, null, false, s.unwatched_episode_count.takeIf { it > 0 }?.let(Format::count))
            else -> null
        }
    }
}
