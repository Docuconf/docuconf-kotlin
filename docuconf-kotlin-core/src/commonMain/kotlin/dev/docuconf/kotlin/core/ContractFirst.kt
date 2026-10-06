package dev.docuconf.kotlin.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Contract-first mode (SPEC §11.2 item 11): validates an environment against a contract given as
 * JSON (`cue export contract.cue --out json`), with no Kotlin declaration, and returns typed values.
 *
 * Every encoding of SPEC §5 is parsed: lists as `csv` (with `separator`), `json` or `indexed`
 * (`NAME__0`, `NAME__1`, ...), durations as `go`, `iso8601`, `seconds` or `timespan`. Values are
 * checked by [ValueChecks], the same code that checks a declared config class at boot.
 *
 * This mode covers variables. File inputs and overlays in the contract are not read or checked.
 *
 * ```
 * val contract = ContractFirst.parse(File("contract.json").readText())
 * val port: Long? = ContractFirst.load(contract, System.getenv()).long("PORT")
 * ```
 */
public object ContractFirst {
    /** The outcome of [check]. */
    public sealed class Result {
        public abstract val warnings: List<String>

        public data class Success(val values: ContractValues, override val warnings: List<String>) : Result()

        public data class Failure(val violations: List<Violation>, override val warnings: List<String>) : Result()
    }

    /**
     * Reads a contract exported as JSON. Throws [DeclarationException] when it is not a valid
     * contract (the same checks a Kotlin declaration gets: names, descriptions, defaults against
     * their constraints, RE2 patterns and so on).
     */
    public fun parse(json: String): Contract = parse(
        try {
            JsonValue.parse(json)
        } catch (e: JsonSyntaxException) {
            throw DeclarationException(listOf("contract: ${e.message}"))
        },
    )

    /** Reads a contract already parsed as JSON. See [parse]. */
    public fun parse(json: JsonValue): Contract {
        val errors = ArrayList<String>()
        val root = json as? JsonValue.Obj ?: throw DeclarationException(listOf("contract: expected a JSON object"))
        val f = root.fields
        (f["kind"] as? JsonValue.Str)?.value?.let { if (it != "ConfigContract") errors += "contract: kind must be ConfigContract, not $it" }
        (f["apiVersion"] as? JsonValue.Str)?.value?.let { if (it != API_VERSION) errors += "contract: apiVersion must be $API_VERSION, not $it" }
        val metadata = (f["metadata"] as? JsonValue.Obj)?.fields ?: emptyMap()
        val service = (metadata["name"] as? JsonValue.Str)?.value ?: "contract".also { errors += "contract: metadata.name is missing" }
        val generator = (metadata["generator"] as? JsonValue.Obj)?.fields?.let { g ->
            Generator(g.str("language") ?: "", g.str("sdk") ?: "", g.str("version") ?: "")
        } ?: Generator("", "", "")
        val vars = when (val v = f["vars"]) {
            null -> emptyList()
            is JsonValue.Obj -> v.fields.mapNotNull { (name, spec) -> readVar(name, spec, errors) }
            else -> emptyList<VarSpec>().also { errors += "contract: vars must be an object" }
        }
        if (errors.isNotEmpty()) throw DeclarationException(errors)
        val contract = Contract(service, generator, vars, appVersion = metadata.str("appVersion"))
        DeclarationChecks.require(contract)
        return contract
    }

    /** Checks [env] against [contract] and returns every violation, or the typed values. */
    public fun check(contract: Contract, env: Map<String, String>): Result {
        val warnings = DeclarationChecks.check(contract).warnings.toMutableList()
        val violations = ArrayList<Violation>()
        val values = LinkedHashMap<String, Any?>()
        for (spec in contract.vars.sortedBy { it.name }) {
            val parsed = if (spec.type == VarType.LIST && spec.listEncoding == ListEncoding.INDEXED) {
                ValueChecks.parse(spec, null, items = ValueChecks.indexedItems(spec.name, env))
            } else {
                ValueChecks.parse(spec, env[spec.name])
            }
            violations += parsed.violations
            val set = parsed.value != null
            if (set) spec.deprecated?.let { d -> warnings += "${spec.name} is deprecated: ${d.message}" + (d.replacedBy?.let { " Use $it." } ?: "") }
            values[spec.name] = when {
                set -> typed(spec, parsed.value)
                spec.default != null -> fromDefault(spec, spec.default)
                else -> null
            }
        }
        return if (violations.isEmpty()) Result.Success(ContractValues(contract, values), warnings) else Result.Failure(violations, warnings)
    }

    /** Like [check], but throws [ConfigViolationException] listing every violation. */
    public fun load(contract: Contract, env: Map<String, String>): ContractValues = when (val r = check(contract, env)) {
        is Result.Success -> r.values
        is Result.Failure -> throw ConfigViolationException(r.violations)
    }

    private const val API_VERSION = "docuconf.dev/v1alpha1"

    private fun typed(spec: VarSpec, value: Any?): Any? = if (spec.type == VarType.DURATION) (value as Long).nanoseconds else value

    /** A default from the contract (platform form: Go durations, typed lists) as a typed value. */
    private fun fromDefault(spec: VarSpec, d: JsonValue): Any? = when (spec.type) {
        VarType.STRING, VarType.URL, VarType.ENUM -> (d as JsonValue.Str).value
        VarType.INT -> (d as JsonValue.Int).value
        VarType.FLOAT -> d.asDouble()
        VarType.BOOL -> (d as JsonValue.Bool).value
        VarType.DURATION -> Durations.parseGo((d as JsonValue.Str).value)!!.nanoseconds
        VarType.LIST -> (d as JsonValue.Arr).items.map { if (spec.items == ListItems.INT) (it as JsonValue.Int).value else (it as JsonValue.Str).value }
        VarType.JSON -> d
    }

    private fun Map<String, JsonValue>.str(key: String): String? = (this[key] as? JsonValue.Str)?.value

    private fun readVar(name: String, json: JsonValue, errors: MutableList<String>): VarSpec? {
        val p = "$name:"
        val f = (json as? JsonValue.Obj)?.fields ?: return null.also { errors += "$p expected an object" }
        fun str(key: String): String? = when (val v = f[key]) {
            null, JsonValue.Null -> null
            is JsonValue.Str -> v.value
            else -> null.also { errors += "$p $key must be a string" }
        }
        fun bool(key: String): Boolean = when (val v = f[key]) {
            null, JsonValue.Null -> false
            is JsonValue.Bool -> v.value
            else -> false.also { errors += "$p $key must be true or false" }
        }
        fun long(key: String): Long? = when (val v = f[key]) {
            null, JsonValue.Null -> null
            is JsonValue.Int -> v.value
            else -> null.also { errors += "$p $key must be an integer" }
        }
        fun int(key: String): Int? = long(key)?.let { if (it in 0..Int.MAX_VALUE) it.toInt() else null.also { errors += "$p $key is out of range" } }
        fun strings(key: String): List<String>? = when (val v = f[key]) {
            null, JsonValue.Null -> null
            is JsonValue.Arr -> v.items.map { (it as? JsonValue.Str)?.value ?: "".also { errors += "$p $key must hold strings" } }
            else -> null.also { errors += "$p $key must be a list of strings" }
        }
        fun number(key: String): JsonValue? = when (val v = f[key]) {
            null, JsonValue.Null -> null
            is JsonValue.Int, is JsonValue.Float -> v
            else -> null.also { errors += "$p $key must be a number" }
        }

        val typeName = str("type") ?: return null.also { errors += "$p type is missing" }
        val type = VarType.entries.firstOrNull { it.wire == typeName } ?: return null.also { errors += "$p unknown type \"$typeName\"" }
        val encoding = str("encoding")
        val durationEncoding = if (type == VarType.DURATION) {
            DurationEncoding.entries.firstOrNull { it.wire == (encoding ?: "go") } ?: DurationEncoding.GO.also { errors += "$p unknown duration encoding \"$encoding\"" }
        } else {
            DurationEncoding.GO
        }
        val listEncoding = if (type == VarType.LIST) {
            ListEncoding.entries.firstOrNull { it.wire == (encoding ?: "csv") } ?: ListEncoding.CSV.also { errors += "$p unknown list encoding \"$encoding\"" }
        } else {
            ListEncoding.CSV
        }
        val items = if (type == VarType.LIST) {
            str("items")?.let { i -> ListItems.entries.firstOrNull { it.wire == i } ?: null.also { errors += "$p unknown list item type \"$i\"" } }
        } else {
            null
        }
        val deprecated = (f["deprecated"] as? JsonValue.Obj)?.fields?.let { d -> Deprecation(d.str("message") ?: "", d.str("replacedBy")) }
        return VarSpec(
            name = name,
            type = type,
            description = str("description") ?: "",
            required = bool("required"),
            secret = bool("secret"),
            group = str("group"),
            examples = strings("examples") ?: emptyList(),
            configKey = str("configKey"),
            deprecated = deprecated,
            default = f["default"]?.takeIf { it != JsonValue.Null },
            min = if (type == VarType.INT || type == VarType.FLOAT) number("min") else null,
            max = if (type == VarType.INT || type == VarType.FLOAT) number("max") else null,
            minDuration = if (type == VarType.DURATION) str("min") else null,
            maxDuration = if (type == VarType.DURATION) str("max") else null,
            durationEncoding = durationEncoding,
            minLength = int("minLength"),
            maxLength = int("maxLength"),
            pattern = str("pattern"),
            schemes = strings("schemes"),
            values = strings("values"),
            items = items,
            listEncoding = listEncoding,
            separator = str("separator") ?: ",",
            minItems = int("minItems"),
            maxItems = int("maxItems"),
            itemMin = long("itemMin"),
            itemMax = long("itemMax"),
            schema = f["schema"]?.takeIf { it != JsonValue.Null },
        )
    }
}

/**
 * Typed values from [ContractFirst], by variable name. An unset optional variable without a
 * default is null. The accessors throw [IllegalArgumentException] for a name the contract does not
 * declare, or a variable of another type.
 */
public class ContractValues internal constructor(public val contract: Contract, private val values: Map<String, Any?>) {
    /** The declared variable names. */
    public val names: Set<String> get() = values.keys

    /**
     * The typed value: `String` (string, url, enum), `Long` (int), `Double` (float), `Boolean` (bool),
     * [Duration] (duration), `List<String>` or `List<Long>` (list), [JsonValue] (json), or null.
     */
    public operator fun get(name: String): Any? {
        require(name in values) { "$name is not declared in the contract" }
        return values[name]
    }

    public fun string(name: String): String? = typed(name, VarType.STRING, VarType.URL, VarType.ENUM)
    public fun long(name: String): Long? = typed(name, VarType.INT)
    public fun double(name: String): Double? = typed(name, VarType.FLOAT)
    public fun boolean(name: String): Boolean? = typed(name, VarType.BOOL)
    public fun duration(name: String): Duration? = typed(name, VarType.DURATION)
    public fun json(name: String): JsonValue? = typed(name, VarType.JSON)

    public fun stringList(name: String): List<String>? = list(name, ListItems.STRING)
    public fun longList(name: String): List<Long>? = list(name, ListItems.INT)

    /** Every value as JSON: durations in canonical Go form (`1m30s`), lists as arrays, absent values as null. */
    public fun toJson(): JsonValue.Obj = JsonValue.Obj(
        values.mapValues { (_, v) ->
            when (v) {
                is Duration -> JsonValue.Str(Durations.formatGo(v.inWholeNanoseconds))
                else -> JsonValue.of(v)
            }
        },
    )

    override fun toString(): String {
        val secrets = contract.vars.filter { it.secret }.map { it.name }.toSet()
        return values.entries.joinToString(", ", "ContractValues(", ")") { (k, v) -> "$k=" + if (k in secrets && v != null) "****" else v.toString() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> typed(name: String, vararg types: VarType): T? {
        val spec = contract.variable(name) ?: throw IllegalArgumentException("$name is not declared in the contract")
        require(spec.type in types) { "$name is a ${spec.type.wire}, not a ${types.joinToString(" or ") { it.wire }}" }
        return values[name] as T?
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> list(name: String, items: ListItems): List<T>? {
        val spec = contract.variable(name) ?: throw IllegalArgumentException("$name is not declared in the contract")
        require(spec.type == VarType.LIST && spec.items == items) { "$name is not a list of ${items.wire}" }
        return values[name] as List<T>?
    }
}
