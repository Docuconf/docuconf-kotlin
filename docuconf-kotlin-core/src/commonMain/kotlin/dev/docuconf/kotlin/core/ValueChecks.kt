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

    /**
     * The outcome of [parse]: every violation, and the typed value when there are none.
     *
     * [value] is null when the variable is unset or has violations. Otherwise it is a `String`
     * (string, url, enum), `Long` (int), `Double` (float), `Boolean` (bool), nanoseconds as `Long`
     * (duration), `List<String>` or `List<Long>` (list), or a [JsonValue] (json).
     */
    public data class Parsed(val violations: List<Violation>, val value: Any?)

    /** All violations for [spec] given its raw value (null when the variable is not in the environment). */
    public fun check(spec: VarSpec, raw: String?, options: Options = Options()): List<Violation> = parse(spec, raw, options).violations

    /**
     * Checks [raw] against [spec] and parses it into its typed value, in one pass, so the value an app
     * gets is exactly the one that was checked. For an `indexed` list (one variable per item), pass
     * the items with [items] instead of [raw].
     */
    public fun parse(spec: VarSpec, raw: String?, options: Options = Options(), items: List<String>? = null): Parsed {
        if (items != null) {
            if (items.isEmpty()) return parse(spec, null, options)
            return Checker(spec, "", options).runList(items)
        }
        if (isUnset(spec, raw)) {
            return Parsed(if (spec.required) listOf(Violation(Codes.MISSING_REQUIRED, spec.name, "required, but not set")) else emptyList(), null)
        }
        if (spec.secret) {
            // Reported on its own: the scheme or pattern checks would only describe the reference.
            unresolvedReference(raw!!)?.let { scheme ->
                return Parsed(listOf(Violation(Codes.INVALID_TYPE, spec.name, "holds an unresolved $scheme reference; the injector that should resolve it did not run")), null)
            }
        }
        return Checker(spec, raw!!, options).run()
    }

    /**
     * The items of an `indexed` list (SPEC §5): `NAME__0`, `NAME__1`, ... up to the first index
     * missing from [env]. Empty when `NAME__0` is not set. Use [indexedGap] to find items past a gap.
     */
    public fun indexedItems(name: String, env: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        while (true) out += env["${name}__${out.size}"] ?: return out
    }

    private val indexSyntax = Regex("^(0|[1-9][0-9]*)$")

    /**
     * Why the `indexed` list [name] in [env] is not numbered from 0 without a gap, or null when it is
     * (SPEC §5). Only `NAME__<n>` with a decimal index and no leading zero is an item; any other
     * suffix, such as `NAME__HOST`, is ignored.
     */
    public fun indexedGap(name: String, env: Map<String, String>): String? {
        val prefix = "${name}__"
        val indexes = env.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.substring(prefix.length) }
            .filter { indexSyntax.matches(it) }
            .toSet()
        // n distinct indexes are gapless exactly when they are 0..n-1.
        val missing = (0 until indexes.size).firstOrNull { it.toString() !in indexes } ?: return null
        return "sets a higher index but not ${prefix}$missing; items must be numbered from 0 with no gap"
    }

    /** Checks and parses the `indexed` list [spec] from [env], including the gap rule of SPEC §5. */
    public fun parseIndexed(spec: VarSpec, env: Map<String, String>, options: Options = Options()): Parsed {
        indexedGap(spec.name, env)?.let { return Parsed(listOf(Violation(Codes.INVALID_TYPE, spec.name, it)), null) }
        return parse(spec, null, options, items = indexedItems(spec.name, env))
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

        fun done(value: Any?): Parsed = Parsed(out, if (out.isEmpty()) value else null)

        fun run(): Parsed = done(
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
            },
        )

        fun runList(items: List<String>): Parsed = done(checkItems(items))

        fun checkString(): String {
            val length = codePointCount(raw)
            spec.minLength?.let { if (length < it) add(Codes.OUT_OF_RANGE, "is $length characters, shorter than minLength $it") }
            spec.maxLength?.let { if (length > it) add(Codes.OUT_OF_RANGE, "is $length characters, longer than maxLength $it") }
            spec.pattern?.let { if (!Re2.matches(it, raw)) add(Codes.PATTERN_MISMATCH, "$shown does not match pattern $it") }
            return raw
        }

        fun checkInt(): Long? {
            if (!intSyntax.matches(raw)) {
                add(Codes.INVALID_TYPE, "$shown is not an integer")
                return null
            }
            val n = raw.toLongOrNull()
            if (n == null) {
                // An integer, just not one a 64-bit int holds (SPEC §5).
                add(Codes.OUT_OF_RANGE, "$shown is outside the 64-bit integer range")
                return null
            }
            bounds(JsonValue.Int(n), n.toDouble())
            return n
        }

        fun checkFloat(): Double? {
            val d = if (floatSyntax.matches(raw)) raw.toDoubleOrNull() else null
            if (d == null || !d.isFinite()) {
                add(Codes.INVALID_TYPE, "$shown is not a finite decimal number")
                return null
            }
            bounds(JsonValue.Float(d), d)
            return d
        }

        fun bounds(v: JsonValue, d: Double) {
            spec.min?.let { if (d < it.asDouble() || (v is JsonValue.Int && it is JsonValue.Int && v.value < it.value)) add(Codes.OUT_OF_RANGE, "$shown is below min ${it.show()}") }
            spec.max?.let { if (d > it.asDouble() || (v is JsonValue.Int && it is JsonValue.Int && v.value > it.value)) add(Codes.OUT_OF_RANGE, "$shown is above max ${it.show()}") }
        }

        fun checkBool(): Boolean? {
            val lower = raw.lowercase()
            if (lower != "true" && lower != "false" && lower !in options.lenientBools) {
                add(Codes.INVALID_TYPE, "$shown is not true or false")
                return null
            }
            return lower == "true" || lower in truthy
        }

        fun checkDuration(): Long? {
            val nanos = parseDuration(raw, spec.durationEncoding, options.hostDuration)
            if (nanos == null) {
                val example = when (spec.durationEncoding) {
                    DurationEncoding.GO -> "1m30s"
                    DurationEncoding.ISO8601 -> "PT1M30S"
                    DurationEncoding.SECONDS -> "90"
                    DurationEncoding.TIMESPAN -> "00:01:30"
                }
                add(Codes.INVALID_TYPE, "$shown is not a duration such as $example")
                return null
            }
            spec.minDuration?.let { if (nanos < Durations.parseGo(it)!!) add(Codes.OUT_OF_RANGE, "$shown is shorter than min $it") }
            spec.maxDuration?.let { if (nanos > Durations.parseGo(it)!!) add(Codes.OUT_OF_RANGE, "$shown is longer than max $it") }
            return nanos
        }

        fun checkUrl(): String? {
            if (!urlSyntax.matches(raw)) {
                add(Codes.INVALID_TYPE, "$shown is not a URL of the form scheme://...")
                return null
            }
            val scheme = raw.substringBefore("://")
            val schemes = spec.schemes
            if (schemes != null && scheme !in schemes) {
                add(Codes.INVALID_SCHEME, "scheme ${quote(scheme)} is not one of ${schemes.joinToString(", ")}")
                return raw
            }
            // The URL as it is; a secret reports its length, never its value.
            spec.maxLength?.let {
                val length = codePointCount(raw)
                if (length > it) add(Codes.OUT_OF_RANGE, "$shown is $length characters, above maxLength $it")
            }
            return raw
        }

        fun checkEnum(): String {
            spec.values?.let { values -> if (raw !in values) add(Codes.NOT_IN_ENUM, "$shown is not one of ${values.joinToString(", ")}") }
            return raw
        }

        fun checkList(): List<Any>? = when (spec.listEncoding) {
            ListEncoding.CSV -> checkItems(raw.split(spec.separator).map { if (options.trimListItems) it.trim() else it })
            ListEncoding.JSON -> checkJsonList()
            // One variable per item; callers pass the items to parse(items = ...). A single value is one item.
            ListEncoding.INDEXED -> checkItems(listOf(raw))
        }

        fun itemLabel(i: Int, item: String) = if (spec.secret) "item $i" else "item $i ${quote(item)}"

        /** Items given as strings (csv, indexed). */
        fun checkItems(items: List<String>): List<Any>? {
            val parsed: List<Any?> = if (spec.items == ListItems.INT) {
                items.mapIndexed { i, item ->
                    when {
                        !intSyntax.matches(item) -> null.also { add(Codes.INVALID_TYPE, "${itemLabel(i, item)} is not an integer") }
                        item.toLongOrNull() == null -> null.also { add(Codes.OUT_OF_RANGE, "${itemLabel(i, item)} is outside the 64-bit integer range") }
                        else -> item.toLong()
                    }
                }
            } else {
                items
            }
            return finishList(parsed)
        }

        /** A JSON array: items must be JSON strings or JSON integers, as `items` says. */
        fun checkJsonList(): List<Any>? {
            val parsed = try {
                JsonValue.parse(raw)
            } catch (e: JsonSyntaxException) {
                add(Codes.INVALID_TYPE, "is not a JSON array: ${e.message}")
                return null
            }
            if (parsed !is JsonValue.Arr) {
                add(Codes.INVALID_TYPE, "is not a JSON array")
                return null
            }
            val items = parsed.items.mapIndexed { i, x ->
                val label = if (spec.secret) "item $i" else "item $i $x"
                when {
                    spec.items == ListItems.INT && x is JsonValue.Int -> x.value
                    // The JSON reader holds integers beyond 64 bits as floats.
                    spec.items == ListItems.INT && x is JsonValue.Float && x.value % 1.0 == 0.0 && (x.value >= 9.223372036854775807E18 || x.value < -9.223372036854775808E18) ->
                        null.also { add(Codes.OUT_OF_RANGE, "$label is outside the 64-bit integer range") }
                    spec.items == ListItems.INT -> null.also { add(Codes.INVALID_TYPE, "$label is not a JSON integer") }
                    x is JsonValue.Str -> x.value
                    else -> null.also { add(Codes.INVALID_TYPE, "$label is not a JSON string") }
                }
            }
            return finishList(items)
        }

        fun finishList(items: List<Any?>): List<Any>? {
            // Each item of a string list after splitting, so a csv separator is never counted.
            if (spec.itemMinLength != null || spec.itemMaxLength != null) {
                items.forEachIndexed { i, x ->
                    if (x !is String) return@forEachIndexed
                    val length = codePointCount(x)
                    val label = itemLabel(i, x)
                    spec.itemMinLength?.let { if (length < it) add(Codes.OUT_OF_RANGE, "$label is $length characters, below itemMinLength $it") }
                    spec.itemMaxLength?.let { if (length > it) add(Codes.OUT_OF_RANGE, "$label is $length characters, above itemMaxLength $it") }
                }
            }
            items.forEachIndexed { i, x ->
                if (x !is Long) return@forEachIndexed
                val label = if (spec.secret) "item $i" else "item $i ($x)"
                spec.itemMin?.let { if (x < it) add(Codes.OUT_OF_RANGE, "$label is below itemMin $it") }
                spec.itemMax?.let { if (x > it) add(Codes.OUT_OF_RANGE, "$label is above itemMax $it") }
            }
            spec.minItems?.let { if (items.size < it) add(Codes.TOO_FEW_ITEMS, "has ${items.size} items, fewer than minItems $it") }
            spec.maxItems?.let { if (items.size > it) add(Codes.TOO_MANY_ITEMS, "has ${items.size} items, more than maxItems $it") }
            return if (items.any { it == null }) null else items.map { it!! }
        }

        fun checkJson(): JsonValue? {
            val parsed = try {
                JsonValue.parse(raw)
            } catch (e: JsonSyntaxException) {
                add(Codes.INVALID_TYPE, "is not valid JSON: ${e.message}")
                return null
            }
            // The value as received, whitespace included, not re-encoded (SPEC §4.3).
            jsonMaxLength(spec, raw)?.let {
                out += it
                return null
            }
            val schema = spec.schema ?: return parsed
            for (problem in JsonSchemaValidator.validate(schema, parsed)) {
                add(Codes.SCHEMA_MISMATCH, if (spec.secret) "does not match its schema at ${problem.path}" else problem.toString())
            }
            return parsed
        }
    }

    private val truthy = setOf("t", "1", "yes")

    /**
     * Checks a json value's wire string against `maxLength` (SPEC §4.3): the raw value as received,
     * or the compact JSON of a value that has no wire string, such as one from a config-file overlay.
     * Returns the violation, or null when it fits.
     */
    public fun jsonMaxLength(spec: VarSpec, wire: String): Violation? {
        val max = spec.maxLength ?: return null
        val length = codePointCount(wire)
        return if (length > max) Violation(Codes.OUT_OF_RANGE, spec.name, "is $length characters of JSON, above maxLength $max") else null
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
