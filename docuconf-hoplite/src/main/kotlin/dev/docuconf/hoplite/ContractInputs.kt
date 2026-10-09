package dev.docuconf.hoplite

import com.sksamuel.hoplite.Node
import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.ConfigFormat
import dev.docuconf.kotlin.core.ConfigViolationException
import dev.docuconf.kotlin.core.Contract
import dev.docuconf.kotlin.core.ContractFirst
import dev.docuconf.kotlin.core.ContractValues
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.FileSpec
import dev.docuconf.kotlin.core.JsonSyntaxException
import dev.docuconf.kotlin.core.JsonValue
import dev.docuconf.kotlin.core.OverlaySpec
import dev.docuconf.kotlin.core.Violation
import java.nio.file.Files
import java.time.Clock
import kotlin.reflect.typeOf

/**
 * The file side of contract-first mode: [ContractFirst.Inputs] backed by the code that checks a
 * declared config class's files at boot ([FileLoader]), and overlays read with Hoplite's parsers.
 * Paths are under `DOCUCONF_FILE_ROOT` (SPEC §11.1).
 */
internal class ContractInputs(
    env: Map<String, String>,
    private val fileRoot: String?,
    clock: Clock,
    private val classLoader: ClassLoader,
) : ContractFirst.Inputs {
    private val files = FileLoader(env, fileRoot, clock, classLoader) {}

    override fun loadFile(spec: FileSpec, env: Map<String, String>): ContractFirst.LoadedFile {
        val before = files.violations.size
        files.load(FileBinding(spec, listOf(spec.name), typeOf<Any>(), valueType = null))
        val violations = files.violations.subList(before, files.violations.size).toList()
        val value = files.loaded[spec.name]?.let { v ->
            when (v) {
                is ConfigFile<*> -> v.value
                is TextFile -> v.text
                else -> v
            }
        }
        return ContractFirst.LoadedFile(value, violations)
    }

    override fun readOverlay(spec: OverlaySpec, env: Map<String, String>): ContractFirst.LoadedOverlay {
        val path = Overlays.resolve(spec, fileRoot)
        if (!Files.exists(path)) return ContractFirst.LoadedOverlay(null)
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return ContractFirst.LoadedOverlay(null, listOf(Violation(Codes.FILE_UNREADABLE, spec.name, "overlay $path is not a readable file")))
        }
        fun malformed(why: String) = ContractFirst.LoadedOverlay(null, listOf(Violation(Codes.FILE_MALFORMED, spec.name, "overlay $path $why")))
        val data: JsonValue = try {
            parse(spec.format, Files.readAllBytes(path), path.toString()) ?: return ContractFirst.LoadedOverlay(null)
        } catch (e: JsonSyntaxException) {
            return malformed("is not valid ${spec.format.wire}: ${e.message}")
        } catch (e: java.io.IOException) {
            return ContractFirst.LoadedOverlay(null, listOf(Violation(Codes.FILE_UNREADABLE, spec.name, "overlay $path could not be read: ${e.javaClass.simpleName}")))
        } catch (e: Exception) {
            return malformed("is not valid ${spec.format.wire}: ${FileLoader.firstLine(e.message)}")
        }
        return when (data) {
            is JsonValue.Obj -> ContractFirst.LoadedOverlay(data)
            JsonValue.Null -> ContractFirst.LoadedOverlay(null)
            else -> malformed("must hold an object of keys at its top level")
        }
    }

    /**
     * An overlay's native values. JSON is read by docuconf's own strict reader, so numbers keep their
     * type exactly; YAML and TOML by Hoplite's parser modules, with YAML scalars typed as YAML 1.2's
     * core schema reads them (Hoplite keeps every YAML scalar as a string). Null for an empty file.
     */
    private fun parse(format: ConfigFormat, bytes: ByteArray, source: String): JsonValue? {
        if (format == ConfigFormat.JSON) return JsonValue.parse(String(bytes, Charsets.UTF_8))
        val parser = parserFor(format, classLoader)
            ?: throw DeclarationException(listOf("no Hoplite parser for ${format.wire} on the classpath; add ${parserModule(format)}"))
        val node: Node = bytes.inputStream().use { parser.load(it, source) }
        if (node is com.sksamuel.hoplite.Undefined) return null
        return ConfigData.of(node, format, null)
    }
}

/** A config file's data as JSON, for contract-first mode, which has no Kotlin type to bind it to. */
internal object ConfigData {
    private val yamlInt = Regex("^[-+]?[0-9]+$")
    private val yamlFloat = Regex("^[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?$")
    private val yamlBool = setOf("true", "True", "TRUE", "false", "False", "FALSE")

    /**
     * The data of a parsed file. Hoplite's YAML parser keeps every scalar as a string, so a YAML
     * scalar gets the type its [schema] asks for when it reads as one (as the declaration mode
     * validates YAML), and otherwise the type YAML 1.2's core schema gives a plain scalar.
     */
    fun of(node: Node, format: ConfigFormat, schema: JsonValue?): JsonValue {
        val json = toJson(node)
        return if (format == ConfigFormat.YAML) typed(json, schema) else json
    }

    private fun typed(v: JsonValue, schema: JsonValue?): JsonValue {
        val s = (schema as? JsonValue.Obj)?.fields
        return when (v) {
            is JsonValue.Obj -> {
                val props = (s?.get("properties") as? JsonValue.Obj)?.fields
                val additional = s?.get("additionalProperties") as? JsonValue.Obj
                JsonValue.Obj(v.fields.mapValues { (k, x) -> typed(x, props?.get(k) ?: additional) })
            }
            is JsonValue.Arr -> JsonValue.Arr(v.items.map { typed(it, s?.get("items")) })
            is JsonValue.Str -> scalar(v.value, types(s))
            else -> v
        }
    }

    private fun types(s: Map<String, JsonValue>?): List<String>? = when (val t = s?.get("type")) {
        is JsonValue.Str -> listOf(t.value)
        is JsonValue.Arr -> t.items.mapNotNull { (it as? JsonValue.Str)?.value }
        else -> null
    }

    private fun scalar(text: String, types: List<String>?): JsonValue {
        if (types == null) {
            return when {
                yamlInt.matches(text) -> text.toLongOrNull()?.let { JsonValue.Int(it) } ?: JsonValue.Str(text)
                yamlFloat.matches(text) -> text.toDoubleOrNull()?.let { JsonValue.Float(it) } ?: JsonValue.Str(text)
                text in yamlBool -> JsonValue.Bool(text.lowercase() == "true")
                else -> JsonValue.Str(text)
            }
        }
        for (t in types) {
            when (t) {
                "string" -> return JsonValue.Str(text)
                "integer" -> if (yamlInt.matches(text)) text.toLongOrNull()?.let { return JsonValue.Int(it) }
                "number" -> when {
                    yamlInt.matches(text) -> text.toLongOrNull()?.let { return JsonValue.Int(it) }
                    yamlFloat.matches(text) -> text.toDoubleOrNull()?.let { return JsonValue.Float(it) }
                }
                "boolean" -> if (text in yamlBool) return JsonValue.Bool(text.lowercase() == "true")
            }
        }
        return JsonValue.Str(text)
    }
}

/**
 * Contract-first mode with files (SPEC §11.2 item 11): checks [env] against [contract], including its
 * file inputs (config files in JSON, YAML and TOML, TLS key pairs, CA bundles, keystores, text and
 * binary files), profiles and config-file overlays, with every path under `DOCUCONF_FILE_ROOT` from
 * [env] (or [fileRoot]). File inputs are checked by the code that checks a declared class's files at
 * boot. YAML and TOML need Hoplite's parser module on the classpath.
 *
 * ```
 * val contract = ContractFirst.parse(File("contract.json").readText())
 * when (val r = Docuconf.checkContract(contract, System.getenv())) {
 *     is ContractFirst.Result.Success -> r.values.file("routes")   // the config file's data
 *     is ContractFirst.Result.Failure -> error(r.violations.joinToString("\n"))
 * }
 * ```
 */
public fun Docuconf.checkContract(
    contract: Contract,
    env: Map<String, String> = System.getenv(),
    fileRoot: String? = env["DOCUCONF_FILE_ROOT"],
    clock: Clock = Clock.systemUTC(),
): ContractFirst.Result {
    val inputs = ContractInputs(env, fileRoot, clock, Docuconf::class.java.classLoader)
    return ContractFirst.check(contract, env, inputs)
}

/** Like [checkContract], but returns the values or throws [ConfigViolationException] listing every violation. */
public fun Docuconf.loadContract(
    contract: Contract,
    env: Map<String, String> = System.getenv(),
    fileRoot: String? = env["DOCUCONF_FILE_ROOT"],
    clock: Clock = Clock.systemUTC(),
): ContractValues = when (val r = checkContract(contract, env, fileRoot, clock)) {
    is ContractFirst.Result.Success -> r.values
    is ContractFirst.Result.Failure -> throw ConfigViolationException(r.violations)
}
