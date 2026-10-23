package com.hometv.tv.testing

import com.hometv.tv.R
import com.hometv.tv.ui.text.StringResolver
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The real `values/strings.xml`, parsed on the JVM: lets ViewModel and UiFormat tests resolve
 * `R.string` / `R.plurals` ids to the Spanish catalog texts without Robolectric.
 */
object CatalogStrings : StringResolver {
    val file: File = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml").map(::File).first { it.isFile }
    val resDir: File get() = requireNotNull(file.absoluteFile.parentFile?.parentFile)

    /** name → raw (unescaped) text. */
    val strings: Map<String, String>

    /** name → quantity → raw text. */
    val plurals: Map<String, Map<String, String>>

    init {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = doc.documentElement
        strings = root.childElements("string").associate { it.getAttribute("name") to unescape(it.textContent) }
        plurals = root.childElements("plurals").associate { p ->
            p.getAttribute("name") to p.childElements("item").associate { it.getAttribute("quantity") to unescape(it.textContent) }
        }
    }

    private val stringNames: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private val pluralNames: Map<Int, String> = R.plurals::class.java.fields.associate { it.getInt(null) to it.name }

    fun nameOf(id: Int): String = stringNames[id] ?: error("Unknown string id $id")

    fun text(name: String): String = strings[name] ?: error("Missing string $name")

    override fun string(id: Int, vararg args: String): String = format(text(nameOf(id)), args)

    override fun plural(id: Int, count: Int, vararg args: String): String {
        val forms = plurals[pluralNames[id] ?: error("Unknown plurals id $id")] ?: error("Missing plurals")
        val form = forms[if (count == 1) "one" else "other"] ?: forms.getValue("other")
        return format(form, args)
    }

    private fun format(template: String, args: Array<out String>): String =
        if (args.isEmpty()) template else String.format(Locale.forLanguageTag("es-ES"), template, *args)

    /** Android string resource unescaping for the forms the catalog uses. */
    fun unescape(raw: String): String {
        val trimmed = raw.trim()
        val body = if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) trimmed.substring(1, trimmed.length - 1) else trimmed
        return body.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
    }

    private fun Element.childElements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.parentNode == this }
    }
}
