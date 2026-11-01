package com.hometv.tv.core.util

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** JVM default locale en-US: the output must still be es-ES (prd3 §UI Language rule 5). */
@ExtendWith(UsLocale::class)
class FormatTest {
    @Test
    fun `player clock`() {
        assertEquals("1:02:03", Format.clock(3723.0))
        assertEquals("1:12:05", Format.clock(4325.9))
        assertEquals("12:31", Format.clock(751.0))
        assertEquals("0:05", Format.clock(5.0))
        assertEquals("0:00", Format.clock(-3.0))
        assertEquals("0:00", Format.clock(Double.NaN))
    }

    @Test
    fun `dates are es-ES medium dates`() {
        assertEquals("4 oct 2005", Format.date("2005-10-04"))
        assertEquals("22 oct 2026", Format.date("2026-10-22T10:00:00Z"))
        assertEquals("1 ene 2021", Format.date("2021-01-01"))
    }

    @Test
    fun `numbers group always and ratings use a decimal comma`() {
        assertEquals("1.234", Format.count(1234))
        assertEquals("1.012", Format.count(1012))
        assertEquals("1.234.567", Format.count(1_234_567))
        assertEquals("12", Format.count(12))
        assertEquals("7,8", Format.rating(7.8))
        assertEquals("8,0", Format.rating(8.0))
        assertEquals("6,5", Format.rating(6.46))
    }

    @Test
    fun `runtime is a structured value`() {
        assertEquals(Runtime(2, 35), Format.runtime(155))
        assertEquals(Runtime(0, 42), Format.runtime(42))
        assertEquals(Runtime(2, 0), Format.runtime(120))
        assertEquals(Runtime(0, 0), Format.runtime(-5))
    }

    @Test
    fun `episode code, season and episode titles are structured values`() {
        assertEquals(EpisodeCode(2, 4), Format.episodeCode(2, 4))
        assertEquals(SeasonTitle.Numbered(3), Format.seasonTitle(3, null))
        assertEquals(SeasonTitle.Numbered(3), Format.seasonTitle(3, "  "))
        assertEquals(SeasonTitle.Specials, Format.seasonTitle(0, null))
        assertEquals(SeasonTitle.Named("Temporada 2"), Format.seasonTitle(2, "Temporada 2"))
        assertEquals(SeasonTitle.Named("Especiales de Navidad"), Format.seasonTitle(0, "Especiales de Navidad"))
        assertEquals(EpisodeTitle.Numbered(3), Format.episodeTitle(3, null))
        assertEquals(EpisodeTitle.Named("Office Olympics"), Format.episodeTitle(3, "Office Olympics"))
    }

    @Test
    fun `channel labels (same table as the web)`() {
        assertEquals("1.0", Format.channels(1))
        assertEquals("2.0", Format.channels(2))
        assertEquals("2.1", Format.channels(3))
        assertEquals("5.1", Format.channels(6))
        assertEquals("7.1", Format.channels(8))
        assertEquals(null, Format.channels(null))
        assertEquals(null, Format.channels(0))
    }

    @Test
    fun `long dates are es-ES (CLDR) whatever the JVM locale`() {
        assertEquals("8 de octubre de 1992", Format.longDate(LocalDate.of(1992, 10, 8)))
        assertEquals("3 de mayo de 2010", Format.longDate(LocalDate.of(2010, 5, 3)))
        assertEquals("27 de diciembre de 1995", Format.longDate(LocalDate.of(1995, 12, 27)))
        assertEquals("1 de enero de 2000", Format.longDate(LocalDate.of(2000, 1, 1)))
    }

    /** The web's `ageOn` table (prd2 FR-W10), 29 February included; empty = null. */
    @ParameterizedTest(name = "{0} on {1} -> {2}")
    @CsvSource(
        "1995-12-27, 2026-10-27, 30",
        "1995-12-27, 2026-12-26, 30",
        "1995-12-27, 2026-12-27, 31",
        "1995-12-27, 2026-12-28, 31",
        "1932-10-08, 2010-05-03, 77",
        "2000-02-29, 2027-02-28, 26",
        "2000-02-29, 2027-03-01, 27",
        "2000-02-29, 2028-02-28, 27",
        "2000-02-29, 2028-02-29, 28",
        "2026-10-27, 2026-10-27, 0",
        "2026-10-28, 2026-10-27, ",
        "2010-05-03, 1932-10-08, ",
    )
    fun `age in completed years`(birth: String, on: String, age: Int?) {
        assertEquals(age, Format.age(LocalDate.parse(birth), LocalDate.parse(on)))
    }

    /** prd.md "Life span line" with today = 2026-10-27; the words come from `app` (`UiFormat`). */
    @Test
    fun `life span shapes`() {
        val today = LocalDate.of(2026, 10, 27)
        assertEquals(LifeSpan.Living("27 de diciembre de 1995", 30), Format.lifeSpan("1995-12-27", null, today))
        assertEquals(LifeSpan.Living("1 de octubre de 2025", 1), Format.lifeSpan("2025-10-01", null, today))
        assertEquals(
            LifeSpan.Deceased("8 de octubre de 1932", "3 de mayo de 2010", 77),
            Format.lifeSpan("1932-10-08", "2010-05-03", today),
        )
        assertEquals(LifeSpan.DeathOnly("3 de mayo de 2010"), Format.lifeSpan(null, "2010-05-03", today))
        assertEquals(null, Format.lifeSpan(null, null, today))
        // Death before birth: both dates, no age. A future birthday: the date alone.
        assertEquals(
            LifeSpan.Deceased("3 de mayo de 2010", "8 de octubre de 1932", null),
            Format.lifeSpan("2010-05-03", "1932-10-08", today),
        )
        assertEquals(LifeSpan.Living("1 de enero de 2027", null), Format.lifeSpan("2027-01-01", null, today))
    }

    @Test
    fun `invalid dates count as unknown`() {
        val today = LocalDate.of(2026, 10, 27)
        assertEquals(null, Format.lifeSpan("2021-02-30", null, today))
        assertEquals(null, Format.lifeSpan("2021-02-29", "", today))
        assertEquals(LifeSpan.DeathOnly("3 de mayo de 2010"), Format.lifeSpan("2020-13-45", "2010-05-03", today))
        assertEquals(LifeSpan.Living("27 de diciembre de 1995", 30), Format.lifeSpan("1995-12-27", "soon", today))
        assertEquals(null, Format.lifeSpan("1995-12-27T00:00:00", "27/12/1995", today))
        assertEquals(null, Format.lifeSpan("+11995-12-27", null, today))
    }

    /** ratings prd3 FR-T3: one profile's value, at most one decimal. */
    @Test
    fun `stars are es-ES with at most one decimal`() {
        assertEquals("4,5", Format.stars(Stars(9)))
        assertEquals("4", Format.stars(Stars(8)))
        assertEquals("0,5", Format.stars(Stars(1)))
        assertEquals("5", Format.stars(Stars(10)))
    }

    /** ratings prd3 FR-T11: the household average keeps one decimal ("4,0 (3)"), half up like the server. */
    @Test
    fun `household averages use the rating format`() {
        assertEquals("4,0", Format.rating(4.0))
        assertEquals("4,3", Format.rating(4.333333))
        assertEquals("3,5", Format.rating(3.5))
    }

    /** ratings prd3 FR-T17: the activity row's older dates, "12 oct" / "12 oct 2025" (no trailing dot). */
    @Test
    fun `short days drop the month dot and add the year only when asked`() {
        assertEquals("12 oct", Format.shortDay(LocalDate.of(2026, 10, 12), withYear = false))
        assertEquals("12 oct 2025", Format.shortDay(LocalDate.of(2025, 10, 12), withYear = true))
        assertEquals("1 ene", Format.shortDay(LocalDate.of(2026, 1, 1), withYear = false))
    }
}
