package com.hometv.tv.ui.text

import com.hometv.tv.testing.CatalogStrings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** prd3 §UI Language (Rev 9/10): one Spanish catalog, glossary terms, templates, no `%d`/`%f`. */
class StringsCatalogTest {
    private val catalog = CatalogStrings

    @Test
    fun `no values-lang folder exists`() {
        val localized = catalog.resDir.listFiles().orEmpty().filter { it.isDirectory && Regex("^values-[a-z]{2}(-r[A-Z]{2})?$").matches(it.name) }
        assertEquals(emptyList<String>(), localized.map { it.name })
    }

    @Test
    fun `glossary terms of prd md UI Language are in the catalog`() {
        val all = catalog.strings.values + catalog.plurals.values.flatMap { it.values }
        val glossary = listOf(
            "Inicio", "Películas", "Series", "Seguir viendo", "A continuación", "Añadidos recientemente", "Buscar", "Ajustes",
            "Reproducir", "Reanudar desde %1\$s", "Ver desde el principio", "Reproducir ahora", "Parte %1\$s",
            "Marcar como visto", "Audio y subtítulos", "Desactivados", "Forzados", "Externo", "Predeterminado", "(convertido)",
            "Probar otra versión", "Preparando el vídeo…", "Reintentar", "Cancelar", "Volver", "¿Quién está viendo?", "Servidor",
            "Cambiar servidor", "Desconocido", "En tu catálogo", "Biografía",
            // ratings prd.md glossary
            "Actividad", "Tu puntuación", "Media de la familia", "¿Qué te ha parecido?", "Puntuar", "Cambiar", "Guardar",
            "Ahora no", "Quitar puntuación", "Puntuación guardada", "Sin puntuación",
        )
        val missing = glossary.filterNot { it in all }
        assertEquals(emptyList<String>(), missing)
        assertTrue(all.any { "televisor" in it })
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        ignoreLeadingAndTrailingWhitespace = false,
        value = [
            "audio_change_cancelled|Cambio de audio cancelado",
            "audio_switch_failed|No se ha podido cambiar la pista de audio",
            "audio_track_unplayable|Esa pista de audio no se puede reproducir en este televisor",
            "language_unknown|Desconocido",
            "episode_code|T%1\$s · E%2\$s",
            "season_numbered|Temporada %1\$s",
            "season_specials|Especiales",
            "episode_numbered|Episodio %1\$s",
            "runtime_h_min|%1\$s h %2\$s min",
            "runtime_h|%1\$s h",
            "runtime_min|%1\$s min",
            "rating|★ %1\$s",
            "tag_forced|Forzados",
            "tag_sdh|SDH",
            "tag_external|Externo",
            "tag_image|Imagen (%1\$s)",
            "tag_default|Predeterminado",
            "tag_converted|(convertido)",
            "tag_unavailable|(no disponible)",
            "separator_dot| · ",
            "item_gone|Este título ya no está disponible.",
            "profiles_session_expired|Tu sesión ha caducado. Vuelve a elegir tu perfil.",
            "home_library_empty|Tu biblioteca está vacía. Añade archivos y escanea la biblioteca desde la app web.",
            "search_no_results|No hay resultados para «%1\$s»",
            "player_preparing|Preparando el vídeo…",
            "player_different_version|Reproduciendo otra versión desde el principio",
            "player_subtitles_preparing|Preparando los subtítulos…",
            "player_subtitles_unavailable|Subtítulos no disponibles",
            "player_audio_switching|Cambiando el audio…",
            "player_audio_preparing|Preparando el audio…",
            "player_last_episode|Este es el último episodio",
            "player_too_many_sessions|El servidor ya está emitiendo en el número máximo de dispositivos. Detén la reproducción en otro dispositivo y vuelve a intentarlo.",
            "player_hls_disk_full|Al servidor le queda poco espacio para emitir. Detén otra reproducción o inténtalo de nuevo más tarde.",
            "reason_video_codec_unsupported|Este televisor no admite el códec de vídeo %1\$s.",
            "reason_keyframes_too_sparse|Los fotogramas clave del archivo están demasiado separados para convertirlo.",
            "reason_unknown|Este formato no es compatible.",
            "error_unexpected|Se ha producido un error inesperado (%1\$s).",
            "next_unplayable|El siguiente episodio no se puede reproducir en este televisor",
            "next_load_failed|No se ha podido cargar el siguiente episodio",
            "countdown_starts_in|Empieza en %1\$s",
            "part_prompt|Continuando con la parte %1\$s",
            "setup_error_cleartext|Usa la dirección https:// que muestra Tailscale (…ts.net).",
            "person_in_catalog|En tu catálogo",
            "person_biography|Biografía",
            "person_as_character|como %1\$s",
            "person_life_living|%1\$s (%2\$s)",
            "person_life_deceased|%1\$s – %2\$s (%3\$s)",
            "person_life_deceased_no_age|%1\$s – %2\$s",
            "person_life_death_only|– %1\$s",
            "person_empty|No aparece en ningún título disponible de tu catálogo.",
            "person_incomplete|Puede que falten títulos: no se ha podido consultar TMDB.",
            "person_gone|Esta persona no está disponible.",
            "nav_watchlist|Mi lista",
            "watchlist_title|Mi lista",
            "watchlist_toggle|Mi lista",
            "watchlist_in_list|En Mi lista",
            "watchlist_add|Añadir a Mi lista",
            "watchlist_remove|Quitar de Mi lista",
            "watchlist_update_failed|No se ha podido actualizar Mi lista.",
            "watchlist_type|Tipo: %1\$s",
            "dialog_type|Tipo",
            "watchlist_type_all|Todo",
            "watchlist_type_films|Películas",
            "watchlist_type_shows|Series",
            "watchlist_empty|Tu lista está vacía. Añade películas y series desde su ficha con «Mi lista».",
            "watchlist_no_films|No hay películas en tu lista.",
            "watchlist_no_shows|No hay series en tu lista.",
            "watchlist_show_all|Ver todo",
            "watchlist_watched|Estado: %1\$s",
            "watchlist_watched_all|Todos",
            "watchlist_watched_yes|Vistos",
            "watchlist_watched_no|No vistos",
            "watchlist_no_matches_filters|Ningún título de tu lista coincide con los filtros.",
            "nav_activity|Actividad",
            "activity_title|Actividad",
            "rating_rate|Puntuar",
            "rating_change|Cambiar",
            "rating_mine|Tu puntuación",
            "rating_mine_description|Tu puntuación: %1\$s. %2\$s",
            "rating_average|Media de la familia",
            "rating_average_value|%1\$s (%2\$s)",
            "rating_question|¿Qué te ha parecido?",
            "rating_save|Guardar",
            "rating_not_now|Ahora no",
            "rating_remove|Quitar puntuación",
            "rating_saved|Puntuación guardada",
            "rating_save_failed|No se ha podido guardar la puntuación.",
            "rating_update_failed|No se ha podido actualizar tu puntuación.",
            "rating_none|Sin puntuación",
            "rating_empty_value|—",
            "rating_more|y %1\$s más",
            "rating_others_title|Puntuaciones",
            "card_my_rating|tu puntuación: %1\$s",
            "activity_row|%1\$s ha puntuado %2\$s con",
            "activity_row_own|Has puntuado %1\$s con",
            "activity_row_description|%1\$s ha puntuado %2\$s con %3\$s, %4\$s",
            "activity_row_own_description|Has puntuado %1\$s con %2\$s, %3\$s",
            "activity_row_description_untimed|%1\$s ha puntuado %2\$s con %3\$s",
            "activity_row_own_description_untimed|Has puntuado %1\$s con %2\$s",
            "activity_empty|Todavía nadie ha puntuado nada. Cuando alguien puntúe una película o serie, aparecerá aquí.",
            "time_just_now|ahora mismo",
            "time_yesterday|ayer",
        ],
    )
    fun `catalog text`(name: String, expected: String) {
        assertEquals(expected, catalog.text(name))
    }

    @Test
    fun `no string or plurals item uses a numeric placeholder`() {
        val numeric = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[dfeEgGxXo]""")
        val offenders = catalog.strings.filterValues { numeric.containsMatchIn(it) }.keys +
            catalog.plurals.filterValues { forms -> forms.values.any { numeric.containsMatchIn(it) } }.keys
        assertEquals(emptySet<String>(), offenders)
    }

    @Test
    fun `every plurals defines one and other and prints its count argument`() {
        assertTrue(catalog.plurals.isNotEmpty())
        catalog.plurals.forEach { (name, forms) ->
            assertEquals(setOf("one", "other"), forms.keys, name)
            forms.values.forEach { assertTrue("%1\$s" in it, "$name must print %1\$s: $it") }
        }
    }

    @Test
    fun `plurals texts`() {
        assertEquals("1 episodio", catalog.plural(com.hometv.tv.R.plurals.episode_count, 1, "1"))
        assertEquals("22 episodios", catalog.plural(com.hometv.tv.R.plurals.episode_count, 22, "22"))
        assertEquals("150 sin ver", catalog.plural(com.hometv.tv.R.plurals.unwatched_count, 150, "150"))
        assertEquals("quedan unos 3 min", catalog.plural(com.hometv.tv.R.plurals.minutes_left, 3, "3"))
        assertEquals("1 año", catalog.plural(com.hometv.tv.R.plurals.person_age, 1, "1"))
        assertEquals("30 años", catalog.plural(com.hometv.tv.R.plurals.person_age, 30, "30"))
        assertEquals("1 estrella", catalog.plural(com.hometv.tv.R.plurals.rating_stars, 1, "1"))
        assertEquals("4,5 estrellas", catalog.plural(com.hometv.tv.R.plurals.rating_stars, 2, "4,5"))
        assertEquals("hace 1 minuto", catalog.plural(com.hometv.tv.R.plurals.time_minutes_ago, 1, "1"))
        assertEquals("hace 5 minutos", catalog.plural(com.hometv.tv.R.plurals.time_minutes_ago, 5, "5"))
        assertEquals("hace 1 hora", catalog.plural(com.hometv.tv.R.plurals.time_hours_ago, 1, "1"))
        assertEquals("hace 3 horas", catalog.plural(com.hometv.tv.R.plurals.time_hours_ago, 3, "3"))
    }
}
