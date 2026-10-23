package com.hometv.tv.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

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
}
