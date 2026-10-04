package dev.docuconf.kotlin.core

/**
 * A JSON value. Used for defaults, JSON Schemas and `json` variables, so the core needs no
 * serialization library and runs on every Kotlin target.
 */
public sealed class JsonValue {
    public data object Null : JsonValue()
    public data class Bool(val value: Boolean) : JsonValue()
    public data class Int(val value: Long) : JsonValue()
    public data class Float(val value: Double) : JsonValue()
    public data class Str(val value: String) : JsonValue()
    public data class Arr(val items: List<JsonValue>) : JsonValue()

    /** Keys keep insertion order. */
    public data class Obj(val fields: Map<String, JsonValue>) : JsonValue()

    /** Compact JSON. */
    final override fun toString(): String = buildString { writeJson(this@JsonValue, this) }

    public companion object {
        public fun of(value: Any?): JsonValue = when (value) {
            null -> Null
            is JsonValue -> value
            is Boolean -> Bool(value)
            is Byte, is Short, is kotlin.Int, is Long -> Int((value as Number).toLong())
            is Double, is kotlin.Float -> Float((value as Number).toDouble())
            is String -> Str(value)
            is List<*> -> Arr(value.map { of(it) })
            is Map<*, *> -> Obj(value.entries.associate { (k, v) -> k.toString() to of(v) })
            else -> throw IllegalArgumentException("cannot represent ${value::class.simpleName} as JSON")
        }

        /** Parses strict JSON (RFC 8259). Throws [JsonSyntaxException] with a position, never the input. */
        public fun parse(text: String): JsonValue = JsonReader(text).readDocument()
    }
}

/** A JSON syntax error. The message gives the offset, not the content. */
public class JsonSyntaxException(message: String) : IllegalArgumentException(message)

internal fun quoteJson(s: String, out: StringBuilder) {
    out.append('"')
    for (c in s) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else -> if (c < ' ' || c == ' ' || c == ' ') {
                out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            } else {
                out.append(c)
            }
        }
    }
    out.append('"')
}

/** A double as the shortest round-trip decimal that still reads as a float. */
internal fun formatFloat(d: Double): String {
    require(d.isFinite()) { "NaN and infinite values cannot be written" }
    val s = d.toString()
    return if (s.contains('.') || s.contains('E') || s.contains('e')) s else "$s.0"
}

private fun writeJson(v: JsonValue, out: StringBuilder) {
    when (v) {
        JsonValue.Null -> out.append("null")
        is JsonValue.Bool -> out.append(v.value)
        is JsonValue.Int -> out.append(v.value)
        is JsonValue.Float -> out.append(formatFloat(v.value))
        is JsonValue.Str -> quoteJson(v.value, out)
        is JsonValue.Arr -> {
            out.append('[')
            v.items.forEachIndexed { i, x ->
                if (i > 0) out.append(',')
                writeJson(x, out)
            }
            out.append(']')
        }
        is JsonValue.Obj -> {
            out.append('{')
            var first = true
            for ((k, x) in v.fields) {
                if (!first) out.append(',')
                first = false
                quoteJson(k, out)
                out.append(':')
                writeJson(x, out)
            }
            out.append('}')
        }
    }
}

private class JsonReader(private val s: String) {
    private var i = 0

    fun readDocument(): JsonValue {
        // A UTF-8 byte-order mark is tolerated (docs/EDGE_CASES.md).
        if (s.startsWith('﻿')) i = 1
        skipWs()
        val v = readValue()
        skipWs()
        if (i != s.length) fail("unexpected content after the JSON value")
        return v
    }

    private fun fail(msg: String): Nothing = throw JsonSyntaxException("invalid JSON at offset $i: $msg")

    private fun skipWs() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    private fun readValue(): JsonValue {
        if (i >= s.length) fail("unexpected end of input")
        return when (val c = s[i]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            else -> if (c == '-' || c in '0'..'9') readNumber() else fail("unexpected character")
        }
    }

    private fun literal(word: String, v: JsonValue): JsonValue {
        if (!s.startsWith(word, i)) fail("unexpected token")
        i += word.length
        return v
    }

    private fun readObject(): JsonValue {
        i++
        val fields = LinkedHashMap<String, JsonValue>()
        skipWs()
        if (i < s.length && s[i] == '}') {
            i++
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') fail("expected a property name")
            val key = readString()
            skipWs()
            if (i >= s.length || s[i] != ':') fail("expected ':'")
            i++
            skipWs()
            fields[key] = readValue()
            skipWs()
            if (i >= s.length) fail("unterminated object")
            when (s[i]) {
                ',' -> i++
                '}' -> {
                    i++
                    return JsonValue.Obj(fields)
                }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun readArray(): JsonValue {
        i++
        val items = ArrayList<JsonValue>()
        skipWs()
        if (i < s.length && s[i] == ']') {
            i++
            return JsonValue.Arr(items)
        }
        while (true) {
            skipWs()
            items += readValue()
            skipWs()
            if (i >= s.length) fail("unterminated array")
            when (s[i]) {
                ',' -> i++
                ']' -> {
                    i++
                    return JsonValue.Arr(items)
                }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun readString(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (i >= s.length) fail("unterminated escape")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("bad unicode escape")
                            val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad unicode escape")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> fail("bad escape '\\$e'")
                    }
                }
                c < ' ' -> fail("control character in string")
                else -> sb.append(c)
            }
        }
    }

    private fun readNumber(): JsonValue {
        val start = i
        if (s[i] == '-') i++
        if (i >= s.length) fail("bad number")
        if (s[i] == '0') {
            i++
        } else if (s[i] in '1'..'9') {
            while (i < s.length && s[i].isAsciiDigit()) i++
        } else {
            fail("bad number")
        }
        var isFloat = false
        if (i < s.length && s[i] == '.') {
            isFloat = true
            i++
            if (i >= s.length || !s[i].isAsciiDigit()) fail("bad number")
            while (i < s.length && s[i].isAsciiDigit()) i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            isFloat = true
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || !s[i].isAsciiDigit()) fail("bad number")
            while (i < s.length && s[i].isAsciiDigit()) i++
        }
        val text = s.substring(start, i)
        if (!isFloat) text.toLongOrNull()?.let { return JsonValue.Int(it) }
        return JsonValue.Float(text.toDouble())
    }
}

internal fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
