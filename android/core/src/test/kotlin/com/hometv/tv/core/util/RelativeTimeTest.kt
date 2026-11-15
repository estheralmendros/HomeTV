package com.hometv.tv.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The activity row's relative time, in the device zone. */
class RelativeTimeTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    /** A local Madrid time (UTC+1 from 2026-10-25). */
    private fun at(local: String): Instant = java.time.LocalDateTime.parse(local).atZone(madrid).toInstant()

    private fun rel(at: String, now: String) = relativeTime(at(at), at(now), madrid)

    @Test
    fun `under a minute or in the future is just now, whatever the day`() {
        assertEquals(RelativeTime.JustNow, rel("2026-10-31T12:00:00", "2026-10-31T12:00:59"))
        assertEquals(RelativeTime.JustNow, rel("2026-10-31T12:00:00", "2026-10-31T12:00:00"))
        assertEquals(RelativeTime.JustNow, rel("2026-10-30T23:59:30", "2026-10-31T00:00:10"))
        assertEquals(RelativeTime.JustNow, rel("2026-10-31T12:02:00", "2026-10-31T12:00:00"))
        assertEquals(RelativeTime.JustNow, rel("2026-11-01T12:00:00", "2026-10-31T12:00:00"))
    }

    @Test
    fun `same day minutes then hours, floored`() {
        assertEquals(RelativeTime.Minutes(1), rel("2026-10-31T12:00:00", "2026-10-31T12:01:00"))
        assertEquals(RelativeTime.Minutes(5), rel("2026-10-31T12:00:00", "2026-10-31T12:05:59"))
        assertEquals(RelativeTime.Minutes(59), rel("2026-10-31T12:00:00", "2026-10-31T12:59:59"))
        assertEquals(RelativeTime.Hours(1), rel("2026-10-31T12:00:00", "2026-10-31T13:00:00"))
        assertEquals(RelativeTime.Hours(3), rel("2026-10-31T08:10:00", "2026-10-31T12:00:00"))
        assertEquals(RelativeTime.Hours(23), rel("2026-10-31T00:00:00", "2026-10-31T23:59:00"))
    }

    @Test
    fun `the previous calendar day is yesterday`() {
        assertEquals(RelativeTime.Yesterday, rel("2026-10-30T23:50:00", "2026-10-31T00:05:00"))
        assertEquals(RelativeTime.Yesterday, rel("2026-10-30T00:00:00", "2026-10-31T23:59:00"))
    }

    @Test
    fun `older is a day, with the year only when it differs`() {
        assertEquals(RelativeTime.Day(LocalDate.of(2026, 10, 12), withYear = false), rel("2026-10-12T10:00:00", "2026-10-31T12:00:00"))
        assertEquals(RelativeTime.Day(LocalDate.of(2025, 10, 12), withYear = true), rel("2025-10-12T10:00:00", "2026-10-31T12:00:00"))
        // Dec 31 → Jan 1 is yesterday; Dec 30 → Jan 1 is a day of the previous year.
        assertEquals(RelativeTime.Yesterday, rel("2025-12-31T22:00:00", "2026-01-01T09:00:00"))
        assertEquals(RelativeTime.Day(LocalDate.of(2025, 12, 30), withYear = true), rel("2025-12-30T22:00:00", "2026-01-01T09:00:00"))
    }

    @Test
    fun `the calendar day is the zone's, not UTC's`() {
        // 2026-10-30T23:30Z is 00:30 on Oct 31 in Madrid (UTC+1 after Oct 25): same day as noon there.
        assertEquals(RelativeTime.Hours(11), relativeTime(Instant.parse("2026-10-30T23:30:00Z"), at("2026-10-31T12:00:00"), madrid))
    }

    @Test
    fun `an API timestamp is parsed, an unparseable one is null`() {
        val now = Instant.parse("2026-10-31T12:00:00Z")
        assertEquals(RelativeTime.Minutes(5), relativeTime("2026-10-31T11:55:00Z", now, ZoneId.of("UTC")))
        assertNull(relativeTime("yesterday", now, ZoneId.of("UTC")))
        assertNull(relativeTime(null, now, ZoneId.of("UTC")))
    }
}
