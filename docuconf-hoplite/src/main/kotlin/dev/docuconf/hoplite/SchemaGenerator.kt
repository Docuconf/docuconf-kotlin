package dev.docuconf.hoplite

import com.sksamuel.hoplite.Secret
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.Durations
import dev.docuconf.kotlin.core.JsonValue
import dev.docuconf.kotlin.core.Re2
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.net.URL
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Generates a JSON Schema from a Kotlin type, the type Hoplite binds a config file or `json`
 * variable to (SPEC §4.6, "schemas come from code").
 *
 * Data classes become closed objects (`additionalProperties: false`) whose `required` list holds the
 * non-null parameters without defaults. Property names are the Kotlin parameter names exactly, so a
 * file that the platform accepts also binds in the app. Constraint annotations ([Min], [Length],
 * [Pattern], [Items], [OneOf], ...) and [Doc] descriptions carry over.
 */
internal object SchemaGenerator {
    fun schema(type: KType, where: String): JsonValue = Gen(where).type(type, emptyList(), emptySet())

    private class Gen(val where: String) {
        fun fail(msg: String): Nothing = throw DeclarationException(listOf("$where: $msg"))

        fun type(t: KType, annotations: List<Annotation>, seen: Set<KClass<*>>): JsonValue {
            val k = t.classifier as? KClass<*> ?: fail("type $t cannot be described as JSON Schema")
            val fields = LinkedHashMap<String, JsonValue>()
            annotations.filterIsInstance<Doc>().firstOrNull()?.let { fields["description"] = JsonValue.Str(it.value) }
            when {
                k == String::class || k == Secret::class || k == URI::class || k == URL::class -> {
                    val oneOf = annotations.filterIsInstance<OneOf>().firstOrNull()
                    fields["type"] = JsonValue.Str("string")
                    if (oneOf != null) fields["enum"] = JsonValue.Arr(oneOf.value.map { JsonValue.Str(it) })
                    annotations.filterIsInstance<Length>().firstOrNull()?.let {
                        if (it.min >= 0) fields["minLength"] = JsonValue.Int(it.min.toLong())
                        if (it.max >= 0) fields["maxLength"] = JsonValue.Int(it.max.toLong())
                    }
                    annotations.filterIsInstance<Pattern>().firstOrNull()?.let {
                        Re2.unsupportedFeature(it.value)?.let { f -> fail("pattern uses $f, which RE2 does not support") }
                        fields["pattern"] = JsonValue.Str(it.value)
                    }
                }
                k == Int::class || k == Long::class || k == Short::class || k == Byte::class || k == BigInteger::class -> {
                    fields["type"] = JsonValue.Str("integer")
                    val (lo, hi) = when (k) {
                        Int::class -> Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong()
                        Short::class -> Short.MIN_VALUE.toLong() to Short.MAX_VALUE.toLong()
                        Byte::class -> Byte.MIN_VALUE.toLong() to Byte.MAX_VALUE.toLong()
                        else -> null to null
                    }
                    (annotations.filterIsInstance<Min>().firstOrNull()?.value ?: lo)?.let { fields["minimum"] = JsonValue.Int(it) }
                    (annotations.filterIsInstance<Max>().firstOrNull()?.value ?: hi)?.let { fields["maximum"] = JsonValue.Int(it) }
                }
                k == Double::class || k == Float::class || k == BigDecimal::class -> {
                    fields["type"] = JsonValue.Str("number")
                    annotations.filterIsInstance<DecimalMin>().firstOrNull()?.let { fields["minimum"] = JsonValue.Float(it.value) }
                    annotations.filterIsInstance<DecimalMax>().firstOrNull()?.let { fields["maximum"] = JsonValue.Float(it.value) }
                }
                k == Boolean::class -> fields["type"] = JsonValue.Str("boolean")
                k == java.time.Duration::class || k == kotlin.time.Duration::class -> {
                    // Hoplite reads durations in files as "30s", "5 minutes" or ISO-8601 (java.time only).
                    fields["type"] = JsonValue.Str("string")
                }
                k.java.isEnum -> {
                    fields["type"] = JsonValue.Str("string")
                    fields["enum"] = JsonValue.Arr(k.java.enumConstants.map { JsonValue.Str(DeclarationReader.wireName(it as Enum<*>)) })
                }
                k == List::class || k == Set::class || k == Collection::class -> {
                    val item = t.arguments.firstOrNull()?.type ?: fail("collection $t needs a concrete item type")
                    fields["type"] = JsonValue.Str("array")
                    fields["items"] = type(item, emptyList(), seen)
                    annotations.filterIsInstance<Items>().firstOrNull()?.let {
                        if (it.min >= 0) fields["minItems"] = JsonValue.Int(it.min.toLong())
                        if (it.max >= 0) fields["maxItems"] = JsonValue.Int(it.max.toLong())
                    }
                }
                k == Map::class -> {
                    val keyType = t.arguments.getOrNull(0)?.type?.classifier
                    if (keyType != String::class) fail("map $t must have String keys")
                    val value = t.arguments.getOrNull(1)?.type ?: fail("map $t needs a concrete value type")
                    fields["type"] = JsonValue.Str("object")
                    fields["additionalProperties"] = type(value, emptyList(), seen)
                }
                k.isData || (k.primaryConstructor != null && !k.isSubclassOf(Collection::class)) -> {
                    if (k in seen) fail("${k.simpleName} is recursive, which JSON Schema export does not support")
                    val ctor = k.primaryConstructor ?: fail("${k.simpleName} has no primary constructor")
                    val props = LinkedHashMap<String, JsonValue>()
                    val required = ArrayList<JsonValue>()
                    for (p in ctor.parameters) {
                        val name = p.name ?: continue
                        props[name] = type(p.type, p.annotations, seen + k)
                        if (!p.isOptional && !p.type.isMarkedNullable) required += JsonValue.Str(name)
                    }
                    fields["type"] = JsonValue.Str("object")
                    if (required.isNotEmpty()) fields["required"] = JsonValue.Arr(required)
                    fields["additionalProperties"] = JsonValue.Bool(false)
                    fields["properties"] = JsonValue.Obj(props)
                }
                else -> fail("type ${k.qualifiedName} cannot be described as JSON Schema")
            }
            return JsonValue.Obj(fields)
        }
    }
}

/** Converts a bound Kotlin value (a default, typically) to JSON. */
internal fun toJsonValue(value: Any?): JsonValue = when (value) {
    null -> JsonValue.Null
    is String -> JsonValue.Str(value)
    is Boolean -> JsonValue.Bool(value)
    is Byte, is Short, is Int, is Long -> JsonValue.Int((value as Number).toLong())
    is Float -> JsonValue.Float(value.toString().toDouble())
    is Double -> JsonValue.Float(value)
    is BigInteger -> JsonValue.Int(value.toLong())
    is BigDecimal -> JsonValue.Float(value.toDouble())
    is Enum<*> -> JsonValue.Str(DeclarationReader.wireName(value))
    is URI, is URL -> JsonValue.Str(value.toString())
    is java.time.Duration -> JsonValue.Str(Durations.formatGo(value.toNanos()))
    is kotlin.time.Duration -> JsonValue.Str(Durations.formatGo(value.inWholeNanoseconds))
    is Json<*> -> toJsonValue(value.value)
    is Collection<*> -> JsonValue.Arr(value.map { toJsonValue(it) })
    is Map<*, *> -> JsonValue.Obj(value.entries.associate { (k, v) -> k.toString() to toJsonValue(v) })
    else -> {
        val k = value::class
        val ctor = k.primaryConstructor ?: throw IllegalArgumentException("cannot write ${k.simpleName} as JSON")
        val props = k.memberProperties.associateBy { it.name }
        JsonValue.Obj(
            ctor.parameters.mapNotNull { p: KParameter ->
                val prop = props[p.name] ?: return@mapNotNull null
                val v = prop.getter.call(value) ?: return@mapNotNull null
                p.name!! to toJsonValue(v)
            }.toMap(LinkedHashMap()),
        )
    }
}
