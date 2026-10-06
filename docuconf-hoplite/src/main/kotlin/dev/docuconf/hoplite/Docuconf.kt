package dev.docuconf.hoplite

import com.sksamuel.hoplite.ArrayNode
import com.sksamuel.hoplite.BooleanNode
import com.sksamuel.hoplite.ConfigException
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.DoubleNode
import com.sksamuel.hoplite.LongNode
import com.sksamuel.hoplite.MapNode
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.StringNode
import com.sksamuel.hoplite.Undefined
import com.sksamuel.hoplite.NullNode
import com.sksamuel.hoplite.addPathSource
import com.sksamuel.hoplite.addResourceOrFileSource
import com.sksamuel.hoplite.fp.getOrElse
import com.sksamuel.hoplite.sources.EnvironmentVariablesPropertySource
import com.sksamuel.hoplite.sources.MapPropertySource
import com.sksamuel.hoplite.transformer.PathNormalizer
import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.ConfigViolationException
import dev.docuconf.kotlin.core.Contract
import dev.docuconf.kotlin.core.CueWriter
import dev.docuconf.kotlin.core.DeclarationChecks
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.Durations
import dev.docuconf.kotlin.core.FileType
import dev.docuconf.kotlin.core.Generator
import dev.docuconf.kotlin.core.JsonSyntaxException
import dev.docuconf.kotlin.core.JsonValue
import dev.docuconf.kotlin.core.ListItems
import dev.docuconf.kotlin.core.MarkdownWriter
import dev.docuconf.kotlin.core.ValueChecks
import dev.docuconf.kotlin.core.VarSpec
import dev.docuconf.kotlin.core.VarType
import dev.docuconf.kotlin.core.Violation
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/** Options for [Docuconf.load], [Docuconf.check] and [Docuconf.contract]. */
public class DocuconfOptions {
    /** The process environment. Replace it in tests. */
    public var env: Map<String, String> = System.getenv()

    /** A `.env` file to read for local development (opt-in). Real environment variables override it. */
    public var dotenv: Path? = null

    /** The prefix of the Hoplite environment source: `APP_` makes `port` read `APP_PORT`. */
    public var prefix: String = ""

    /** Prepended to every absolute file input path. Defaults to `DOCUCONF_FILE_ROOT`. */
    public var fileRoot: String? = null

    /**
     * Where boot violations are written for `kubectl describe pod`. Defaults to
     * `DOCUCONF_TERMINATION_LOG`, else `/dev/termination-log` when it exists.
     */
    public var terminationLog: String? = null

    /**
     * Config files baked into the image, as Hoplite resource-or-file paths (`/application.yaml`).
     * Hoplite reads them below environment variables, and their values are exported as defaults
     * (SPEC §4.4).
     */
    public var baseSources: List<String> = emptyList()

    /** The clock for certificate validity checks. */
    public var clock: Clock = Clock.systemUTC()

    /** Receives warnings: deprecated inputs that are still set, feature-flag-like names. */
    public var warn: (String) -> Unit = { System.err.println("docuconf: warning: $it") }

    internal var hopliteConfig: ConfigLoaderBuilder.() -> Unit = {}

    /**
     * Customises the Hoplite [ConfigLoaderBuilder] (decoders, preprocessors, extra sources). docuconf
     * starts from `defaultWithoutPropertySources()` and adds its own environment source, which treats
     * an empty value as unset. Sources added here rank below environment variables, overlays and base sources.
     */
    public fun hoplite(configure: ConfigLoaderBuilder.() -> Unit) {
        hopliteConfig = configure
    }

    internal fun effectiveEnv(): Map<String, String> {
        val file = dotenv ?: return env
        if (!Files.isRegularFile(file)) return env
        return Dotenv.parse(Files.readString(file)) + env
    }
}

/** The outcome of [Docuconf.check]. */
public sealed class LoadResult<out T : Any> {
    public abstract val warnings: List<String>

    public data class Success<T : Any>(val value: T, override val warnings: List<String>) : LoadResult<T>()

    public data class Failure(val violations: List<Violation>, override val warnings: List<String>) : LoadResult<Nothing>()
}

/**
 * docuconf for Hoplite: export a config class as a contract, and load it at boot with every
 * violation reported together.
 */
public object Docuconf {
    /** This library's version, recorded in `metadata.generator`. */
    public const val VERSION: String = "0.1.0"

    /** The SDK name recorded in `metadata.generator`. */
    public const val SDK: String = "docuconf-hoplite"

    private val declarations = ConcurrentHashMap<Pair<KClass<*>, String>, Declaration>()

    /** Loads [T] from the environment and files, or throws [ConfigViolationException] listing every problem. */
    public inline fun <reified T : Any> load(noinline configure: DocuconfOptions.() -> Unit = {}): T =
        load(T::class, DocuconfOptions().apply(configure))

    /** Loads [type], or throws [ConfigViolationException] after writing the violations to the termination log. */
    public fun <T : Any> load(type: KClass<T>, options: DocuconfOptions = DocuconfOptions()): T =
        when (val r = check(type, options)) {
            is LoadResult.Success -> r.value
            is LoadResult.Failure -> {
                val e = ConfigViolationException(r.violations)
                writeTerminationLog(options, e.message!!)
                throw e
            }
        }

    /** Checks and loads [type] without throwing for configuration problems. Declaration errors still throw. */
    public fun <T : Any> check(type: KClass<T>, options: DocuconfOptions = DocuconfOptions()): LoadResult<T> {
        val env = options.effectiveEnv()
        val decl = declaration(type, options.prefix)
        val vars = effectiveVars(decl, options)
        val contract = Contract("check", Generator("kotlin", SDK, VERSION), vars.map { it.spec }, decl.files.map { it.spec }, overlays = decl.overlays)
        val warnings = ArrayList(DeclarationChecks.require(contract) + decl.warnings)
        val classLoader = type.java.classLoader ?: Docuconf::class.java.classLoader
        val fileRoot = options.fileRoot ?: env["DOCUCONF_FILE_ROOT"]

        val violations = ArrayList<Violation>()
        // Overlays (SPEC §4.7): their values are checked like env values, and Hoplite layers them
        // between the base files and the environment.
        val overlayPaths = decl.overlays.associateWith { Overlays.resolve(it, fileRoot) }
        Overlays.checkDirs(decl.overlays, { overlayPaths.getValue(it) }, Overlays.shippedDirs(type, options.baseSources, classLoader))
        val overlays = decl.overlays.map { Overlays.load(it, overlayPaths.getValue(it), classLoader, violations) }

        val hostOptions = ValueChecks.Options(trimListItems = true, hostDuration = ::hopliteDuration, lenientBools = setOf("t", "f", "1", "0", "yes", "no"))
        val filteredEnv = HashMap(env)
        for (v in vars) {
            val raw = env[v.spec.name]
            // Hoplite reads sources in order, so the first overlay declared that holds the key wins.
            val fromOverlay = overlays.firstNotNullOfOrNull { o ->
                o.root?.let { Overlays.at(it, v.path) }?.takeIf { it !is Undefined && it !is NullNode }?.let { o to it }
            }
            val unset = ValueChecks.isUnset(v.spec, raw)
            if (unset) {
                // Hoplite would fail to parse "" for a non-string type; unset lets the overlay or default apply.
                filteredEnv.remove(v.spec.name)
            }
            if (!unset || fromOverlay != null) {
                v.spec.deprecated?.let { d -> warnings += "${v.spec.name} is deprecated: ${d.message}" + (d.replacedBy?.let { r -> " Use $r." } ?: "") }
            }
            when {
                fromOverlay != null && (unset || v.spec.secret) -> violations += Overlays.check(v, fromOverlay.second, fromOverlay.first.spec, hostOptions)
                else -> {
                    if (fromOverlay != null) warnings += "${v.spec.name} is set in the environment and in overlay ${fromOverlay.first.spec.name}; the environment wins"
                    violations += ValueChecks.check(v.spec, raw, hostOptions)
                }
            }
        }

        for (f in decl.files) {
            val format = f.spec.format
            if (f.spec.type == FileType.CONFIG && format != null && parserFor(format, classLoader) == null) {
                throw DeclarationException(listOf("file ${f.spec.name}: no Hoplite parser for ${format.wire} on the classpath; add ${parserModule(format)}"))
            }
        }
        val files = FileLoader(env, fileRoot, options.clock, classLoader, options.hopliteConfig)
        for (f in decl.files) {
            if (f.spec.deprecated != null && Files.exists(files.resolve(f.spec))) warnings += "file ${f.spec.name} is deprecated: ${f.spec.deprecated!!.message}"
            files.load(f)
        }
        violations += files.violations
        if (violations.isNotEmpty()) return LoadResult.Failure(violations, warnings).also { warnings.forEach(options.warn) }

        val markers = decl.files.filter { it.spec.name in files.loaded }.associate { it.path.joinToString(".") to FILE_MARKER + it.spec.name }
        val prefix = options.prefix.ifEmpty { null }
        val value = try {
            ConfigLoaderBuilder.defaultWithoutPropertySources()
                .addDecoder(KotlinDurationDecoder())
                .addDecoder(JsonVarDecoder())
                .addDecoder(FileInputDecoder(files.loaded))
                .addPropertySource(MapPropertySource(markers))
                .addPropertySource(EnvironmentVariablesPropertySource({ filteredEnv }, prefix))
                // Hoplite: earlier sources win. So: environment > overlays > base files (SPEC §4.7).
                .apply { overlays.forEach { addPathSource(it.path, optional = true, allowEmpty = true) } }
                .apply { options.baseSources.forEach { addResourceOrFileSource(it) } }
                .apply(options.hopliteConfig)
                .build()
                .loadConfigOrThrow(type, emptyList())
        } catch (e: ConfigException) {
            // Pre-checks should have caught this. Report Hoplite's reason with secret values removed.
            var message = e.message ?: "Hoplite could not bind ${type.simpleName}"
            for (v in vars) if (v.spec.secret) env[v.spec.name]?.takeIf { it.isNotEmpty() }?.let { message = message.replace(it, "****") }
            val failure = LoadResult.Failure(listOf(Violation(Codes.INVALID_TYPE, type.simpleName ?: "config", message.trim())), warnings)
            warnings.forEach(options.warn)
            return failure
        }
        warnings.forEach(options.warn)
        return LoadResult.Success(value, warnings)
    }

    /** The contract for [type]. Throws [DeclarationException] when the declaration is invalid. */
    public fun contract(type: KClass<*>, service: String, appVersion: String? = null, configure: DocuconfOptions.() -> Unit = {}): Contract {
        val options = DocuconfOptions().apply(configure)
        val decl = declaration(type, options.prefix)
        val contract = Contract(
            service = service,
            generator = Generator("kotlin", SDK, VERSION),
            vars = effectiveVars(decl, options).map { it.spec },
            files = decl.files.map { it.spec },
            appVersion = appVersion,
            overlays = decl.overlays,
        )
        val classLoader = type.java.classLoader ?: Docuconf::class.java.classLoader
        Overlays.checkDirs(decl.overlays, { Path.of(it.path) }, Overlays.shippedDirs(type, options.baseSources, classLoader))
        (DeclarationChecks.require(contract) + decl.warnings).forEach(options.warn)
        return contract
    }

    /** `contract.cue` for [type] (SPEC §4). Deterministic. */
    public fun exportCue(type: KClass<*>, service: String, appVersion: String? = null, configure: DocuconfOptions.() -> Unit = {}): String =
        CueWriter.write(contract(type, service, appVersion, configure))

    /** Markdown documentation of every input of [type]. */
    public fun exportMarkdown(type: KClass<*>, service: String, appVersion: String? = null, configure: DocuconfOptions.() -> Unit = {}): String =
        MarkdownWriter.write(contract(type, service, appVersion, configure))

    internal fun declaration(type: KClass<*>, prefix: String): Declaration =
        declarations.getOrPut(type to prefix) { DeclarationReader.read(type, prefix) }

    /** Variables with defaults from base config files applied (SPEC §4.4). */
    private fun effectiveVars(decl: Declaration, options: DocuconfOptions): List<VarBinding> {
        if (options.baseSources.isEmpty()) return decl.vars
        val root = ConfigLoaderBuilder.defaultWithoutPropertySources()
            .apply(options.hopliteConfig)
            .build()
            .loadNode(options.baseSources)
            .getOrElse { throw DeclarationException(listOf("base sources ${options.baseSources}: ${it.description()}")) }
        val errors = ArrayList<String>()
        val result = decl.vars.map { v ->
            val node = v.path.fold(root) { n, seg -> lookup(n, seg) }
            if (node is Undefined) return@map v
            if (v.spec.secret) {
                errors += "${v.spec.name}: a secret cannot have a value in a config file baked into the image (${options.baseSources.joinToString()})"
                return@map v
            }
            val value = try {
                baseValue(v.spec, node)
            } catch (e: IllegalArgumentException) {
                errors += "${v.spec.name}: the value in ${options.baseSources.joinToString()} ${e.message}"
                return@map v
            }
            v.copy(spec = v.spec.copy(default = value, required = false))
        }
        if (errors.isNotEmpty()) throw DeclarationException(errors)
        return result
    }

    private fun lookup(n: Node, segment: String): Node {
        if (n !is MapNode) return Undefined
        val direct = n.atKey(segment)
        return if (direct !is Undefined) direct else n.atKey(PathNormalizer.transformPathElement(segment))
    }

    private fun baseValue(spec: VarSpec, node: Node): JsonValue {
        fun bad(): Nothing = throw IllegalArgumentException("is not a valid ${spec.type.wire}")
        fun scalar(): String = when (node) {
            is StringNode -> node.value
            is LongNode -> node.value.toString()
            is DoubleNode -> node.value.toString()
            is BooleanNode -> node.value.toString()
            else -> bad()
        }
        return when (spec.type) {
            VarType.STRING, VarType.URL, VarType.ENUM -> JsonValue.Str(scalar())
            VarType.INT -> JsonValue.Int(scalar().toLongOrNull() ?: bad())
            VarType.FLOAT -> JsonValue.Float(scalar().toDoubleOrNull()?.takeIf { it.isFinite() } ?: bad())
            VarType.BOOL -> when (scalar().lowercase()) {
                "true", "yes", "t", "1" -> JsonValue.Bool(true)
                "false", "no", "f", "0" -> JsonValue.Bool(false)
                else -> bad()
            }
            VarType.DURATION -> JsonValue.Str(Durations.formatGo(hopliteDuration(scalar())?.takeIf { it >= 0 } ?: bad()))
            VarType.LIST -> {
                val items = if (node is ArrayNode) node.elements.map { (it as? StringNode)?.value ?: toJson(it).toString() } else scalar().split(",").map { it.trim() }
                JsonValue.Arr(items.map { if (spec.items == ListItems.INT) JsonValue.Int(it.toLongOrNull() ?: bad()) else JsonValue.Str(it) })
            }
            VarType.JSON -> if (node is StringNode) {
                try {
                    JsonValue.parse(node.value)
                } catch (_: JsonSyntaxException) {
                    bad()
                }
            } else {
                toJson(node)
            }
        }
    }

    private fun writeTerminationLog(options: DocuconfOptions, message: String) {
        val explicit = options.terminationLog ?: options.env["DOCUCONF_TERMINATION_LOG"]?.takeIf { it.isNotEmpty() }
        val path = explicit?.let { Path.of(it) } ?: Path.of("/dev/termination-log").takeIf { Files.exists(it) } ?: return
        try {
            Files.writeString(path, message + "\n")
        } catch (_: Exception) {
            // Best effort: the exception still carries the message.
        }
    }
}

/** A minimal `.env` reader for local development: `KEY=value`, `#` comments, optional quotes. */
internal object Dotenv {
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val body = t.removePrefix("export ").trimStart()
            val eq = body.indexOf('=')
            if (eq <= 0) continue
            val key = body.substring(0, eq).trim()
            var value = body.substring(eq + 1)
            if (value.length >= 2 && ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\'')))) {
                value = value.substring(1, value.length - 1)
                if (body[eq + 1] == '"') value = value.replace("\\n", "\n").replace("\\\"", "\"")
            }
            out[key] = value
        }
        return out
    }
}
