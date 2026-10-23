package com.hometv.tv.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * prd3 §UI Language rule 2: `core` holds no Spanish UI words. Fails on string literals in
 * `core/src/main` that contain Spanish letters or catalog words (comments may quote catalog texts).
 */
class CoreHasNoUiTextTest {
    private val catalogWords = listOf("Desconocido", "Temporada", "Episodio", "Especiales", "Forzados", "Externo", "Predeterminado", "convertido", "disponible")
    private val literal = Regex(""""([^"\\]|\\.)*"""")
    private val spanishLetters = Regex("[áéíóúüñÁÉÍÓÚÑ¿¡]")

    @Test
    fun `no Spanish UI text in core main sources`() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "run from the core module directory")
        val offenders = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@mapIndexedNotNull null
                literal.findAll(code.substringBefore(" //")).map { it.value }
                    .firstOrNull { text -> spanishLetters.containsMatchIn(text) || catalogWords.any { it in text } }
                    ?.let { "${file.name}:${index + 1}: $it" }
            }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
