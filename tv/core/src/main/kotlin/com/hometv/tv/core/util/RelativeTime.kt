package com.hometv.tv.core.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** When an activity row was rated, relative to now; the words come from `app` (`UiFormat`). */
sealed interface RelativeTime {
    /** Less than a minute ago, or in the future (a device clock behind the server). */
    data object JustNow : RelativeTime

    data class Minutes(val n: Int) : RelativeTime

    data class Hours(val n: Int) : RelativeTime

    data object Yesterday : RelativeTime

    /** Older: "12 oct" ([withYear] false) or "12 oct 2025" ([Format.shortDay]). */
    data class Day(val date: LocalDate, val withYear: Boolean) : RelativeTime
}

/**
 * The rules, checked in order: under 60 s or in the future → just now (whatever the calendar
 * day); same calendar day in [zone] → minutes (< 60) else hours, floored; the previous day →
 * yesterday; older → the day, with the year only when it is not [now]'s.
 */
fun relativeTime(at: Instant, now: Instant, zone: ZoneId): RelativeTime {
    val elapsed = Duration.between(at, now)
    if (elapsed.isNegative || elapsed.seconds < 60) return RelativeTime.JustNow
    val day = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when (day) {
        today -> if (elapsed.toMinutes() < 60) RelativeTime.Minutes(elapsed.toMinutes().toInt()) else RelativeTime.Hours(elapsed.toHours().toInt())
        today.minusDays(1) -> RelativeTime.Yesterday
        else -> RelativeTime.Day(day, withYear = day.year != today.year)
    }
}

/** The same from an API timestamp (`rated_at`); null or unparseable → null (the row shows no time). */
fun relativeTime(iso: String?, now: Instant, zone: ZoneId): RelativeTime? {
    if (iso == null) return null
    val at = try {
        Instant.parse(iso)
    } catch (_: DateTimeParseException) {
        null
    }
    return at?.let { relativeTime(it, now, zone) }
}
