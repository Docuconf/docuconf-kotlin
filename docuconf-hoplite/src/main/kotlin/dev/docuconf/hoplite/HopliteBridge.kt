package dev.docuconf.hoplite

import com.sksamuel.hoplite.ArrayNode
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.BooleanNode
import com.sksamuel.hoplite.ConfigFailure
import com.sksamuel.hoplite.ConfigResult
import com.sksamuel.hoplite.DecoderContext
import com.sksamuel.hoplite.DoubleNode
import com.sksamuel.hoplite.LongNode
import com.sksamuel.hoplite.MapNode
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.NullNode
import com.sksamuel.hoplite.Pos
import com.sksamuel.hoplite.StringNode
import com.sksamuel.hoplite.Undefined
import com.sksamuel.hoplite.decoder.Decoder
import com.sksamuel.hoplite.decoder.DotPath
import com.sksamuel.hoplite.fp.flatMap
import com.sksamuel.hoplite.fp.invalid
import com.sksamuel.hoplite.fp.valid
import com.sksamuel.hoplite.parsers.Parser
import com.sksamuel.hoplite.time.parseDuration
import dev.docuconf.kotlin.core.ConfigFormat
import dev.docuconf.kotlin.core.Durations
import dev.docuconf.kotlin.core.JsonSyntaxException
import dev.docuconf.kotlin.core.JsonValue
import java.util.ServiceLoader
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/** Marker values the docuconf property source puts where a file input is bound. */
internal const val FILE_MARKER = "docuconf-file:"

/**
 * The duration forms docuconf accepts in an env value, for both duration types: ISO 8601 (`PT1M30S`,
 * the contract's encoding, which the platform renders), Go syntax (`1m30s`, what people type
 * locally), and Hoplite's one number and one unit (`30s`, `5 minutes`). Returned as nanoseconds, or
 * null when none parses.
 */
internal fun hopliteDuration(raw: String): Long? =
    Durations.parseIso(raw) ?: Durations.parseGo(raw) ?: parseDuration(raw).fold({ null }, { it.toNanos() })

/** What a duration error says is accepted. */
internal const val DURATION_HINT: String = "an ISO 8601 duration like PT30S, or Go syntax like 30s or 1m30s"

/**
 * java.time.Duration: Hoplite's own decoder takes ISO 8601 and `30s`-style values, but not Go's
 * `1m30s`. docuconf's decoder takes all three ([hopliteDuration]).
 */
internal class JavaDurationDecoder : Decoder<java.time.Duration> {
    override fun supports(type: KType): Boolean = type.classifier == java.time.Duration::class
    override fun priority(): Int = 10

    override fun decode(node: Node, type: KType, context: DecoderContext): ConfigResult<java.time.Duration> = when (node) {
        is StringNode -> hopliteDuration(node.value)?.let { java.time.Duration.ofNanos(it) }?.valid() ?: ConfigFailure.DecodeError(node, type).invalid()
        is LongNode -> java.time.Duration.ofMillis(node.value).valid()
        else -> ConfigFailure.DecodeError(node, type).invalid()
    }
}

/** Binds enums whose constants carry [WireName] by their wire value. Other enums use Hoplite's decoder. */
internal class WireEnumDecoder : Decoder<Enum<*>> {
    override fun supports(type: KType): Boolean {
        val k = type.classifier as? KClass<*> ?: return false
        return k.java.isEnum && k.java.fields.any { it.isEnumConstant && it.isAnnotationPresent(WireName::class.java) }
    }

    override fun priority(): Int = 10

    override fun decode(node: Node, type: KType, context: DecoderContext): ConfigResult<Enum<*>> {
        val k = type.classifier as KClass<*>
        val raw = (node as? StringNode)?.value ?: return ConfigFailure.DecodeError(node, type).invalid()
        val match = k.java.enumConstants.map { it as Enum<*> }.firstOrNull { DeclarationReader.wireName(it) == raw }
        return match?.valid() ?: ConfigFailure.DecodeError(node, type).invalid()
    }
}

/**
 * kotlin.time.Duration: Hoplite's own decoder takes only `30s`-style values, which cannot carry
 * `1m30s`. docuconf's decoder takes the same forms as for java.time.Duration ([hopliteDuration]).
 */
internal class KotlinDurationDecoder : Decoder<kotlin.time.Duration> {
    override fun supports(type: KType): Boolean = type.classifier == kotlin.time.Duration::class
    override fun priority(): Int = 10

    override fun decode(node: Node, type: KType, context: DecoderContext): ConfigResult<kotlin.time.Duration> = when (node) {
        is StringNode -> hopliteDuration(node.value)?.nanoseconds?.valid() ?: ConfigFailure.DecodeError(node, type).invalid()
        is LongNode -> node.value.milliseconds.valid()
        else -> ConfigFailure.DecodeError(node, type).invalid()
    }
}

/** The decoders docuconf adds for values: durations in every accepted form, wire-named enums, [Json]. */
internal fun valueDecoders(): List<Decoder<*>> = listOf(KotlinDurationDecoder(), JavaDurationDecoder(), WireEnumDecoder(), JsonVarDecoder())

/**
 * Whether a sealed type appears anywhere in [root]'s class tree, including `@NotInContract`
 * parameters. Without one, docuconf turns on Hoplite's explicit sealed types, which changes nothing
 * but silences Hoplite 3.0's deprecation notice about sealed-type inference.
 */
internal fun hasSealedTypes(root: KClass<*>): Boolean {
    val seen = HashSet<KClass<*>>()
    fun visit(t: KType?): Boolean {
        val k = t?.classifier as? KClass<*> ?: return false
        if (t.arguments.any { visit(it.type) }) return true
        if (!seen.add(k)) return false
        if (k.isSealed) return true
        val pkg = k.java.`package`?.name ?: ""
        if (pkg.startsWith("java.") || pkg.startsWith("kotlin.") || k.java.isEnum || k.java.isPrimitive) return false
        return k.primaryConstructor?.parameters?.any { visit(it.type) } ?: false
    }
    if (root.isSealed) return true
    seen += root
    return root.primaryConstructor?.parameters?.any { visit(it.type) } ?: false
}

/** Hoplite's explicit sealed types (an experimental API in 3.0). */
@OptIn(ExperimentalHoplite::class)
internal fun ConfigLoaderBuilder.explicitSealedTypes(): ConfigLoaderBuilder = withExplicitSealedTypes()

/** Decodes a [Json] variable: parses the string, then lets Hoplite bind the result to `T`. */
internal class JsonVarDecoder : Decoder<Json<*>> {
    override fun supports(type: KType): Boolean = type.classifier == Json::class
    override fun priority(): Int = 10

    override fun decode(node: Node, type: KType, context: DecoderContext): ConfigResult<Json<*>> {
        val inner = type.arguments.first().type!!
        val tree: Node = when (node) {
            is StringNode -> try {
                toNode(JsonValue.parse(node.value), node.pos, node.path)
            } catch (e: JsonSyntaxException) {
                return ConfigFailure.Generic("${node.path.flatten()}: ${e.message}").invalid()
            }
            // From a structured source (a YAML base file), the value is already a tree.
            else -> node
        }
        return context.decoder(inner).flatMap { d -> d.decode(tree, inner, context) }.map { Json(it!!) }
    }
}

/** Hands file inputs, already loaded and checked by docuconf, to Hoplite when it binds the class. */
internal class FileInputDecoder(private val loaded: Map<String, Any>) : Decoder<Any> {
    private val types: Set<KClass<*>> = setOf(ConfigFile::class, TlsKeyPair::class, CaBundle::class, Keystore::class, TextFile::class, BinaryFile::class)

    override fun supports(type: KType): Boolean = type.classifier in types
    override fun priority(): Int = 10

    override fun decode(node: Node, type: KType, context: DecoderContext): ConfigResult<Any> {
        val name = (node as? StringNode)?.value?.removePrefix(FILE_MARKER)
        val v = name?.let { loaded[it] }
        return v?.valid() ?: ConfigFailure.Generic("file input at ${node.path.flatten()} was not loaded by docuconf").invalid()
    }
}

/** Converts parsed JSON to a Hoplite node tree. */
internal fun toNode(v: JsonValue, pos: Pos = Pos.NoPos, path: DotPath = DotPath.root): Node = when (v) {
    JsonValue.Null -> NullNode(pos, path)
    is JsonValue.Bool -> BooleanNode(v.value, pos, path)
    is JsonValue.Int -> LongNode(v.value, pos, path)
    is JsonValue.Float -> DoubleNode(v.value, pos, path)
    is JsonValue.Str -> StringNode(v.value, pos, path)
    is JsonValue.Arr -> ArrayNode(v.items.mapIndexed { i, x -> toNode(x, pos, path.with(i.toString())) }, pos, path)
    is JsonValue.Obj -> MapNode(v.fields.mapValues { (k, x) -> toNode(x, pos, path.with(k)) }, pos, path)
}

/** Converts a Hoplite node tree to JSON, for schema validation. */
internal fun toJson(n: Node): JsonValue = when (n) {
    is MapNode -> JsonValue.Obj(n.map.mapValues { toJson(it.value) })
    is ArrayNode -> JsonValue.Arr(n.elements.map { toJson(it) })
    is StringNode -> JsonValue.Str(n.value)
    is LongNode -> JsonValue.Int(n.value)
    is DoubleNode -> JsonValue.Float(n.value)
    is BooleanNode -> JsonValue.Bool(n.value)
    is NullNode, Undefined -> JsonValue.Null

}

/** Finds the Hoplite parser module for a config file format on the classpath. */
internal fun parserFor(format: ConfigFormat, classLoader: ClassLoader): Parser? {
    val ext = when (format) {
        ConfigFormat.JSON -> "json"
        ConfigFormat.YAML -> "yaml"
        ConfigFormat.TOML -> "toml"
    }
    return ServiceLoader.load(Parser::class.java, classLoader).firstOrNull { ext in it.defaultFileExtensions() }
}

internal fun parserModule(format: ConfigFormat): String = "com.sksamuel.hoplite:hoplite-${format.wire}"
