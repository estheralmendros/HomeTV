package com.hometv.tv.ui.text

import com.hometv.tv.testing.CatalogStrings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The `:shared` catalog on its own: Spanish only, templates, no `%d`/`%f`, the glossary terms it owns. */
class StringsCatalogTest {
    private val catalog = CatalogStrings

    @Test
    fun `the test reads only the shared catalog`() {
        assertEquals(1, catalog.files.size, catalog.files.toString())
        assertTrue(catalog.files.single().invariantSeparatorsPath.endsWith("shared/src/main/res/values/strings.xml"))
    }

    @Test
    fun `no values-lang folder exists`() {
        val localized = catalog.resDirs.flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isDirectory && Regex("^values-[a-z]{2}(-r[A-Z]{2})?$").matches(it.name) }
        assertEquals(emptyList<String>(), localized.map { it.name })
    }

    @Test
    fun `UI glossary terms owned by the shared catalog are present`() {
        val all = catalog.strings.values + catalog.plurals.values.flatMap { it.values }
        val glossary = listOf(
            "Películas", "Series", "Seguir viendo", "Añadidos recientemente", "Reproducir", "Reanudar desde %1\$s",
            "Ver desde el principio", "Parte %1\$s", "Marcar como visto", "Desactivados", "Forzados", "Externo",
            "Predeterminado", "(convertido)", "Probar otra versión", "Preparando el vídeo…", "Reintentar", "Volver",
            "Desconocido", "Puntuar", "Cambiar", "Sin puntuación",
        )
        assertEquals(emptyList<String>(), glossary.filterNot { it in all })
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
}
