package dev.docuconf.core

/**
 * Validates a value against the JSON Schema subset docuconf generates from an app's types:
 * `type`, `properties`, `required`, `additionalProperties`, `items`, `enum`, `minimum`, `maximum`,
 * `minLength`, `maxLength`, `pattern` (RE2, partial match), `minItems` and `maxItems`.
 *
 * The platform checks the same schema before deploy (CUE), so the app and the platform accept the
 * same documents.
 */
public object JsonSchemaValidator {
    /** One schema violation at a JSON path such as `$.routes[0].match`. */
    public data class Problem(val path: String, val message: String) {
        override fun toString(): String = "$path: $message"
    }

    /**
     * @param lenientScalars accept strings where the schema asks for an integer, number or boolean
     *   when the string parses as one. YAML parsers that do not keep scalar types need this.
     */
    public fun validate(schema: JsonValue, value: JsonValue, lenientScalars: Boolean = false): List<Problem> {
        val out = ArrayList<Problem>()
        visit(schema, value, "$", lenientScalars, out)
        return out
    }

    private fun visit(schema: JsonValue, value: JsonValue, path: String, lenient: Boolean, out: MutableList<Problem>) {
        if (schema !is JsonValue.Obj) return
        val s = schema.fields
        val types = when (val t = s["type"]) {
            is JsonValue.Str -> listOf(t.value)
            is JsonValue.Arr -> t.items.mapNotNull { (it as? JsonValue.Str)?.value }
            else -> null
        }
        if (types != null && types.none { matchesType(it, value, lenient) }) {
            out += Problem(path, "expected ${types.joinToString(" or ")}, got ${typeName(value)}")
            return
        }
        (s["enum"] as? JsonValue.Arr)?.let { allowed ->
            if (allowed.items.none { it == value }) {
                out += Problem(path, "must be one of ${allowed.items.joinToString(", ") { it.show() }}")
            }
        }
        when (value) {
            is JsonValue.Obj -> {
                val props = (s["properties"] as? JsonValue.Obj)?.fields ?: emptyMap()
                (s["required"] as? JsonValue.Arr)?.items?.forEach { r ->
                    val name = (r as? JsonValue.Str)?.value ?: return@forEach
                    if (name !in value.fields) out += Problem(path, "missing required property \"$name\"")
                }
                for ((k, v) in value.fields) {
                    val childPath = if (isIdentifier(k)) "$path.$k" else "$path[${ValueChecks.quote(k)}]"
                    val ps = props[k]
                    when {
                        ps != null -> visit(ps, v, childPath, lenient, out)
                        s["additionalProperties"] == JsonValue.Bool(false) -> out += Problem(childPath, "is not an allowed property")
                        s["additionalProperties"] is JsonValue.Obj -> visit(s["additionalProperties"]!!, v, childPath, lenient, out)
                    }
                }
            }
            is JsonValue.Arr -> {
                (s["minItems"] as? JsonValue.Int)?.let { if (value.items.size < it.value) out += Problem(path, "has ${value.items.size} items, fewer than ${it.value}") }
                (s["maxItems"] as? JsonValue.Int)?.let { if (value.items.size > it.value) out += Problem(path, "has ${value.items.size} items, more than ${it.value}") }
                s["items"]?.let { itemSchema -> value.items.forEachIndexed { i, x -> visit(itemSchema, x, "$path[$i]", lenient, out) } }
            }
            is JsonValue.Str -> {
                val number = if (lenient && types != null && ("integer" in types || "number" in types)) value.value.toDoubleOrNull() else null
                if (number != null) {
                    checkNumber(s, number, path, out)
                } else {
                    val n = ValueChecks.codePointCount(value.value)
                    (s["minLength"] as? JsonValue.Int)?.let { if (n < it.value) out += Problem(path, "is shorter than ${it.value} characters") }
                    (s["maxLength"] as? JsonValue.Int)?.let { if (n > it.value) out += Problem(path, "is longer than ${it.value} characters") }
                    (s["pattern"] as? JsonValue.Str)?.let { if (!Re2.matches(it.value, value.value)) out += Problem(path, "does not match pattern ${it.value}") }
                }
            }
            is JsonValue.Int -> checkNumber(s, value.value.toDouble(), path, out)
            is JsonValue.Float -> checkNumber(s, value.value, path, out)
            else -> Unit
        }
    }

    private fun checkNumber(s: Map<String, JsonValue>, d: Double, path: String, out: MutableList<Problem>) {
        s["minimum"]?.let { if (d < it.asDouble()) out += Problem(path, "is below minimum ${it.show()}") }
        s["maximum"]?.let { if (d > it.asDouble()) out += Problem(path, "is above maximum ${it.show()}") }
    }

    private fun matchesType(type: String, v: JsonValue, lenient: Boolean): Boolean = when (type) {
        "object" -> v is JsonValue.Obj
        "array" -> v is JsonValue.Arr
        "string" -> v is JsonValue.Str
        "integer" -> v is JsonValue.Int || (v is JsonValue.Float && v.value % 1.0 == 0.0) ||
            (lenient && v is JsonValue.Str && v.value.toLongOrNull() != null)
        "number" -> v is JsonValue.Int || v is JsonValue.Float || (lenient && v is JsonValue.Str && v.value.toDoubleOrNull()?.isFinite() == true)
        "boolean" -> v is JsonValue.Bool || (lenient && v is JsonValue.Str && v.value.lowercase() in setOf("true", "false"))
        "null" -> v is JsonValue.Null
        else -> true
    }

    private fun typeName(v: JsonValue): String = when (v) {
        JsonValue.Null -> "null"
        is JsonValue.Bool -> "boolean"
        is JsonValue.Int -> "integer"
        is JsonValue.Float -> "number"
        is JsonValue.Str -> "string"
        is JsonValue.Arr -> "array"
        is JsonValue.Obj -> "object"
    }

    private fun isIdentifier(k: String) = k.isNotEmpty() && (k[0].isLetter() || k[0] == '_') && k.all { it.isLetterOrDigit() || it == '_' }
}
