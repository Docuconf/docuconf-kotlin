package dev.docuconf.hoplite

import com.sksamuel.hoplite.ArrayNode
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/** Marker values the docuconf property source puts where a file input is bound. */
internal const val FILE_MARKER = "docuconf-file:"

/**
 * Hoplite's duration parser for env values: one number and one unit (`30s`, `5 minutes`), then
 * ISO-8601 for java.time.Duration. Returned as nanoseconds, or null when it does not parse.
 */
internal fun hopliteDuration(raw: String): Long? =
    parseDuration(raw).fold({ null }, { it.toNanos() }) ?: Durations.parseIso(raw)

/**
 * kotlin.time.Duration: Hoplite's own decoder takes only `30s`-style values, which cannot carry
 * `1m30s`. docuconf's decoder also takes ISO-8601 (`PT1M30S`), the encoding in the contract.
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
