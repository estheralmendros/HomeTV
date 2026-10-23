package com.hometv.tv.core.util

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.floor

/** A runtime split for the `runtime_h_min` / `runtime_min` templates (`app` UiFormat). */
data class Runtime(val hours: Int, val minutes: Int)

/** "T2 · E4" in `app` (`episode_code`); never "S02E04" in the UI. */
data class EpisodeCode(val season: Int, val episode: Int)

/** Season title: the API `name`, else "Temporada N" (`season_numbered`) or "Especiales" (`season_specials`). */
sealed interface SeasonTitle {
    data class Named(val name: String) : SeasonTitle

    data class Numbered(val number: Int) : SeasonTitle

    data object Specials : SeasonTitle
}

/** Episode title: the API `title`, else "Episodio N" (`episode_numbered`). */
sealed interface EpisodeTitle {
    data class Named(val title: String) : EpisodeTitle

    data class Numbered(val number: Int) : EpisodeTitle
}

/**
 * es-ES formatting with one constant locale (prd3 §UI Language rule 5): never `Locale.getDefault()`,
 * so an English TV still shows "4 oct 2005", "1.234" and "7,8". Returns numbers and dates (data)
 * or structured values; no UI words. Formatters are created once (thread-safe ones are shared,
 * DecimalFormat instances are per thread).
 */
object Format {
    val LOCALE: Locale = Locale.forLanguageTag("es-ES")

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LOCALE)
    private val symbols = DecimalFormatSymbols.getInstance(LOCALE)
    private val countFormat = ThreadLocal.withInitial { DecimalFormat("#,##0", symbols).apply { isGroupingUsed = true } }
    private val ratingFormat = ThreadLocal.withInitial { DecimalFormat("0.0", symbols).apply { roundingMode = RoundingMode.HALF_UP } }
    private val dateOnly = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /** Player clock: "1:02:03" with hours, else "12:31"; negative / NaN → "0:00". */
    fun clock(seconds: Double): String {
        val total = if (seconds.isFinite() && seconds > 0) floor(seconds).toLong() else 0L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(LOCALE, h, m, s) else "%d:%02d".format(LOCALE, m, s)
    }

    /** "4 oct 2005". Date-only values are calendar dates; timestamps are shown in UTC. */
    fun date(iso: String): String {
        val date = if (dateOnly.matches(iso)) LocalDate.parse(iso) else Instant.parse(iso).atZone(ZoneOffset.UTC).toLocalDate()
        return dateFormatter.format(date).replace(".", "")
    }

    /** "1.234": grouping always on, also for 4 digits (the web's `useGrouping: 'always'`). */
    fun count(n: Long): String = countFormat.get().format(n)

    fun count(n: Int): String = count(n.toLong())

    /** "7,8" (one decimal, decimal comma). */
    fun rating(value: Double): String = ratingFormat.get().format(value)

    /** 155 → Runtime(2, 35); negative → Runtime(0, 0). */
    fun runtime(minutes: Int): Runtime {
        val total = minutes.coerceAtLeast(0)
        return Runtime(total / 60, total % 60)
    }

    fun episodeCode(season: Int, episode: Int): EpisodeCode = EpisodeCode(season, episode)

    /** The API name when present (TMDB names come in `tmdb.language`), else Numbered / Specials (season 0). */
    fun seasonTitle(seasonNumber: Int, name: String?): SeasonTitle = when {
        !name.isNullOrBlank() -> SeasonTitle.Named(name)
        seasonNumber == 0 -> SeasonTitle.Specials
        else -> SeasonTitle.Numbered(seasonNumber)
    }

    fun episodeTitle(episodeNumber: Int, title: String?): EpisodeTitle =
        if (title.isNullOrBlank()) EpisodeTitle.Numbered(episodeNumber) else EpisodeTitle.Named(title)

    /**
     * Channel label, same table as the web and the server `label`: 1 → "1.0", 2 → "2.0",
     * n > 2 → "(n-1).1" (6 → "5.1", 8 → "7.1"); null or < 1 → null (omitted). Technical token, not a word.
     */
    fun channels(channels: Int?): String? = when {
        channels == null || channels < 1 -> null
        channels <= 2 -> "$channels.0"
        else -> "${channels - 1}.1"
    }
}
