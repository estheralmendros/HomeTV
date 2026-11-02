package com.hometv.tv.ui.text

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * No UI string literal in `app/src/main/kotlin`.
 * Fails on non-empty literals passed to `Text(`, `text =`, `label =`, `placeholder =`,
 * `contentDescription =`, `title =`, `Toast.makeText`, and on ViewModel `UiText.Data("…")` literals.
 */
class NoHardcodedUiTextTest {
    private val patterns = listOf(
        Regex("""\bText\(\s*"[^"]"""),
        Regex("""\b(text|label|placeholder|contentDescription|title|supportingText)\s*=\s*"[^"]"""),
        Regex("""Toast\.makeText\([^,]*,\s*"[^"]"""),
        Regex("""UiText\.Data\(\s*"[^"]"""),
        Regex("""\buiData\(\s*"[^"]"""),
    )

    @Test
    fun `no hard-coded UI text in app main sources`() {
        val root = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        val files = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.size > 10, "app sources not found")
        val offenders = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@mapIndexedNotNull null
                patterns.firstOrNull { it.containsMatchIn(code) }?.let { "${file.name}:${index + 1}: $code" }
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the scanner catches the forbidden forms`() {
        val bad = listOf("Text(\"Hola\")", "label = \"Reproducir\"", "contentDescription = \"Buscar\"", "UiText.Data(\"Hola\")", "Toast.makeText(ctx, \"x\", 0)")
        bad.forEach { sample -> assertTrue(patterns.any { it.containsMatchIn(sample) }, sample) }
        assertTrue(patterns.none { it.containsMatchIn("Text(stringResource(R.string.app_name))") })
    }
}
