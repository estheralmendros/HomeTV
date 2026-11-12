package com.hometv.tv.core.playback

import com.hometv.tv.core.api.AudioChoice
import com.hometv.tv.core.api.AudioTrack
import com.hometv.tv.core.api.SubtitleChoice
import com.hometv.tv.core.api.SubtitleTrack
import com.hometv.tv.core.api.TrackChoice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Runs `shared/track-choice-cases.json` (prd.md FR-5b) against [TrackChoiceRules]. The path comes
 * from the `hometv.trackChoiceCases` system property set by `core/build.gradle.kts`.
 */
class TrackChoiceRulesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val cases: List<JsonObject> by lazy {
        val path = requireNotNull(System.getProperty("hometv.trackChoiceCases")) {
            "hometv.trackChoiceCases is not set: run through Gradle (:core:test)"
        }
        json.parseToJsonElement(File(path).readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
    }

    @Test
    fun `the shared table holds at least the 24 cases of prd md`() {
        assertTrue(cases.size >= 24, "only ${cases.size} cases in the shared table")
    }

    @TestFactory
    fun `shared case table`(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(case.getValue("name").jsonPrimitive.content) { runCase(case) }
    }

    private fun runCase(case: JsonObject) {
        val mode = when (val m = case.getValue("mode").jsonPrimitive.content) {
            "direct" -> PlaybackMode.DIRECT
            "hls" -> PlaybackMode.HLS
            else -> error("unknown mode $m")
        }
        val choice = case["choice"]?.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement<TrackChoice>(it) }
        val audio = case.getValue("audio_tracks").jsonArray.map { audioTrack(it) }
        val subtitles = case.getValue("subtitle_tracks").jsonArray.map { subtitleTrack(it) }
        case["pick"]?.let { assertEquals(choice?.audio, TrackChoiceRules.audioChoiceOf(audioTrack(it)), "audioChoiceOf(pick)") }
        val expected = case.getValue("expected").jsonObject + (case["expected_tv"]?.jsonObject ?: emptyMap())
        val match = TrackChoiceRules.match(choice, audio, subtitles, mode)
        // getValue: a missing (e.g. misspelt) key fails the case; an explicit JSON null means null.
        assertEquals(expected.getValue("audio_stream_index").jsonPrimitive.intOrNull, match.audioStreamIndex, "audio_stream_index")
        assertEquals(expected.getValue("subtitle_id").jsonPrimitive.contentOrNull, match.subtitleId, "subtitle_id")
    }

    private fun audioTrack(e: JsonElement): AudioTrack {
        val o = e.jsonObject
        return AudioTrack(
            index = o.getValue("index").jsonPrimitive.int,
            codec = "aac",
            channels = o["channels"]?.jsonPrimitive?.intOrNull,
            language = o["language"]?.jsonPrimitive?.contentOrNull,
            title = o["title"]?.jsonPrimitive?.contentOrNull,
            default = o["default"]?.jsonPrimitive?.content == "true",
        )
    }

    private fun subtitleTrack(e: JsonElement): SubtitleTrack = json.decodeFromJsonElement(e)

    // --- Building and merging choices (prd.md FR-5a) ---

    private val eng6 = AudioTrack(index = 2, codec = "ac3", channels = 6, language = " eng ", title = "Director")

    @Test
    fun `audioChoiceOf trims the language, blank is und, channels outside 1-32 are null, long titles are cut`() {
        assertEquals(AudioChoice("eng", "Director", 6), TrackChoiceRules.audioChoiceOf(eng6))
        assertEquals(AudioChoice("und", null, null), TrackChoiceRules.audioChoiceOf(eng6.copy(language = "  ", title = null, channels = 0)))
        assertEquals(AudioChoice("und", null, null), TrackChoiceRules.audioChoiceOf(eng6.copy(language = null, title = null, channels = 33)))
        assertEquals(1000, TrackChoiceRules.audioChoiceOf(eng6.copy(title = "x".repeat(1200))).title?.length)
    }

    @Test
    fun `subtitleChoiceOf - null is off, a track carries its traits`() {
        assertEquals(SubtitleChoice(mode = "off"), TrackChoiceRules.subtitleChoiceOf(null))
        val track = SubtitleTrack(id = "ext-9", source = "external", kind = "text", language = null, title = "SDH", forced = false, hearing_impaired = true)
        assertEquals(
            SubtitleChoice("track", "und", "SDH", forced = false, hearing_impaired = true, source = "external", kind = "text"),
            TrackChoiceRules.subtitleChoiceOf(track),
        )
    }

    private val audioPart = AudioChoice("eng", null, 6)
    private val subsPart = SubtitleChoice("track", "spa", null, false, false, "embedded", "text")

    @Test
    fun `withAudio and withSubtitles replace one part and keep the other`() {
        assertEquals(TrackChoice(audioPart, null), TrackChoiceRules.withAudio(null, audioPart))
        assertEquals(TrackChoice(audioPart, subsPart), TrackChoiceRules.withAudio(TrackChoice(AudioChoice("spa"), subsPart), audioPart))
        assertEquals(TrackChoice(null, subsPart), TrackChoiceRules.withSubtitles(null, subsPart))
        assertEquals(TrackChoice(audioPart, SubtitleChoice("off")), TrackChoiceRules.withSubtitles(TrackChoice(audioPart, subsPart), SubtitleChoice("off")))
    }

    @Test
    fun `sameAudioChoice - both non-null and equal`() {
        assertTrue(TrackChoiceRules.sameAudioChoice(audioPart, audioPart.copy()))
        assertTrue(TrackChoiceRules.sameAudioChoice(AudioChoice("eng"), AudioChoice("eng", null, null)))
        assertFalse(TrackChoiceRules.sameAudioChoice(audioPart, audioPart.copy(channels = 2)))
        assertFalse(TrackChoiceRules.sameAudioChoice(audioPart, null))
        assertFalse(TrackChoiceRules.sameAudioChoice(null, null))
    }

    @Test
    fun `effective - each part from the session pick when set, else the fetched one`() {
        val fetched = TrackChoice(AudioChoice("spa"), subsPart)
        assertNull(TrackChoiceRules.effective(null, null))
        assertEquals(fetched, TrackChoiceRules.effective(fetched, null))
        assertEquals(TrackChoice(audioPart, subsPart), TrackChoiceRules.effective(fetched, TrackChoice(audioPart, null)))
        assertEquals(TrackChoice(AudioChoice("spa"), SubtitleChoice("off")), TrackChoiceRules.effective(fetched, TrackChoice(null, SubtitleChoice("off"))))
        assertEquals(TrackChoice(audioPart, null), TrackChoiceRules.effective(null, TrackChoice(audioPart, null)))
    }

    @Test
    fun `languageKey - region dropped, und kept, empty null`() {
        assertEquals("pt", TrackChoiceRules.languageKey(" pt-BR "))
        assertEquals("zh", TrackChoiceRules.languageKey("zh_Hans"))
        assertEquals("und", TrackChoiceRules.languageKey("UND"))
        assertEquals("fr", TrackChoiceRules.languageKey("fre"))
        assertEquals("xyz", TrackChoiceRules.languageKey("xyz"))
        assertNull(TrackChoiceRules.languageKey("  "))
        assertNull(TrackChoiceRules.languageKey(null))
        assertTrue(TrackChoiceRules.sameLanguage("fra", "fr"))
        assertFalse(TrackChoiceRules.sameLanguage(null, null))
        assertFalse(TrackChoiceRules.sameLanguage("spa", "und"))
    }
}
