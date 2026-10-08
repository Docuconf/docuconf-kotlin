package dev.docuconf.hoplite

import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * KDoc of config class parameters (SPEC §14.7): the first sentence is the description, as Spring's
 * configuration metadata takes it for Java, and the rest of the KDoc is the details.
 *
 * KDoc is not in the compiled classes, so the `dev.docuconf` Gradle plugin indexes it from the sources
 * at build time into the resource [RESOURCE]: one entry per constructor parameter, keyed
 * `<qualified class name>#<parameter>`, holding the comment text without its `/**`, `*/` and leading
 * `*`. A parameter's own KDoc wins over an `@property` tag in its class's KDoc. Without the resource
 * only [Doc] counts.
 */
internal object KDocs {
    const val RESOURCE = "META-INF/docuconf/kdoc.properties"

    private val indexes = ConcurrentHashMap<ClassLoader, Map<String, String>>()

    /** The description (plain text) and details (CommonMark) of [parameter] of [owner], from its KDoc. */
    fun of(owner: KClass<*>, parameter: String): Pair<String?, String?> {
        val loader = owner.java.classLoader ?: return null to null
        val index = indexes.getOrPut(loader) { load(loader) }
        val raw = index["${owner.qualifiedName}#$parameter"] ?: return null to null
        return split(raw)
    }

    private fun load(loader: ClassLoader): Map<String, String> {
        val out = HashMap<String, String>()
        for (url in loader.getResources(RESOURCE)) {
            val p = Properties()
            url.openStream().use { p.load(it.reader(Charsets.UTF_8)) }
            for (name in p.stringPropertyNames()) out.putIfAbsent(name, p.getProperty(name))
        }
        return out
    }

    private val fence = Regex("^\\s*(```|~~~)")
    private val blockTag = Regex("^\\s*@\\w+")

    /** The KDoc's main text: up to its first block tag (`@param`, `@see`, `@sample`, ...), outside code fences. */
    fun main(raw: String): String {
        val out = ArrayList<String>()
        var inFence = false
        for (line in raw.lines()) {
            if (!inFence && blockTag.containsMatchIn(line)) break
            if (fence.containsMatchIn(line)) inFence = !inFence
            out += line
        }
        return out.joinToString("\n").trim()
    }

    /**
     * Splits KDoc into its first sentence, the description, and the rest, the details. The first
     * sentence ends at the first period followed by white space outside code and links, or at the
     * first blank line.
     */
    fun split(raw: String): Pair<String?, String?> {
        val text = main(raw)
        if (text.isBlank()) return null to null
        var end = text.length
        var inCode = false
        var brackets = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> inCode = !inCode
                inCode -> {}
                c == '[' -> brackets++
                c == ']' && brackets > 0 -> brackets--
                brackets == 0 && c == '.' && (i + 1 == text.length || text[i + 1].isWhitespace()) -> {
                    end = i + 1
                    break
                }
                c == '\n' && Regex("^[ \\t]*\\n").containsMatchIn(text.substring(i + 1)) -> {
                    end = i
                    break
                }
            }
            i++
        }
        val description = plain(text.substring(0, end)).ifEmpty { null }
        val details = markdown(text.substring(end)).ifEmpty { null }
        return description to details
    }

    private val link = Regex("\\[([^\\]\\[]+)](?!\\()")

    /** The first sentence as one line of plain text: KDoc links `[Name]` become `Name`. */
    private fun plain(s: String): String = link.replace(s) { it.groupValues[1] }.replace(Regex("\\s+"), " ").trim()

    /** KDoc is Markdown already: only its links to declarations, `[Name]`, become code spans. Code is kept as written. */
    fun markdown(s: String): String {
        val out = ArrayList<String>()
        var inFence = false
        for (line in s.trim('\n').lines()) {
            if (fence.containsMatchIn(line)) {
                inFence = !inFence
                out += line
                continue
            }
            out += if (inFence) line else line.split('`').mapIndexed { n, part -> if (n % 2 == 1) part else link.replace(part) { "`${it.groupValues[1]}`" } }.joinToString("`")
        }
        return out.joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}
