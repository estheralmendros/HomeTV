package com.hometv.tv.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** JVM default locale en-US: names must still be Spanish (prd3 §UI Language rule 5). */
@ExtendWith(UsLocale::class)
class LanguageNamesTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "eng, Inglés",
        "spa, Español",
        "fre, Francés",
        "fra, Francés",
        "ger, Alemán",
        "deu, Alemán",
        "chi, Chino",
        "baq, Euskera",
        "cat, Catalán",
        "jpn, Japonés",
        "cze, Checo",
        "ces, Checo",
        "gre, Griego",
        "en, Inglés",
        "es, Español",
        "'ENG ', Inglés",
        "pt-BR, Portugués",
        "es_419, Español",
    )
    fun `names codes in Spanish`(code: String, name: String) {
        assertEquals(LanguageName.Named(name), LanguageNames.name(code))
    }

    @Test
    fun `und, null and empty are unknown`() {
        assertEquals(LanguageName.Unknown, LanguageNames.name("und"))
        assertEquals(LanguageName.Unknown, LanguageNames.name(" UND "))
        assertEquals(LanguageName.Unknown, LanguageNames.name(null))
        assertEquals(LanguageName.Unknown, LanguageNames.name(""))
    }

    @Test
    fun `unrecognized codes are returned upper-cased`() {
        assertEquals(LanguageName.Code("XYZ"), LanguageNames.name("xyz"))
        assertEquals(LanguageName.Code("QQ"), LanguageNames.name("qq"))
        assertEquals(LanguageName.Code("KLINGON"), LanguageNames.name("klingon"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "eng", "spa", "fre", "fra", "ger", "deu", "ita", "por", "dut", "nld", "jpn", "kor", "chi", "zho", "rus", "ara",
            "cat", "baq", "eus", "glg", "swe", "nor", "dan", "fin", "pol",
            "cze", "ces", "gre", "ell", "hun", "tur", "heb", "hin",
        ],
    )
    fun `every table code (the 20 prd2 codes plus the TV extras) has a Spanish name`(code: String) {
        val name = LanguageNames.name(code)
        assertTrue(name is LanguageName.Named, "$code -> $name")
        val text = (name as LanguageName.Named).name
        assertTrue(text.first().isUpperCase(), text)
    }

    @Test
    fun `maps ISO 639-2 B and T codes to BCP 47`() {
        assertEquals("zh", LanguageNames.toBcp47("chi"))
        assertEquals("zh", LanguageNames.toBcp47("zho"))
        assertEquals("nl", LanguageNames.toBcp47("dut"))
        assertEquals("en", LanguageNames.toBcp47("ENG"))
        assertEquals("es", LanguageNames.toBcp47("es"))
        assertEquals(null, LanguageNames.toBcp47("xyz"))
        assertEquals(null, LanguageNames.toBcp47(null))
    }
}
