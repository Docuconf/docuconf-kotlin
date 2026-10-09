package dev.docuconf.hoplite

import com.sksamuel.hoplite.ArrayNode
import com.sksamuel.hoplite.BooleanNode
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
import com.sksamuel.hoplite.sources.MapPropertySource
import com.sksamuel.hoplite.transformer.PathNormalizer
import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.ConfigViolationException
import dev.docuconf.kotlin.core.Contract
import dev.docuconf.kotlin.core.ContractFirst
import dev.docuconf.kotlin.core.CueWriter
import dev.docuconf.kotlin.core.DeclarationChecks
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.Durations
import dev.docuconf.kotlin.core.FileType
import dev.docuconf.kotlin.core.Generator
import dev.docuconf.kotlin.core.JsonSyntaxException
import dev.docuconf.kotlin.core.JsonValue
import dev.docuconf.kotlin.core.ListEncoding
import dev.docuconf.kotlin.core.ListItems
import dev.docuconf.kotlin.core.MarkdownWriter
import dev.docuconf.kotlin.core.ValueChecks
import dev.docuconf.kotlin.core.VarSpec
import dev.docuconf.kotlin.core.VarType
import dev.docuconf.kotlin.core.Violation
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.system.exitProcess

/** Options for [Docuconf.load], [Docuconf.check], `withDocuconf()` and [Docuconf.contract]. */
public class DocuconfOptions {
    /** The process environment. Replace it in tests: docuconf never reads or changes the real one then. */
    public var env: Map<String, String> = System.getenv()

    /** A `.env` file to read for local development (opt-in). Real environment variables override it. */
    public var dotenv: Path? = null

    /** Prepended to every absolute file input path. Defaults to `DOCUCONF_FILE_ROOT`. */
    public var fileRoot: String? = null

    /**
     * Where boot violations are written for `kubectl describe pod`. Defaults to
     * `DOCUCONF_TERMINATION_LOG`, else `/dev/termination-log` when it exists.
     */
    public var terminationLog: String? = null

    /** The clock for certificate validity checks. */
    public var clock: Clock = Clock.systemUTC()

    /**
     * Receives warnings: deprecated inputs that are still set, feature-flag-like names, likely typos,
     * and changes to a [Watched] file that failed their checks (the previous content is kept).
     */
    public var warn: (String) -> Unit = { System.err.println("docuconf: $it") }

    /**
     * How often a [Watched] file input checks whether its file changed, at most: one second by
     * default. It checks when the app reads it, never in the background. [Duration.ZERO] checks at
     * every read.
     */
    public var reloadInterval: Duration = Duration.ofSeconds(1)

    // Tests only: the prefix and base sources come from @DocuconfService, so load and export agree.
    internal var prefix: String? = null
    internal var baseSources: List<String>? = null

    internal val hopliteConfigs = ArrayList<ConfigLoaderBuilder.() -> Unit>()

    /**
     * Customises the Hoplite [ConfigLoaderBuilder] (decoders, preprocessors, extra sources). Each call
     * adds a block; they run in order. docuconf starts from `defaultWithoutPropertySources()` and adds
     * its own environment source. Sources added here rank below environment variables, overlays and
     * base sources. To start from a builder of your own, use `withDocuconf()` instead.
     */
    public fun hoplite(configure: ConfigLoaderBuilder.() -> Unit) {
        hopliteConfigs += configure
    }

    internal val hopliteConfig: ConfigLoaderBuilder.() -> Unit get() = { hopliteConfigs.forEach { it(this) } }

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

/** The prefix and base sources of a config class: [DocuconfService], unless a test overrides them. */
internal class ServiceSettings(val name: String?, val prefix: String, val baseSources: List<String>) {
    companion object {
        fun of(type: KClass<*>, options: DocuconfOptions): ServiceSettings {
            val a = type.findAnnotation<DocuconfService>()
            return ServiceSettings(
                name = a?.name?.ifEmpty { null },
                prefix = options.prefix ?: a?.prefix ?: "",
                baseSources = options.baseSources ?: a?.baseSources?.toList() ?: emptyList(),
            )
        }
    }
}

/**
 * docuconf for Hoplite: export a config class as a contract, and load it at boot with every
 * violation reported together.
 */
public object Docuconf {
    /** This library's version, recorded in `metadata.generator`. */
    public const val VERSION: String = "0.2.0" // x-release-please-version

    /** The SDK name recorded in `metadata.generator`. */
    public const val SDK: String = "docuconf-hoplite"

    private val declarations = ConcurrentHashMap<Pair<KClass<*>, String>, Declaration>()

    /** Loads [T] from the environment and files, or throws [ConfigViolationException] listing every problem. */
    public inline fun <reified T : Any> load(noinline configure: DocuconfOptions.() -> Unit = {}): T =
        load(T::class, DocuconfOptions().apply(configure))

    /** Loads [type], or throws [ConfigViolationException] after writing the violations to the termination log. */
    public fun <T : Any> load(type: KClass<T>, options: DocuconfOptions = DocuconfOptions()): T = load(type, options, null)

    /**
     * Loads [T], or prints every problem to stderr, writes them to the termination log and exits with
     * status 1. No stack trace. Use it in `main`:
     *
     * ```
     * docuconf: 2 configuration problems:
     *   PORT: out_of_range: "0" is below min 1
     *   DATABASE_URL: missing_required: required, but not set
     * ```
     */
    public inline fun <reified T : Any> loadOrExit(noinline configure: DocuconfOptions.() -> Unit = {}): T =
        loadOrExit(T::class, DocuconfOptions().apply(configure))

    /** Loads [type], or prints every problem, writes the termination log and exits with status 1. */
    public fun <T : Any> loadOrExit(type: KClass<T>, options: DocuconfOptions = DocuconfOptions()): T = loadOrExit(type, options, null)

    internal fun <T : Any> load(type: KClass<T>, options: DocuconfOptions, builder: ConfigLoaderBuilder?): T =
        when (val r = check(type, options, builder)) {
            is LoadResult.Success -> r.value
            is LoadResult.Failure -> {
                val e = ConfigViolationException(r.violations)
                writeTerminationLog(options, e.message!!)
                throw e
            }
        }

    internal fun <T : Any> loadOrExit(
        type: KClass<T>,
        options: DocuconfOptions,
        builder: ConfigLoaderBuilder?,
        err: PrintStream = System.err,
        exit: (Int) -> Nothing = { exitProcess(it) },
    ): T = try {
        load(type, options, builder)
    } catch (e: ConfigViolationException) {
        err.println(e.message)
        exit(1)
    } catch (e: DeclarationException) {
        // A programming error, but at boot it is still reported cleanly.
        val message = "docuconf: " + e.message
        writeTerminationLog(options, message)
        err.println(message)
        exit(1)
    }

    /** Checks and loads [type] without throwing for configuration problems. Declaration errors still throw. */
    public fun <T : Any> check(type: KClass<T>, options: DocuconfOptions = DocuconfOptions()): LoadResult<T> = check(type, options, null)

    internal fun <T : Any> check(type: KClass<T>, options: DocuconfOptions, userBuilder: ConfigLoaderBuilder?): LoadResult<T> {
        val env = options.effectiveEnv()
        val settings = ServiceSettings.of(type, options)
        val decl = declaration(type, settings.prefix)
        val vars = effectiveVars(decl, settings, options.hopliteConfig)
        val contract = Contract("check", Generator("kotlin", SDK, VERSION), vars.map { it.spec }, decl.files.map { it.spec }, overlays = decl.overlays)
        val warnings = ArrayList(DeclarationChecks.require(contract) + decl.warnings)
        val classLoader = type.java.classLoader ?: Docuconf::class.java.classLoader
        val fileRoot = options.fileRoot ?: env["DOCUCONF_FILE_ROOT"]

        val violations = ArrayList<Violation>()
        // Overlays (SPEC §4.7): their values are checked like env values, and Hoplite layers them
        // between the base files and the environment.
        val overlayPaths = decl.overlays.associateWith { Overlays.resolve(it, fileRoot) }
        Overlays.checkDirs(decl.overlays, { overlayPaths.getValue(it) }, Overlays.shippedDirs(type, settings.baseSources, classLoader))
        val overlays = decl.overlays.map { Overlays.load(it, overlayPaths.getValue(it), classLoader, violations) }

        val typos = Typos.find(env, contract, settings.prefix)
        typos.forEach { (set, declared) -> warnings += "$set is set but not declared; did you mean $declared?" }

        // The values docuconf hands Hoplite, already parsed and keyed by property path (db.poolSize),
        // so Hoplite's own environment naming, and its more lenient parsing, never apply (SPEC §5).
        val values = LinkedHashMap<List<String>, Node>()
        for (v in vars) {
            val raw = env[v.spec.name]
            // Hoplite reads sources in order, so the first overlay declared that holds the key wins.
            val fromOverlay = overlays.firstNotNullOfOrNull { o ->
                o.root?.let { Overlays.at(it, v.path) }?.takeIf { it !is Undefined && it !is NullNode }?.let { o to it }
            }
            // Unset (absent, or empty for any type but string) lets the overlay or default apply.
            val unset = ValueChecks.isUnset(v.spec, raw)
            if (!unset || fromOverlay != null) {
                v.spec.deprecated?.let { d -> warnings += ContractFirst.deprecationWarning(v.spec.name, d) }
            }
            val parsed = when {
                fromOverlay != null && (unset || v.spec.secret) -> Overlays.check(v, fromOverlay.second, fromOverlay.first.spec)
                else -> {
                    if (fromOverlay != null) warnings += "${v.spec.name} is set in the environment and in overlay ${fromOverlay.first.spec.name}; the environment wins"
                    ValueChecks.parse(v.spec, raw)
                }
            }
            val found = parsed.violations
            parsed.value?.let { values[v.path] = valueNode(v.spec, it) }
            violations += found.map { f ->
                val typo = typos.entries.firstOrNull { it.value == f.input }?.key
                if (f.code == Codes.MISSING_REQUIRED && typo != null) f.copy(message = "${f.message} ($typo is set; a typo?)") else f
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
            if (f.spec.deprecated != null && Files.exists(files.resolve(f.spec))) warnings += ContractFirst.deprecationWarning("file ${f.spec.name}", f.spec.deprecated!!)
            files.load(f)
        }
        violations += files.violations
        if (violations.isNotEmpty()) return LoadResult.Failure(violations, warnings).also { warnings.forEach(options.warn) }
        for (f in decl.files) {
            if (f.watched) files.loaded[f.spec.name]?.let { files.loaded[f.spec.name] = watch(f, it, files, env, fileRoot, classLoader, options) }
        }

        val markers = decl.files.filter { it.spec.name in files.loaded }.associate { it.path.joinToString(".") to FILE_MARKER + it.spec.name }
        val loader = HopliteLoader.build(
            type = type,
            userBuilder = userBuilder,
            hopliteConfig = options.hopliteConfig,
            // Hoplite: earlier sources win. So: environment > overlays > base files (SPEC §4.7).
            front = listOf(MapPropertySource(markers), NodeSource(values)),
            files = { overlays.forEach { addPathSource(it.path, optional = true, allowEmpty = true) }; settings.baseSources.forEach { addResourceOrFileSource(it) } },
            decoders = valueDecoders() + FileInputDecoder(files.loaded),
        )
        if (userBuilder != null) HopliteLoader.checkUserFiles(loader.user, vars, settings)
        // A secret list's and key set's items too, longest first, so an error that quotes one item never shows it.
        val secrets = vars.filter { it.spec.secret }.flatMap { v ->
            val raw = env[v.spec.name]?.takeIf { it.isNotEmpty() } ?: return@flatMap emptyList()
            val items = if (v.spec.type == VarType.LIST || v.spec.type == VarType.KEY_SET) raw.split(v.spec.separator).filter { it.trim().length >= 4 } else emptyList()
            listOf(raw) + items
        }.distinct().sortedByDescending { it.length }
        val bound = try {
            HopliteLoader.bind(loader.loader, type, classLoader)
        } catch (e: Exception) {
            // Hoplite reports most problems as failures; anything thrown (a user's init block) is reported too.
            val failure = LoadResult.Failure(listOf(Violation(Codes.INVALID_TYPE, type.simpleName ?: "config", redact(e.message ?: e.toString(), secrets))), warnings)
            warnings.forEach(options.warn)
            return failure
        }
        warnings.forEach(options.warn)
        return bound.fold(
            { failure -> LoadResult.Failure(HopliteFailures.violations(failure, type, vars, secrets), warnings) },
            { value -> LoadResult.Success(value, warnings) },
        )
    }

    /**
     * The contract for [type]. [service] defaults to [DocuconfService.name]. Throws
     * [DeclarationException] when the declaration is invalid.
     */
    public fun contract(type: KClass<*>, service: String? = null, appVersion: String? = null, configure: DocuconfOptions.() -> Unit = {}): Contract {
        val options = DocuconfOptions().apply(configure)
        val settings = ServiceSettings.of(type, options)
        val name = service ?: settings.name
            ?: throw DeclarationException(listOf("${type.simpleName}: no service name; annotate the class @DocuconfService(name = \"...\") or pass one"))
        if (service != null && settings.name != null && service != settings.name) {
            throw DeclarationException(listOf("${type.simpleName}: service \"$service\" differs from @DocuconfService(name = \"${settings.name}\"); set it in one place"))
        }
        val decl = declaration(type, settings.prefix)
        val contract = Contract(
            service = name,
            generator = Generator("kotlin", SDK, VERSION),
            vars = effectiveVars(decl, settings, options.hopliteConfig).map { it.spec },
            files = decl.files.map { it.spec },
            appVersion = appVersion,
            overlays = decl.overlays,
        )
        val classLoader = type.java.classLoader ?: Docuconf::class.java.classLoader
        Overlays.checkDirs(decl.overlays, { Path.of(it.path) }, Overlays.shippedDirs(type, settings.baseSources, classLoader))
        (DeclarationChecks.require(contract) + decl.warnings).forEach(options.warn)
        return contract
    }

    /** `contract.cue` for [type] (SPEC §4). Deterministic. */
    public fun exportCue(type: KClass<*>, service: String? = null, appVersion: String? = null, configure: DocuconfOptions.() -> Unit = {}): String =
        CueWriter.write(contract(type, service, appVersion, configure))

    /**
     * [cue], a contract as [exportCue] writes it, with the value of `metadata.generator.version` replaced by a
     * placeholder and nothing else changed. That value is [VERSION], which changes with every release, so the
     * checks that a committed contract is current compare through this: `Export --check`, the Gradle plugin's
     * `docuconfCheck` and the README's unit test. Any other difference still fails them.
     */
    public fun withoutGeneratorVersion(cue: String): String {
        var inMetadata = false
        return cue.split('\n').joinToString("\n") { line ->
            when (line.removeSuffix("\r")) {
                "\tmetadata: {" -> line.also { inMetadata = true }
                "\t}" -> line.also { inMetadata = false }
                else -> if (inMetadata) GENERATOR_VERSION.replace(line) { it.groupValues[1] + "\"<generator-version>\"" + it.groupValues[2] } else line
            }
        }
    }

    /** The `generator` line of `metadata`, as CueWriter writes it: group 1 is up to the version, group 2 after it. */
    private val GENERATOR_VERSION =
        Regex("""^(\t\tgenerator: \{language: "(?:[^"\\]|\\.)*", sdk: "(?:[^"\\]|\\.)*", version: )"(?:[^"\\]|\\.)*"(\}\r?)$""")

    /** Markdown documentation of every input of [type]. */
    public fun exportMarkdown(type: KClass<*>, service: String? = null, appVersion: String? = null, configure: DocuconfOptions.() -> Unit = {}): String =
        MarkdownWriter.write(contract(type, service, appVersion, configure))

    /**
     * [initial], the file of [f] loaded at boot, as a [Watched] that reruns the boot checks with a
     * loader of its own when the file changes.
     */
    private fun watch(
        f: FileBinding,
        initial: Any,
        boot: FileLoader,
        env: Map<String, String>,
        fileRoot: String?,
        classLoader: ClassLoader,
        options: DocuconfOptions,
    ): Watched<Any> {
        val path = boot.resolve(f.spec)
        val paths = if (f.spec.type == FileType.TLS) listOf("tls.crt", "tls.key", "ca.crt").map { path.resolve(it) } else listOf(path)
        val reloader = Reloader(f.spec.name, paths, options.reloadInterval, options.warn) {
            val loader = FileLoader(env, fileRoot, options.clock, classLoader, options.hopliteConfig)
            loader.load(f)
            loader.loaded[f.spec.name] to loader.violations.toList()
        }
        return Watched(initial, reloader)
    }

    internal fun redact(message: String, secrets: List<String>): String = secrets.fold(message) { m, s -> m.replace(s, "****") }

    internal fun declaration(type: KClass<*>, prefix: String): Declaration =
        declarations.getOrPut(type to prefix) { DeclarationReader.read(type, prefix) }

    /** Variables with defaults from base config files applied (SPEC §4.4). */
    internal fun effectiveVars(decl: Declaration, settings: ServiceSettings, hopliteConfig: ConfigLoaderBuilder.() -> Unit): List<VarBinding> {
        if (settings.baseSources.isEmpty()) return decl.vars
        val root = ConfigLoaderBuilder.defaultWithoutPropertySources()
            .explicitSealedTypes()
            .apply(hopliteConfig)
            .build()
            .loadNode(settings.baseSources)
            .getOrElse { throw DeclarationException(listOf("base sources ${settings.baseSources}: ${it.description()}")) }
        return withDefaults(decl.vars, root, settings.baseSources.joinToString())
    }

    /** [vars] with the values in [root] (a config file tree) as defaults; a secret there is an error. */
    internal fun withDefaults(vars: List<VarBinding>, root: Node, where: String): List<VarBinding> {
        val errors = ArrayList<String>()
        val result = vars.map { v ->
            val node = v.path.fold(root) { n, seg -> lookup(n, seg) }
            if (node is Undefined) return@map v
            if (v.spec.secret) {
                errors += "${v.spec.name}: a secret cannot have a value in a config file baked into the image ($where)"
                return@map v
            }
            val value = try {
                baseValue(v.spec, node)
            } catch (e: IllegalArgumentException) {
                errors += "${v.spec.name}: the value in $where ${e.message}"
                return@map v
            }
            v.copy(spec = v.spec.copy(default = value, required = false))
        }
        if (errors.isNotEmpty()) throw DeclarationException(errors)
        return result
    }

    internal fun lookup(n: Node, segment: String): Node {
        if (n !is MapNode) return Undefined
        val direct = n.atKey(segment)
        return if (direct !is Undefined) direct else n.atKey(PathNormalizer.transformPathElement(segment))
    }

    internal fun baseValue(spec: VarSpec, node: Node): JsonValue {
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
            // A key set is secret, so a base file never holds one (withDefaults rejects it first).
            VarType.KEY_SET -> bad()
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

    internal fun writeTerminationLog(options: DocuconfOptions, message: String) {
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
