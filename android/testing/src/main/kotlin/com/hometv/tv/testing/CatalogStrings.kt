package com.hometv.tv.testing

import com.hometv.tv.ui.text.StringResolver
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The real `values/strings.xml` catalogs of the module under test, parsed on the JVM: lets ViewModel and UiFormat
 * tests resolve `R.string` / `R.plurals` ids to the Spanish catalog texts without Robolectric.
 *
 * Gradle passes two system properties, in the same order (library first, app last; a later catalog overrides an
 * earlier name): `hometv.catalogs` (absolute `strings.xml` paths joined with [File.pathSeparator]) and
 * `hometv.rClasses` (comma-separated fully-qualified `R` class names, whose `string`/`plurals` ids map to names).
 */
object CatalogStrings : StringResolver {
    /** The catalog files, library first. */
    val files: List<File> = requiredProperty("hometv.catalogs").split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)

    /** The `res` folder of each catalog. */
    val resDirs: List<File> get() = files.map { requireNotNull(it.absoluteFile.parentFile?.parentFile) }

    /** name -> raw (unescaped) text. */
    val strings: Map<String, String>

    /** name -> quantity -> raw text. */
    val plurals: Map<String, Map<String, String>>

    init {
        val s = LinkedHashMap<String, String>()
        val p = LinkedHashMap<String, Map<String, String>>()
        for (file in files) {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            root.childElements("string").forEach { s[it.getAttribute("name")] = unescape(it.textContent) }
            root.childElements("plurals").forEach { e ->
                p[e.getAttribute("name")] = e.childElements("item").associate { it.getAttribute("quantity") to unescape(it.textContent) }
            }
        }
        strings = s
        plurals = p
    }

    private val rClasses: List<String> = requiredProperty("hometv.rClasses").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    private val stringNames: Map<Int, String> = idNames("string")
    private val pluralNames: Map<Int, String> = idNames("plurals")

    /** A module's `R` has no `string`/`plurals` class when it defines none of that kind (`:tv` has no plurals). */
    private fun idNames(kind: String): Map<Int, String> = rClasses.flatMap { name ->
        val type = runCatching { Class.forName("$name\$$kind") }.getOrNull() ?: return@flatMap emptyList()
        type.fields.map { it.getInt(null) to it.name }
    }.toMap()

    /** The system property [name] from [lookup]; fails naming it when it is not set. */
    fun requiredProperty(name: String, lookup: (String) -> String? = System::getProperty): String =
        lookup(name)?.takeIf { it.isNotBlank() } ?: error("System property $name is not set (the module's Gradle test config passes it)")

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
