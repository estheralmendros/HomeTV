package com.hometv.tv.core.playback

import com.hometv.tv.core.playback.F.audio
import com.hometv.tv.core.playback.F.sub
import com.hometv.tv.core.playback.PlaybackMode.DIRECT
import com.hometv.tv.core.playback.PlaybackMode.HLS
import com.hometv.tv.core.util.LanguageName
import com.hometv.tv.core.util.UsLocale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(UsLocale::class)
class TrackRulesTest {
    private val spa = sub("emb-3", "spa")
    private val engForced = sub("ext-5", "eng", source = "external", forced = true)
    private val engDefault = sub("emb-6", "eng", default = true)
    private val pgs = sub("emb-4", "eng", kind = "image")

    @Test
    fun `default subtitle - forced in the audio language first`() {
        assertEquals(engForced, TrackRules.defaultSubtitle(listOf(spa, engDefault, engForced), DIRECT, "eng"))
        // 639-2/B vs /T spellings of the same language still match.
        val fraForced = sub("ext-7", "fra", forced = true)
        assertEquals(fraForced, TrackRules.defaultSubtitle(listOf(spa, fraForced), DIRECT, "fre"))
    }

    @Test
    fun `then a default=true track, else Off`() {
        assertEquals(engDefault, TrackRules.defaultSubtitle(listOf(spa, engDefault, engForced), DIRECT, "spa"))
        assertNull(TrackRules.defaultSubtitle(listOf(spa, engForced), DIRECT, "spa"))
        assertNull(TrackRules.defaultSubtitle(listOf(spa), DIRECT, null))
        assertNull(TrackRules.defaultSubtitle(emptyList(), DIRECT, "eng"))
    }

    @Test
    fun `HLS hides image tracks, also as the default`() {
        val defaultPgs = pgs.copy(default = true)
        assertEquals(listOf(spa, pgs), TrackRules.visibleSubtitles(listOf(spa, pgs), DIRECT))
        assertEquals(listOf(spa), TrackRules.visibleSubtitles(listOf(spa, pgs), HLS))
        assertEquals(defaultPgs, TrackRules.defaultSubtitle(listOf(spa, defaultPgs), DIRECT, "spa"))
        assertNull(TrackRules.defaultSubtitle(listOf(spa, defaultPgs), HLS, "spa"))
    }

    @Test
    fun `audio labels are structured`() {
        assertEquals(
            TrackLabel(LanguageName.Named("Inglés"), "EAC3 5.1", null, listOf(TrackTag.Default)),
            TrackRules.audioLabel(audio(1, "eac3", 6, "eng", default = true)),
        )
        assertEquals(
            TrackLabel(LanguageName.Named("Inglés"), "EAC3 5.1", "Surround", listOf(TrackTag.Default)),
            TrackRules.audioLabel(audio(1, "eac3", 6, "eng", title = "Surround", default = true)),
        )
        assertEquals(TrackLabel(LanguageName.Unknown, "AAC 2.0", null, emptyList()), TrackRules.audioLabel(audio(2, "aac", 2, "und")))
        assertEquals(TrackLabel(LanguageName.Named("Español"), "TRUEHD 7.1", null, emptyList()), TrackRules.audioLabel(audio(3, "truehd", 8, "spa")))
        assertEquals(TrackLabel(LanguageName.Named("Japonés"), "AAC", null, emptyList()), TrackRules.audioLabel(audio(4, "aac", null, "jpn")))
        assertEquals(
            listOf(TrackTag.Default, TrackTag.Converted, TrackTag.Unavailable),
            TrackRules.audioLabel(audio(1, "dts", 6, "eng", default = true), converted = true, unavailable = true).tags,
        )
        assertEquals("PCM 2.0", TrackRules.audioLabel(audio(1, "pcm_s16le", 2)).codec)
    }

    @Test
    fun `subtitle labels are structured`() {
        assertEquals(TrackLabel(LanguageName.Named("Español"), null, null, emptyList()), TrackRules.subtitleLabel(spa))
        assertEquals(TrackLabel(LanguageName.Named("Inglés"), null, null, listOf(TrackTag.Forced, TrackTag.External)), TrackRules.subtitleLabel(engForced))
        assertEquals(listOf(TrackTag.Sdh), TrackRules.subtitleLabel(sub("emb-8", sdh = true)).tags)
        assertEquals(listOf(TrackTag.Image("PGS")), TrackRules.subtitleLabel(pgs).tags)
        assertEquals("Commentary", TrackRules.subtitleLabel(sub("emb-9", title = "Commentary")).title)
        assertEquals(LanguageName.Unknown, TrackRules.subtitleLabel(sub("emb-1", "und")).language)
        assertEquals(LanguageName.Code("XYZ"), TrackRules.subtitleLabel(sub("emb-1", "xyz")).language)
        assertEquals(listOf(TrackTag.Unavailable), TrackRules.subtitleLabel(spa, unavailable = true).tags)
    }

    @Test
    fun `codec display tokens`() {
        assertEquals("PGS", TrackRules.subtitleCodecToken("hdmv_pgs_subtitle"))
        assertEquals("VobSub", TrackRules.subtitleCodecToken("dvd_subtitle"))
        assertEquals("DVB", TrackRules.subtitleCodecToken("dvb_subtitle"))
        assertEquals("XSUB", TrackRules.subtitleCodecToken("xsub"))
        assertEquals("?", TrackRules.subtitleCodecToken(null))
        assertEquals("EAC3", TrackRules.audioCodecToken("eac3"))
        assertEquals("PCM", TrackRules.audioCodecToken("pcm_s24le"))
    }

    @Test
    fun `no output contains a catalog word - language names come only from LanguageNames`() {
        val catalogWords = listOf("Desconocido", "Forzados", "Externo", "Predeterminado", "Imagen", "convertido", "no disponible", "SDH ·")
        val labels = listOf(
            TrackRules.audioLabel(audio(1, "eac3", 6, "und", default = true), converted = true, unavailable = true),
            TrackRules.subtitleLabel(sub("x", "und", kind = "image", source = "external", forced = true, sdh = true), unavailable = true),
        )
        for (label in labels) {
            val text = listOfNotNull(label.codec, label.title).joinToString() + label.tags.joinToString()
            for (word in catalogWords) assertFalse(word in text, "$word in $text")
        }
    }

    @Test
    fun `audio switch route`() {
        val caps = F.caps // aac, ac3, eac3; max 6 channels
        assertEquals(AudioSwitchRoute.LOCAL, TrackRules.audioSwitchRoute(audio(2, "ac3", 6), DIRECT, caps))
        assertEquals(AudioSwitchRoute.LOCAL, TrackRules.audioSwitchRoute(audio(2, "AAC", null), DIRECT, caps))
        assertEquals(AudioSwitchRoute.SERVER, TrackRules.audioSwitchRoute(audio(2, "dts", 6), DIRECT, caps))
        assertEquals(AudioSwitchRoute.SERVER, TrackRules.audioSwitchRoute(audio(2, "eac3", 8), DIRECT, caps))
        assertEquals(AudioSwitchRoute.SERVER, TrackRules.audioSwitchRoute(audio(2, "aac", 2), HLS, caps))
    }
}
