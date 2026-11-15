package com.hometv.tv.testing

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * No UI string literal in a module's `src/main/kotlin`: each module with UI code calls [assertClean] on its own root.
 * Fails on non-empty literals passed to `Text(`, `text =`, `label =`, `placeholder =`,
 * `contentDescription =`, `title =`, `Toast.makeText`, and on ViewModel `UiText.Data("…")` literals.
 */
object NoHardcodedUiText {
    private val patterns = listOf(
        Regex("""\bText\(\s*"[^"]"""),
        Regex("""\b(text|label|placeholder|contentDescription|title|supportingText)\s*=\s*"[^"]"""),
        Regex("""Toast\.makeText\([^,]*,\s*"[^"]"""),
        Regex("""UiText\.Data\(\s*"[^"]"""),
        Regex("""\buiData\(\s*"[^"]"""),
    )

    /** Whether [code] (one trimmed source line) holds a forbidden literal. */
    fun isOffending(code: String): Boolean = patterns.any { it.containsMatchIn(code) }

    /** Fails listing every offending line under [root]; also fails when [root] holds fewer than [minFiles] sources. */
    fun assertClean(root: File, minFiles: Int = 10) {
        val files = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.size > minFiles, "sources not found under $root")
        val offenders = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@mapIndexedNotNull null
                if (isOffending(code)) "${file.name}:${index + 1}: $code" else null
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /** The scanner's own check: it catches each forbidden form and lets a resource read through. */
    fun assertScannerCatchesForbiddenForms() {
        val bad = listOf("Text(\"Hola\")", "label = \"Reproducir\"", "contentDescription = \"Buscar\"", "UiText.Data(\"Hola\")", "Toast.makeText(ctx, \"x\", 0)")
        bad.forEach { sample -> assertTrue(isOffending(sample), sample) }
        assertTrue(!isOffending("Text(stringResource(R.string.app_name))"))
    }
}
