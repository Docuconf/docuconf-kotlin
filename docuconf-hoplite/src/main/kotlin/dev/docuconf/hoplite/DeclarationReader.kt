package dev.docuconf.hoplite

import com.sksamuel.hoplite.ConfigAlias
import com.sksamuel.hoplite.Secret
import dev.docuconf.kotlin.core.ConfigFormat
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.Deprecation
import dev.docuconf.kotlin.core.DurationEncoding
import dev.docuconf.kotlin.core.FileSpec
import dev.docuconf.kotlin.core.FileType
import dev.docuconf.kotlin.core.JsonValue
import dev.docuconf.kotlin.core.KeystoreFormat
import dev.docuconf.kotlin.core.ListEncoding
import dev.docuconf.kotlin.core.ListItems
import dev.docuconf.kotlin.core.OverlaySpec
import dev.docuconf.kotlin.core.Reload
import dev.docuconf.kotlin.core.VarSpec
import dev.docuconf.kotlin.core.VarType
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.security.PrivateKey
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotations
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/** A variable and where Hoplite binds it in the config class tree. */
internal data class VarBinding(val spec: VarSpec, val path: List<String>, val type: KType)

/** A file input and where it is bound. [valueType] is `T` of `ConfigFile<T>`. */
internal data class FileBinding(val spec: FileSpec, val path: List<String>, val type: KType, val valueType: KType?)

/** Everything docuconf reads from a config class. */
internal data class Declaration(
    val root: KClass<*>,
    val vars: List<VarBinding>,
    val files: List<FileBinding>,
    val overlays: List<OverlaySpec>,
    val warnings: List<String>,
)

/**
 * Reads a Hoplite config class into contract inputs.
 *
 * Environment variable names follow Hoplite 3's environment source: `_` separates nesting levels,
 * and within one level Hoplite compares names lower-cased with `_` and `-` removed. So property
 * `port` reads `PORT`, `httpPort` reads `HTTPPORT`, and `db.url` (a nested `db` class) reads `DB_URL`.
 * `HTTP_PORT` would be read as `http.port`, never as `httpPort`.
 */
internal object DeclarationReader {
    private val fileTypes = setOf(ConfigFile::class, TlsKeyPair::class, CaBundle::class, Keystore::class, TextFile::class, BinaryFile::class)

    fun read(root: KClass<*>, prefix: String = ""): Declaration {
        val r = Reader(root, prefix)
        r.walk(root, emptyList(), parentOptional = false, inheritedGroup = null)
        val overlays = root.findAnnotations(ConfigOverlay::class).map { r.overlay(it) }
        if (r.errors.isNotEmpty()) throw DeclarationException(r.errors)
        return Declaration(root, r.vars, r.files, overlays, r.warnings)
    }

    /** Hoplite nests config file keys as maps; the contract writes them joined with this separator. */
    const val KEY_SEPARATOR = "."

    /** The env segment Hoplite matches a property name against. */
    fun envSegment(name: String): String = name.replace("_", "").replace("-", "").uppercase()

    private class Reader(val root: KClass<*>, val prefix: String) {
        val vars = ArrayList<VarBinding>()
        val files = ArrayList<FileBinding>()
        val warnings = ArrayList<String>()
        val errors = ArrayList<String>()

        fun walk(k: KClass<*>, path: List<String>, parentOptional: Boolean, inheritedGroup: String?) {
            val ctor = k.primaryConstructor
            if (ctor == null) {
                errors += "${where(path)}: ${k.simpleName} has no primary constructor"
                return
            }
            val defaults = defaultsOf(k, path)
            for (p in ctor.parameters) {
                val name = p.name ?: continue
                val here = path + name
                if (p.annotations.any { it is NotInContract }) continue
                if (p.annotations.any { it is ConfigAlias }) {
                    warnings += "${where(here)}: @ConfigAlias names are not exported; the contract uses ${envName(here)}"
                }
                val t = p.type
                val kc = t.classifier as? KClass<*>
                if (kc == null) {
                    errors += "${where(here)}: unsupported type $t"
                    continue
                }
                val optional = parentOptional || p.isOptional || t.isMarkedNullable
                val group = p.annotations.filterIsInstance<Group>().firstOrNull()?.value ?: inheritedGroup
                when {
                    kc in fileTypes -> fileInput(p, kc, here, optional, group)
                    isScalar(kc, t) -> variable(p, kc, here, optional, group, defaults)
                    kc == Map::class -> warnings += "${where(here)}: maps cannot be set by the platform through environment variables; left out of the contract (file-only)"
                    kc.isData || kc.primaryConstructor != null && !kc.java.isInterface && kc.java.`package`?.name?.startsWith("java.") != true ->
                        walk(kc, here, optional, group)
                    else -> errors += "${where(here)}: type ${kc.qualifiedName} is not supported by docuconf; annotate it @NotInContract"
                }
            }
        }

        fun overlay(a: ConfigOverlay): OverlaySpec {
            val p = "${root.simpleName}: @ConfigOverlay(name = \"${a.name}\")"
            val format = when (a.path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
                "json" -> ConfigFormat.JSON
                "yaml", "yml" -> ConfigFormat.YAML
                "toml" -> ConfigFormat.TOML
                else -> {
                    errors += "$p: cannot tell the format of ${a.path}; overlays must end in .json, .yaml, .yml or .toml"
                    ConfigFormat.YAML
                }
            }
            if (a.reload == Reload.WATCH) {
                // hoplite-watch's ReloadableConfig re-runs Hoplite alone, so a reload would bypass
                // docuconf's checks and file inputs. Rather than export a promise it does not keep
                // (SPEC §11.2 item 8), docuconf rejects watch.
                errors += "$p: reload = WATCH is not supported; docuconf for Hoplite validates configuration once, at boot. " +
                    "Use Reload.RESTART: the platform renders an immutable ConfigMap and rolls the pods on change."
            }
            return OverlaySpec(
                name = a.name,
                format = format,
                path = a.path,
                keySeparator = KEY_SEPARATOR,
                description = a.description.ifEmpty { null },
                reload = a.reload,
            )
        }

        fun envName(path: List<String>) = prefix + path.joinToString("_") { envSegment(it) }

        fun where(path: List<String>) = "${root.simpleName}.${path.joinToString(".")}"

        fun variable(p: KParameter, k: KClass<*>, path: List<String>, optional: Boolean, group: String?, defaults: Map<String, Any?>) {
            val a = p.annotations
            val name = envName(path)
            val doc = a.filterIsInstance<Doc>().firstOrNull()?.value
            if (doc == null) errors += "${where(path)}: add @Doc(\"...\") with a description of at least 5 characters"
            val secret = k == Secret::class
            val length = a.filterIsInstance<Length>().firstOrNull()
            val items = a.filterIsInstance<Items>().firstOrNull()
            val schemes = a.filterIsInstance<Schemes>().firstOrNull()?.value?.toList()
            val oneOf = a.filterIsInstance<OneOf>().firstOrNull()?.value?.toList()
            val isUrl = schemes != null || a.any { it is Url } || k == URI::class || k == URL::class
            var type = when {
                k == String::class || k == Secret::class -> when {
                    isUrl -> VarType.URL
                    oneOf != null -> VarType.ENUM
                    else -> VarType.STRING
                }
                k == URI::class || k == URL::class -> VarType.URL
                k == Int::class || k == Long::class || k == Short::class || k == Byte::class -> VarType.INT
                k == Double::class || k == Float::class -> VarType.FLOAT
                k == Boolean::class -> VarType.BOOL
                k == java.time.Duration::class || k == kotlin.time.Duration::class -> VarType.DURATION
                k.java.isEnum -> VarType.ENUM
                k == List::class || k == Set::class -> VarType.LIST
                k == Json::class -> VarType.JSON
                else -> error("unreachable")
            }
            val values = when {
                k.java.isEnum -> k.java.enumConstants.map { (it as Enum<*>).name }
                oneOf != null -> oneOf
                else -> null
            }
            var listItems: ListItems? = null
            var schema: JsonValue? = null
            val itemClass = p.type.arguments.firstOrNull()?.type?.classifier
            if (type == VarType.LIST) {
                listItems = when (itemClass) {
                    String::class -> ListItems.STRING
                    Int::class, Long::class -> ListItems.INT
                    else -> {
                        errors += "${where(path)}: lists must hold String, Int or Long; other items cannot be set through one environment variable"
                        ListItems.STRING
                    }
                }
            }
            if (type == VarType.JSON) {
                val inner = p.type.arguments.firstOrNull()?.type
                if (inner == null) errors += "${where(path)}: Json needs a concrete type argument" else schema = SchemaGenerator.schema(inner, where(path))
            }
            val (implicitMin, implicitMax) = when (k) {
                Int::class -> Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong()
                Short::class -> Short.MIN_VALUE.toLong() to Short.MAX_VALUE.toLong()
                Byte::class -> Byte.MIN_VALUE.toLong() to Byte.MAX_VALUE.toLong()
                else -> null to null
            }
            val min: JsonValue? = when (type) {
                // A declared bound wider than the type holds is narrowed to the type's range.
                VarType.INT -> (a.filterIsInstance<Min>().firstOrNull()?.value?.let { m -> implicitMin?.let { maxOf(m, it) } ?: m } ?: implicitMin)?.let { JsonValue.Int(it) }
                VarType.FLOAT -> a.filterIsInstance<DecimalMin>().firstOrNull()?.let { JsonValue.Float(it.value) }
                else -> null
            }
            val max: JsonValue? = when (type) {
                VarType.INT -> (a.filterIsInstance<Max>().firstOrNull()?.value?.let { m -> implicitMax?.let { minOf(m, it) } ?: m } ?: implicitMax)?.let { JsonValue.Int(it) }
                VarType.FLOAT -> a.filterIsInstance<DecimalMax>().firstOrNull()?.let { JsonValue.Float(it.value) }
                else -> null
            }
            // Item bounds: the declared ones, else the item type's own range when it is narrower than
            // 64 bits, so the platform never sends an item the app cannot hold (SPEC §5).
            val itemMin = a.filterIsInstance<ItemMin>().firstOrNull()?.value
            val itemMax = a.filterIsInstance<ItemMax>().firstOrNull()?.value
            if ((itemMin != null || itemMax != null) && listItems != ListItems.INT) {
                errors += "${where(path)}: @ItemMin and @ItemMax only apply to List<Int> or List<Long>"
            }
            val intItems = type == VarType.LIST && itemClass == Int::class
            val rawDefault = defaults[p.name]
            val default = if (p.isOptional && rawDefault != null) {
                try {
                    toJsonValue(rawDefault)
                } catch (e: IllegalArgumentException) {
                    errors += "${where(path)}: default ${e.message}"
                    null
                }
            } else {
                null
            }
            if (default != null && type == VarType.DURATION && default is JsonValue.Str && default.value.startsWith("-")) {
                errors += "${where(path)}: negative durations cannot be exported"
            }
            val deprecated = a.filterIsInstance<DeprecatedInput>().firstOrNull()?.let { Deprecation(it.message, it.replacedBy.ifEmpty { null }) }
            // Where Hoplite reads the value in a config file, such as a platform overlay (SPEC §4.7).
            val configKey = path.joinToString(KEY_SEPARATOR)
            if (type == VarType.ENUM && values == null) type = VarType.STRING
            vars += VarBinding(
                VarSpec(
                    name = name,
                    type = type,
                    description = doc ?: "",
                    required = !optional,
                    secret = secret,
                    group = group,
                    examples = a.filterIsInstance<Examples>().firstOrNull()?.values?.toList() ?: emptyList(),
                    configKey = configKey,
                    deprecated = deprecated,
                    default = default,
                    min = min,
                    max = max,
                    minDuration = a.filterIsInstance<DurationMin>().firstOrNull()?.value,
                    maxDuration = a.filterIsInstance<DurationMax>().firstOrNull()?.value,
                    // Hoplite parses java.time.Duration as ISO-8601 (PT1M30S) but not Go's 1m30s; the
                    // kotlin.time.Duration decoder docuconf registers accepts the same.
                    durationEncoding = DurationEncoding.ISO8601,
                    minLength = length?.min?.takeIf { it >= 0 },
                    maxLength = length?.max?.takeIf { it >= 0 },
                    pattern = a.filterIsInstance<Pattern>().firstOrNull()?.value,
                    schemes = schemes,
                    values = values,
                    items = listItems,
                    // Hoplite splits a string on "," for lists (and trims each item).
                    listEncoding = ListEncoding.CSV,
                    minItems = items?.min?.takeIf { it >= 0 },
                    maxItems = items?.max?.takeIf { it >= 0 },
                    itemMin = if (intItems) maxOf(itemMin ?: Long.MIN_VALUE, Int.MIN_VALUE.toLong()) else itemMin,
                    itemMax = if (intItems) minOf(itemMax ?: Long.MAX_VALUE, Int.MAX_VALUE.toLong()) else itemMax,
                    schema = schema,
                ),
                path,
                p.type,
            )
        }

        fun fileInput(p: KParameter, k: KClass<*>, path: List<String>, optional: Boolean, group: String?) {
            val a = p.annotations
            val input = a.filterIsInstance<FileInput>().firstOrNull()
            if (input == null) {
                errors += "${where(path)}: add @FileInput(name = ..., path = ...)"
                return
            }
            val doc = a.filterIsInstance<Doc>().firstOrNull()?.value
            if (doc == null) errors += "${where(path)}: add @Doc(\"...\") with a description of at least 5 characters"
            val type = when (k) {
                ConfigFile::class -> FileType.CONFIG
                TlsKeyPair::class -> FileType.TLS
                CaBundle::class -> FileType.CA_BUNDLE
                Keystore::class -> FileType.KEYSTORE
                TextFile::class -> FileType.TEXT
                else -> FileType.BINARY
            }
            val tls = a.filterIsInstance<Tls>().firstOrNull()
            val keystore = a.filterIsInstance<KeystoreSpec>().firstOrNull()
            val length = a.filterIsInstance<Length>().firstOrNull()
            var valueType: KType? = null
            var format: ConfigFormat? = null
            var schema: JsonValue? = null
            if (type == FileType.CONFIG) {
                valueType = p.type.arguments.firstOrNull()?.type
                format = a.filterIsInstance<Format>().firstOrNull()?.value ?: when (input.path.substringAfterLast('.', "").lowercase()) {
                    "json" -> ConfigFormat.JSON
                    "yaml", "yml" -> ConfigFormat.YAML
                    "toml" -> ConfigFormat.TOML
                    else -> {
                        errors += "${where(path)}: cannot tell the format of ${input.path}; add @Format"
                        null
                    }
                }
                if (valueType == null) errors += "${where(path)}: ConfigFile needs a concrete type argument" else schema = SchemaGenerator.schema(valueType, where(path))
            }
            if (type == FileType.KEYSTORE && keystore == null) {
                errors += "${where(path)}: add @KeystoreSpec(format = ..., passwordVar = ...)"
            }
            files += FileBinding(
                FileSpec(
                    name = input.name,
                    type = type,
                    description = doc ?: "",
                    path = input.path,
                    required = !optional,
                    secret = input.secret || type == FileType.TLS || type == FileType.KEYSTORE,
                    group = group,
                    deprecated = a.filterIsInstance<DeprecatedInput>().firstOrNull()?.let { Deprecation(it.message, it.replacedBy.ifEmpty { null }) },
                    pathEnv = input.pathEnv.ifEmpty { null },
                    maxSize = input.maxSize.takeIf { it >= 0 },
                    format = format,
                    schema = schema,
                    dnsNames = tls?.dnsNames?.toList()?.takeIf { it.isNotEmpty() },
                    keyAlgorithms = tls?.keyAlgorithms?.toList()?.takeIf { it.isNotEmpty() },
                    minRemaining = tls?.minRemaining?.ifEmpty { null },
                    requireCA = tls?.requireCA ?: false,
                    minCertificates = a.filterIsInstance<MinCertificates>().firstOrNull()?.value,
                    keystoreFormat = if (type == FileType.KEYSTORE) keystore?.format ?: KeystoreFormat.PKCS12 else null,
                    passwordVar = keystore?.passwordVar?.ifEmpty { null }?.let { if (it.startsWith(prefix)) it else prefix + it },
                    pattern = a.filterIsInstance<Pattern>().firstOrNull()?.value,
                    minLength = length?.min?.takeIf { it >= 0 },
                    maxLength = length?.max?.takeIf { it >= 0 },
                ),
                path,
                p.type,
                valueType,
            )
            if (tls != null && type != FileType.TLS) errors += "${where(path)}: @Tls only applies to TlsKeyPair"
        }

        /**
         * Defaults live in Kotlin default arguments, which reflection cannot read directly. docuconf
         * constructs the class once with placeholder values for the required parameters and reads
         * the properties back.
         */
        fun defaultsOf(k: KClass<*>, path: List<String>): Map<String, Any?> {
            val ctor = k.primaryConstructor ?: return emptyMap()
            if (ctor.parameters.none { it.isOptional }) return emptyMap()
            val instance = try {
                ctor.isAccessible = true
                ctor.callBy(ctor.parameters.filter { !it.isOptional }.associateWith { placeholder(it.type) })
            } catch (e: Exception) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                errors += "${where(path).trimEnd('.')}: cannot read the defaults of ${k.simpleName}: constructing it with placeholder values failed (${cause.message}). " +
                    "Keep init-block checks on values with defaults, or move them to docuconf constraints."
                return emptyMap()
            }
            val props = k.memberProperties.associateBy { it.name }
            return ctor.parameters.filter { it.isOptional }.associate { p ->
                val prop = props[p.name]
                if (prop == null) {
                    errors += "${where(path + p.name!!)}: constructor parameters with defaults must be properties (val), so docuconf can read the default"
                }
                p.name!! to prop?.let { it.isAccessible = true; it.getter.call(instance) }
            }
        }
    }

    private fun isScalar(k: KClass<*>, t: KType): Boolean =
        k == String::class || k == Secret::class || k == URI::class || k == URL::class ||
            k == Int::class || k == Long::class || k == Short::class || k == Byte::class ||
            k == Double::class || k == Float::class || k == Boolean::class ||
            k == java.time.Duration::class || k == kotlin.time.Duration::class ||
            k.java.isEnum || k == Json::class ||
            ((k == List::class || k == Set::class) && t.arguments.firstOrNull()?.type?.classifier.let { it == String::class || it == Int::class || it == Long::class })

    private val placeholderKey = object : PrivateKey {
        override fun getAlgorithm() = "none"
        override fun getFormat(): String? = null
        override fun getEncoded(): ByteArray? = null
    }

    /** A value of type [t] used only to construct an instance and read its defaults. */
    fun placeholder(t: KType): Any? {
        if (t.isMarkedNullable) return null
        val k = t.classifier as? KClass<*> ?: return null
        val nowhere = Path.of("/")
        return when {
            k == String::class -> ""
            k == Secret::class -> Secret("")
            k == Int::class -> 0
            k == Long::class -> 0L
            k == Short::class -> 0.toShort()
            k == Byte::class -> 0.toByte()
            k == Double::class -> 0.0
            k == Float::class -> 0f
            k == Boolean::class -> false
            k == java.time.Duration::class -> java.time.Duration.ZERO
            k == kotlin.time.Duration::class -> kotlin.time.Duration.ZERO
            k == URI::class -> URI("placeholder:/")
            k == URL::class -> URI("http://placeholder").toURL()
            k.java.isEnum -> k.java.enumConstants.first()
            k == List::class || k == Collection::class -> emptyList<Any>()
            k == Set::class -> emptySet<Any>()
            k == Map::class -> emptyMap<Any, Any>()
            k == Json::class -> Json(placeholder(t.arguments.first().type!!) ?: "")
            k == ConfigFile::class -> ConfigFile(nowhere, placeholder(t.arguments.first().type!!) ?: "")
            k == TlsKeyPair::class -> TlsKeyPair(nowhere, emptyList(), placeholderKey, emptyList())
            k == CaBundle::class -> CaBundle(nowhere, emptyList())
            k == Keystore::class -> Keystore(nowhere, java.security.KeyStore.getInstance("PKCS12"))
            k == TextFile::class -> TextFile(nowhere, "", false)
            k == BinaryFile::class -> BinaryFile(nowhere)
            else -> {
                val ctor = k.primaryConstructor ?: throw IllegalArgumentException("no placeholder for ${k.simpleName}")
                ctor.isAccessible = true
                ctor.callBy(ctor.parameters.filter { !it.isOptional }.associateWith { placeholder(it.type) })
            }
        }
    }
}
