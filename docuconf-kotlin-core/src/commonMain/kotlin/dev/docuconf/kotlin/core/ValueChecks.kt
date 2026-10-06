package dev.docuconf.kotlin.core

/**
 * Checks one variable's raw environment string against its declaration (SPEC §5 parsing rules and
 * §11.2 item 5). Runs before the host library parses, so every problem is reported with a stable
 * code, and secrets are never echoed.
 */
public object ValueChecks {
    private val urlSyntax = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s]+$")
    private val intSyntax = Regex("^[+-]?[0-9]+$")
    private val floatSyntax = Regex("^[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?$")
    private val secondsSyntax = Regex("^([0-9]+)(?:\\.([0-9]{1,9}))?$")
    private val timespanSyntax = Regex("^(?:([0-9]+)\\.)?([0-9]{1,2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,7}))?$")

    /**
     * Options that adapt the checks to the host library.
     *
     * @property trimListItems whether the host trims whitespace around csv items (Hoplite does).
     * @property hostDuration an extra duration parser for forms the host also accepts (Hoplite's `30s`).
     * @property lenientBools other strings the host accepts as booleans (Hoplite: t, f, 1, 0, yes, no).
     */
    public data class Options(
        val trimListItems: Boolean = false,
        val hostDuration: ((String) -> Long?)? = null,
        val lenientBools: Set<String> = emptySet(),
    )

    /** Whether a raw value counts as unset: absent, or empty for any type except string (SPEC §5). */
    public fun isUnset(spec: VarSpec, raw: String?): Boolean = raw == null || (raw.isEmpty() && spec.type != VarType.STRING)

    /**
     * Reference schemes of secret injectors (Bank-Vaults `vault:`, 1Password `op://`, vals `ref+`).
     * A secret that still starts with one was never resolved: the injector did not run (SPEC §4.5.1).
     */
    public val injectorSchemes: List<String> = listOf("vault:", "op://", "ref+")

    /** The injector scheme [raw] starts with, or null. */
    public fun unresolvedReference(raw: String): String? = injectorSchemes.firstOrNull { raw.startsWith(it) }

    /** All violations for [spec] given its raw value (null when the variable is not in the environment). */
    public fun check(spec: VarSpec, raw: String?, options: Options = Options()): List<Violation> {
        if (isUnset(spec, raw)) {
            return if (spec.required) listOf(Violation(Codes.MISSING_REQUIRED, spec.name, "required, but not set")) else emptyList()
        }
        if (spec.secret) {
            // Reported on its own: the scheme or pattern checks would only describe the reference.
            unresolvedReference(raw!!)?.let { scheme ->
                return listOf(Violation(Codes.INVALID_TYPE, spec.name, "holds an unresolved $scheme reference; the injector that should resolve it did not run"))
            }
        }
        return Checker(spec, raw!!, options).run()
    }

    /** Parses a duration in the variable's encoding, or with the host parser; null when neither accepts it. */
    public fun parseDuration(raw: String, encoding: DurationEncoding, hostDuration: ((String) -> Long?)? = null): Long? {
        val native = when (encoding) {
            DurationEncoding.GO -> Durations.parseGo(raw)
            DurationEncoding.ISO8601 -> Durations.parseIso(raw)
            DurationEncoding.SECONDS -> secondsSyntax.matchEntire(raw)?.let { m ->
                val whole = m.groupValues[1].toLongOrNull() ?: return@let null
                whole * Durations.NANOS_PER_SECOND + m.groupValues[2].padEnd(9, '0').ifEmpty { "0" }.toLong()
            }
            DurationEncoding.TIMESPAN -> timespanSyntax.matchEntire(raw)?.let { m ->
                val (d, h, min, s, f) = m.destructured
                val days = if (d.isEmpty()) 0 else d.toLongOrNull() ?: return@let null
                if (h.toInt() > 23 || min.toInt() > 59 || s.toInt() > 59) return@let null
                days * 24 * Durations.NANOS_PER_HOUR + h.toLong() * Durations.NANOS_PER_HOUR +
                    min.toLong() * Durations.NANOS_PER_MINUTE + s.toLong() * Durations.NANOS_PER_SECOND +
                    (if (f.isEmpty()) 0L else f.padEnd(9, '0').toLong())
            }
        }
        return native ?: hostDuration?.invoke(raw)
    }

    private class Checker(val spec: VarSpec, val raw: String, val options: Options) {
        val out = ArrayList<Violation>()
        val shown: String get() = if (spec.secret) "the value" else quote(raw)

        fun add(code: String, message: String) {
            out += Violation(code, spec.name, message)
        }

        fun run(): List<Violation> {
            when (spec.type) {
                VarType.STRING -> checkString()
                VarType.INT -> checkInt()
                VarType.FLOAT -> checkFloat()
                VarType.BOOL -> checkBool()
                VarType.DURATION -> checkDuration()
                VarType.URL -> checkUrl()
                VarType.ENUM -> checkEnum()
                VarType.LIST -> checkList()
                VarType.JSON -> checkJson()
            }
            return out
        }

        fun checkString() {
            val length = codePointCount(raw)
            spec.minLength?.let { if (length < it) add(Codes.OUT_OF_RANGE, "is $length characters, shorter than minLength $it") }
            spec.maxLength?.let { if (length > it) add(Codes.OUT_OF_RANGE, "is $length characters, longer than maxLength $it") }
            spec.pattern?.let { if (!Re2.matches(it, raw)) add(Codes.PATTERN_MISMATCH, "$shown does not match pattern $it") }
        }

        fun checkInt() {
            val n = if (intSyntax.matches(raw)) raw.toLongOrNull() else null
            if (n == null) {
                add(Codes.INVALID_TYPE, "$shown is not a 64-bit integer")
                return
            }
            bounds(JsonValue.Int(n), n.toDouble())
        }

        fun checkFloat() {
            val d = if (floatSyntax.matches(raw)) raw.toDoubleOrNull() else null
            if (d == null || !d.isFinite()) {
                add(Codes.INVALID_TYPE, "$shown is not a finite decimal number")
                return
            }
            bounds(JsonValue.Float(d), d)
        }

        fun bounds(v: JsonValue, d: Double) {
            spec.min?.let { if (d < it.asDouble() || (v is JsonValue.Int && it is JsonValue.Int && v.value < it.value)) add(Codes.OUT_OF_RANGE, "$shown is below min ${it.show()}") }
            spec.max?.let { if (d > it.asDouble() || (v is JsonValue.Int && it is JsonValue.Int && v.value > it.value)) add(Codes.OUT_OF_RANGE, "$shown is above max ${it.show()}") }
        }

        fun checkBool() {
            val lower = raw.lowercase()
            if (lower != "true" && lower != "false" && lower !in options.lenientBools) {
                add(Codes.INVALID_TYPE, "$shown is not true or false")
            }
        }

        fun checkDuration() {
            val nanos = parseDuration(raw, spec.durationEncoding, options.hostDuration)
            if (nanos == null) {
                val example = when (spec.durationEncoding) {
                    DurationEncoding.GO -> "1m30s"
                    DurationEncoding.ISO8601 -> "PT1M30S"
                    DurationEncoding.SECONDS -> "90"
                    DurationEncoding.TIMESPAN -> "00:01:30"
                }
                add(Codes.INVALID_TYPE, "$shown is not a duration such as $example")
                return
            }
            spec.minDuration?.let { if (nanos < Durations.parseGo(it)!!) add(Codes.OUT_OF_RANGE, "$shown is shorter than min $it") }
            spec.maxDuration?.let { if (nanos > Durations.parseGo(it)!!) add(Codes.OUT_OF_RANGE, "$shown is longer than max $it") }
        }

        fun checkUrl() {
            if (!urlSyntax.matches(raw)) {
                add(Codes.INVALID_TYPE, "$shown is not a URL of the form scheme://...")
                return
            }
            val schemes = spec.schemes ?: return
            val scheme = raw.substringBefore("://")
            if (scheme !in schemes) {
                add(Codes.INVALID_SCHEME, "scheme ${quote(scheme)} is not one of ${schemes.joinToString(", ")}")
            }
        }

        fun checkEnum() {
            val values = spec.values ?: return
            if (raw !in values) add(Codes.NOT_IN_ENUM, "$shown is not one of ${values.joinToString(", ")}")
        }

        fun checkList() {
            val items: List<String> = when (spec.listEncoding) {
                ListEncoding.CSV -> raw.split(spec.separator).map { if (options.trimListItems) it.trim() else it }
                ListEncoding.JSON -> {
                    val parsed = try {
                        JsonValue.parse(raw)
                    } catch (e: JsonSyntaxException) {
                        add(Codes.INVALID_TYPE, "is not a JSON array: ${e.message}")
                        return
                    }
                    if (parsed !is JsonValue.Arr) {
                        add(Codes.INVALID_TYPE, "is not a JSON array")
                        return
                    }
                    parsed.items.map { if (it is JsonValue.Str) it.value else it.toString() }
                }
                ListEncoding.INDEXED -> listOf(raw)
            }
            if (spec.items == ListItems.INT) {
                items.forEachIndexed { i, item ->
                    if (!intSyntax.matches(item) || item.toLongOrNull() == null) {
                        add(Codes.INVALID_TYPE, if (spec.secret) "item $i is not an integer" else "item $i ${quote(item)} is not an integer")
                    }
                }
            }
            spec.minItems?.let { if (items.size < it) add(Codes.TOO_FEW_ITEMS, "has ${items.size} items, fewer than minItems $it") }
            spec.maxItems?.let { if (items.size > it) add(Codes.TOO_MANY_ITEMS, "has ${items.size} items, more than maxItems $it") }
        }

        fun checkJson() {
            val parsed = try {
                JsonValue.parse(raw)
            } catch (e: JsonSyntaxException) {
                add(Codes.INVALID_TYPE, "is not valid JSON: ${e.message}")
                return
            }
            val schema = spec.schema ?: return
            for (problem in JsonSchemaValidator.validate(schema, parsed)) {
                add(Codes.SCHEMA_MISMATCH, if (spec.secret) "does not match its schema at ${problem.path}" else problem.toString())
            }
        }
    }

    internal fun quote(s: String): String = buildString { quoteJson(s, this) }

    /** Length in Unicode code points, as CUE counts runes. */
    public fun codePointCount(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) i++
            i++
            n++
        }
        return n
    }
}

internal fun JsonValue.asDouble(): Double = when (this) {
    is JsonValue.Int -> value.toDouble()
    is JsonValue.Float -> value
    else -> Double.NaN
}

internal fun JsonValue.show(): String = when (this) {
    is JsonValue.Str -> value
    else -> toString()
}
