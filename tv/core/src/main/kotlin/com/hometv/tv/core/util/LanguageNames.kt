package com.hometv.tv.core.util

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A track language for display: `app` shows Named/Code as their text and Unknown as "Desconocido". */
sealed interface LanguageName {
    data class Named(val name: String) : LanguageName

    /** Unrecognized code, upper-cased as received (same fallback as the web). */
    data class Code(val code: String) : LanguageName

    data object Unknown : LanguageName
}

/**
 * ISO 639-2 (B and T) → BCP 47 → Spanish name from the platform (prd3 §UI Language rule 5, same
 * rule and the same table as the web's `lib/languages.ts`, so both clients name a code the same
 * way). Results are memoized per code.
 */
object LanguageNames {
    private val ISO_639_2_TO_BCP47: Map<String, String> = mapOf(
        "eng" to "en", "spa" to "es", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de", "ita" to "it",
        "por" to "pt", "dut" to "nl", "nld" to "nl", "jpn" to "ja", "kor" to "ko", "chi" to "zh", "zho" to "zh",
        "rus" to "ru", "ara" to "ar", "cat" to "ca", "baq" to "eu", "eus" to "eu", "glg" to "gl", "swe" to "sv",
        "nor" to "no", "nob" to "nb", "nno" to "nn", "dan" to "da", "fin" to "fi", "pol" to "pl", "tur" to "tr",
        "gre" to "el", "ell" to "el", "heb" to "he", "hin" to "hi", "hun" to "hu", "cze" to "cs", "ces" to "cs",
        "slo" to "sk", "slk" to "sk", "rum" to "ro", "ron" to "ro", "ukr" to "uk", "bul" to "bg", "hrv" to "hr",
        "srp" to "sr", "slv" to "sl", "tha" to "th", "vie" to "vi", "ind" to "id", "may" to "ms", "msa" to "ms",
        "per" to "fa", "fas" to "fa", "ice" to "is", "isl" to "is", "lat" to "la", "wel" to "cy", "cym" to "cy",
        "gle" to "ga", "est" to "et", "lav" to "lv", "lit" to "lt", "tam" to "ta", "tel" to "te", "ben" to "bn",
        "urd" to "ur", "fil" to "fil", "tgl" to "tl",
    )
    private val TWO_LETTERS = Regex("^[a-z]{2}$")
    private val cache = ConcurrentHashMap<String, LanguageName>()

    /**
     * (1) trim + lower-case; (2) null, "" or `und` → Unknown; a tag with a region (`pt-BR`,
     * `es_419`) is named by its primary subtag; (3) 639-2 table, a 2-letter code as is;
     * (4) Spanish platform name, first letter upper-cased; (5) no mapping or no name → Code.
     */
    fun name(code: String?): LanguageName {
        val key = code?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (key.isEmpty() || key == "und") return LanguageName.Unknown
        return cache.getOrPut(key) { compute(key) }
    }

    /** BCP 47 language for an ISO 639-2 (or 2-letter) code; null when unknown. */
    fun toBcp47(code: String?): String? {
        val key = primary(code?.trim()?.lowercase(Locale.ROOT).orEmpty())
        return ISO_639_2_TO_BCP47[key] ?: key.takeIf { TWO_LETTERS.matches(it) }
    }

    private fun compute(key: String): LanguageName {
        val primary = primary(key)
        if (primary == "und") return LanguageName.Unknown
        val tag = toBcp47(primary) ?: return LanguageName.Code(key.uppercase(Locale.ROOT))
        val display = Locale.forLanguageTag(tag).getDisplayLanguage(Format.LOCALE)
        if (display.isNullOrEmpty() || display.equals(tag, ignoreCase = true)) return LanguageName.Code(key.uppercase(Locale.ROOT))
        return LanguageName.Named(display.substring(0, 1).uppercase(Format.LOCALE) + display.substring(1))
    }

    private fun primary(key: String): String = key.split('-', '_').first()
}
