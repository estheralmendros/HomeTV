package com.hometv.tv.ui.text

import com.hometv.tv.R
import com.hometv.tv.core.playback.MessageKey
import com.hometv.tv.core.playback.PlayerAction
import com.hometv.tv.core.playback.PreparingEstimate
import com.hometv.tv.core.playback.TrackLabel
import com.hometv.tv.core.playback.TrackRules
import com.hometv.tv.core.playback.TrackTag
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.util.EpisodeCode
import com.hometv.tv.core.util.EpisodeTitle
import com.hometv.tv.core.util.LanguageName
import com.hometv.tv.core.util.Runtime
import com.hometv.tv.core.util.SeasonTitle
import com.hometv.tv.core.util.UsLocale
import com.hometv.tv.testing.CatalogStrings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith

/** UiFormat resolves core structured values through the real catalog (an English JVM locale changes nothing). */
@ExtendWith(UsLocale::class)
class UiFormatTest {
    private val fmt = UiFormat(CatalogStrings)

    @Test
    fun `runtime picks the template by its parts`() {
        assertEquals("1 h 52 min", fmt.runtime(Runtime(1, 52)))
        assertEquals("2 h 35 min", fmt.runtime(Runtime(2, 35)))
        assertEquals("42 min", fmt.runtime(Runtime(0, 42)))
        assertEquals("2 h", fmt.runtime(Runtime(2, 0)))
        assertEquals("0 min", fmt.runtime(Runtime(0, 0)))
    }

    @Test
    fun `episode codes, seasons and episodes`() {
        assertEquals("T2 · E4", fmt.episodeCode(EpisodeCode(2, 4)))
        assertEquals("Temporada 3", fmt.seasonTitle(SeasonTitle.Numbered(3)))
        assertEquals("Especiales", fmt.seasonTitle(SeasonTitle.Specials))
        assertEquals("Temporada de lujo", fmt.seasonTitle(SeasonTitle.Named("Temporada de lujo")))
        assertEquals("Episodio 3", fmt.episodeTitle(EpisodeTitle.Numbered(3)))
        assertEquals("Office Olympics", fmt.episodeTitle(EpisodeTitle.Named("Office Olympics")))
    }

    @Test
    fun `language names`() {
        assertEquals("Desconocido", fmt.language(LanguageName.Unknown))
        assertEquals("Inglés", fmt.language(LanguageName.Named("Inglés")))
        assertEquals("XYZ", fmt.language(LanguageName.Code("XYZ")))
    }

    @Test
    fun `track labels join words with a middle dot and append parenthesized tags with a space`() {
        val surround = F.audio(1, codec = "eac3", channels = 6, language = "eng", title = "Envolvente", default = true)
        assertEquals("Inglés · EAC3 5.1 · Envolvente · Predeterminado", fmt.trackLabel(TrackRules.audioLabel(surround)))
        assertEquals("Español · AC3 5.1", fmt.trackLabel(TrackRules.audioLabel(F.audio(2, codec = "ac3", channels = 6, language = "spa"))))
        assertEquals("Desconocido · AAC 2.0", fmt.trackLabel(TrackRules.audioLabel(F.audio(3, language = "und"))))
        assertEquals("Inglés · EAC3 5.1 · Envolvente · Predeterminado (convertido)", fmt.trackLabel(TrackRules.audioLabel(surround, converted = true)))
        assertEquals("Inglés · Forzados · Externo", fmt.trackLabel(TrackRules.subtitleLabel(F.sub("ext-1", forced = true, source = "external"))))
        assertEquals("Inglés · Imagen (PGS)", fmt.trackLabel(TrackRules.subtitleLabel(F.sub("emb-4", kind = "image"))))
        assertEquals("Inglés · SDH", fmt.trackLabel(TrackRules.subtitleLabel(F.sub("emb-5", sdh = true))))
        assertEquals("Español", fmt.trackLabel(TrackRules.subtitleLabel(F.sub("emb-3", language = "spa"))))
        assertEquals("Francés (no disponible)", fmt.trackLabel(TrackRules.subtitleLabel(F.sub("emb-6", language = "fre"), unavailable = true)))
        assertEquals("Desconocido · Imagen (VobSub)", fmt.trackLabel(TrackLabel(LanguageName.Unknown, null, null, listOf(TrackTag.Image("VobSub")))))
    }

    @Test
    fun `preparing estimates`() {
        assertEquals("Preparando el vídeo…", fmt.preparing(PreparingEstimate.None))
        assertEquals("Preparando el vídeo… queda menos de un minuto", fmt.preparing(PreparingEstimate.LessThanAMinute))
        assertEquals("Preparando el vídeo… quedan unos 3 min", fmt.preparing(PreparingEstimate.Minutes(3)))
        assertEquals("Preparando el audio… quedan unos 12 min", fmt.preparing(PreparingEstimate.Minutes(12), R.string.player_audio_preparing))
    }

    @Test
    fun `UiText resolves nested values, plurals and data`() {
        val resume = UiText.Res(R.string.show_resume_episode, listOf(EpisodeCode(2, 3)))
        assertEquals("Reanudar T2 · E3", fmt.text(resume))
        assertEquals("150 sin ver", fmt.text(UiText.Plural(R.plurals.unwatched_count, 150, listOf("150"))))
        assertEquals("Dune", fmt.text(UiText.Data("Dune")))
        assertEquals("2 h 35 min", fmt.text(UiText.Value(Runtime(2, 35))))
        val meta = UiText.Joined(listOf(UiText.Data("2021"), UiText.Res(R.string.rating, listOf("7,8")), UiText.Res(R.string.runtime_min, listOf("42"))))
        assertEquals("2021 · ★ 7,8 · 42 min", fmt.text(meta))
        assertEquals("Películas · 1.234", fmt.text(UiText.Res(R.string.pair_dot, listOf(UiText.Res(R.string.nav_films), "1.234"))))
        assertEquals("Reanudar desde 1:02:03", fmt.text(UiText.Res(R.string.detail_resume_from, listOf("1:02:03"))))
    }

    @Test
    fun `numbers must be pre-formatted strings`() {
        assertThrows<IllegalArgumentException> { fmt.text(UiText.Res(R.string.part_n, listOf(2))) }
    }

    @Test
    fun `every message key and action has a catalog text`() {
        MessageKey.entries.forEach { CatalogStrings.nameOf(UiFormat.messageRes(it)) }
        PlayerAction.entries.forEach { CatalogStrings.nameOf(UiFormat.actionRes(it)) }
        assertEquals("Este televisor no admite el códec de vídeo HEVC.", fmt.message(MessageKey.REASON_VIDEO_CODEC_UNSUPPORTED, listOf("HEVC")))
        assertEquals("Se ha producido un error inesperado (teapot).", fmt.message(MessageKey.UNEXPECTED_ERROR, listOf("teapot")))
        assertEquals("Probar otra versión", CatalogStrings.string(UiFormat.actionRes(PlayerAction.TRY_OTHER_VERSION)))
    }

    @Test
    fun `TMDB show status is mapped through the catalog`() {
        assertEquals("Finalizada", UiFormat.showStatusRes("Ended")?.let { CatalogStrings.string(it) })
        assertEquals("En emisión", UiFormat.showStatusRes("Returning Series")?.let { CatalogStrings.string(it) })
        assertEquals("Cancelada", UiFormat.showStatusRes("Canceled")?.let { CatalogStrings.string(it) })
        assertEquals("En producción", UiFormat.showStatusRes("In Production")?.let { CatalogStrings.string(it) })
        assertEquals("Planificada", UiFormat.showStatusRes("Planned")?.let { CatalogStrings.string(it) })
        assertEquals("Piloto", UiFormat.showStatusRes("Pilot")?.let { CatalogStrings.string(it) })
        assertEquals(null, UiFormat.showStatusRes("Rumored"))
        assertEquals(null, UiFormat.showStatusRes(null))
    }
}
