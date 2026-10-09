package dev.docuconf.kotlin.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Contract-first mode (SPEC §11.2 item 11): validates an environment against a contract given as
 * JSON (`cue export contract.cue --out json`), with no Kotlin declaration, and returns typed values.
 *
 * Every encoding of SPEC §5 is parsed, exactly: lists and key sets as `csv` (with `separator`),
 * `json` or `indexed` (`NAME__0`, `NAME__1`, ...), durations as `go`, `iso8601`, `seconds` or
 * `timespan`. Values are checked by [ValueChecks], the same code that checks a declared config class
 * at boot. Values are layered as SPEC §4.4 and §4.7 say: the variable's default, then the selected
 * profile's default, then a config-file overlay, then the environment.
 *
 * File inputs and overlays are files, which this multiplatform module does not read: pass [Inputs]
 * to read them. `docuconf-hoplite` does, with `Docuconf.checkContract` and `Docuconf.loadContract`,
 * which check every file input as boot validation does. Without [Inputs], file inputs and overlays in
 * the contract are not read.
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

    /** A file input after [Inputs.loadFile]: its typed value (null when absent or invalid) and its violations. */
    public data class LoadedFile(val value: Any?, val violations: List<Violation> = emptyList())

    /** An overlay after [Inputs.readOverlay]: its top-level object (null when absent or invalid) and its violations. */
    public data class LoadedOverlay(val root: JsonValue.Obj?, val violations: List<Violation> = emptyList())

    /**
     * Reads what lives in files: file inputs (SPEC §4.6) and config-file overlays (SPEC §4.7), both
     * under `DOCUCONF_FILE_ROOT`. `docuconf-hoplite` implements it.
     */
    public interface Inputs {
        /**
         * Loads and checks one file input. Its value is the file's data as a [JsonValue] for a `config`
         * file, its text as a `String` for a `text` file, and an object of the reader's choosing for
         * the others; null when it is absent.
         */
        public fun loadFile(spec: FileSpec, env: Map<String, String>): LoadedFile

        /**
         * Reads one overlay as native values. A missing file is not an error (a null root); one that
         * does not parse, or does not hold an object, is `file_malformed` for the overlay's name.
         */
        public fun readOverlay(spec: OverlaySpec, env: Map<String, String>): LoadedOverlay
    }

    /**
     * Reads a contract exported as JSON. Throws [DeclarationException] when it is not a valid
     * contract (the same checks a Kotlin declaration gets: names, descriptions, defaults against
     * their constraints, RE2 patterns, deprecations, key sets, profiles and so on).
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
        val vars = section(f, "vars", errors) { name, spec -> readVar(name, spec, errors) }
        val files = section(f, "files", errors) { name, spec -> readFile(name, spec, errors) }
        val overlays = section(f, "overlays", errors) { name, spec -> readOverlay(name, spec, errors) }
        val profiles = when (val p = f["profiles"]) {
            null, JsonValue.Null -> null
            is JsonValue.Obj -> readProfiles(p, errors)
            else -> null.also { errors += "contract: profiles must be an object" }
        }
        if (errors.isNotEmpty()) throw DeclarationException(errors)
        val contract = Contract(service, generator, vars, files, metadata.str("appVersion"), overlays, profiles)
        DeclarationChecks.require(contract)
        return contract
    }

    /**
     * Checks [env] against [contract] and returns every violation, or the typed values. [inputs]
     * reads file inputs and overlays; without it they are not read.
     */
    public fun check(contract: Contract, env: Map<String, String>, inputs: Inputs? = null): Result {
        val warnings = DeclarationChecks.check(contract).warnings.toMutableList()
        val violations = ArrayList<Violation>()
        val values = LinkedHashMap<String, Any?>()

        // Overlays first: a value comes from the environment, else an overlay, else a profile file,
        // else the variable's default (SPEC §4.7).
        val overlays = if (inputs == null) emptyList() else contract.overlays.map { o -> o to inputs.readOverlay(o, env) }
        overlays.forEach { violations += it.second.violations }
        val profile = selectedProfile(contract, env)

        for (spec in contract.vars.sortedBy { it.name }) {
            val fromOverlay = if (spec.configKey == null || spec.name == contract.profiles?.selector) {
                null
            } else {
                overlays.firstNotNullOfOrNull { (o, loaded) -> loaded.root?.let { at(it, spec.configKey.split(o.keySeparator)) }?.let { o to it } }
            }
            val inEnv = envPresent(spec, env)
            if (fromOverlay != null && spec.secret) {
                // Overlays are ConfigMaps: a secret never comes from one, and its value is never shown.
                violations += Violation(Codes.INVALID_TYPE, spec.name, "is set in overlay ${fromOverlay.first.name}; overlays are ConfigMaps, so a secret must come from the environment")
                continue
            }
            if (inEnv || fromOverlay != null) {
                spec.deprecated?.let { warnings += deprecationWarning(spec.name, it) }
            }
            val parsed: ValueChecks.Parsed? = when {
                inEnv -> {
                    if (fromOverlay != null) warnings += "${spec.name} is set in the environment and in overlay ${fromOverlay.first.name}; the environment wins"
                    if (spec.listEncoding == ListEncoding.INDEXED && (spec.type == VarType.LIST || spec.type == VarType.KEY_SET)) {
                        ValueChecks.parseIndexed(spec, env)
                    } else {
                        ValueChecks.parse(spec, env[spec.name])
                    }
                }
                fromOverlay != null -> parseNative(spec, fromOverlay.second, fromOverlay.first)
                else -> null
            }
            if (parsed != null) {
                violations += parsed.violations
                values[spec.name] = parsed.value?.let { typed(spec, it) }
                continue
            }
            val profileDefault = profile?.let { contract.profiles!!.defaults[it]?.get(spec.name) }
            values[spec.name] = when {
                profileDefault != null -> fromDefault(spec, profileDefault)
                spec.default != null -> fromDefault(spec, spec.default)
                else -> {
                    if (spec.required) violations += Violation(Codes.MISSING_REQUIRED, spec.name, "required, but not set")
                    null
                }
            }
        }

        if (inputs != null) {
            for (file in contract.files.sortedBy { it.name }) {
                val loaded = inputs.loadFile(file, env)
                violations += loaded.violations
                values[file.name] = loaded.value
                if (loaded.value != null) file.deprecated?.let { warnings += deprecationWarning("file ${file.name}", it) }
            }
        }
        return if (violations.isEmpty()) Result.Success(ContractValues(contract, values), warnings) else Result.Failure(violations, warnings)
    }

    /** Like [check], but throws [ConfigViolationException] listing every violation. */
    public fun load(contract: Contract, env: Map<String, String>, inputs: Inputs? = null): ContractValues = when (val r = check(contract, env, inputs)) {
        is Result.Success -> r.values
        is Result.Failure -> throw ConfigViolationException(r.violations)
    }

    /**
     * The warning for a deprecated input that is set (SPEC §11.2): it names the input and the
     * message, never the value.
     */
    public fun deprecationWarning(input: String, d: Deprecation): String =
        "$input is deprecated: ${d.message}" + (d.replacedBy?.let { " (replaced by $it)" } ?: "")

    /**
     * The profile in effect (SPEC §4.4): the selector's value when the environment sets it, read as
     * its type reads it (for a `string` selector the empty string is a value), else `profiles.default`.
     */
    public fun selectedProfile(contract: Contract, env: Map<String, String>): String? {
        val p = contract.profiles ?: return null
        val selector = contract.variable(p.selector)
        val raw = env[p.selector]
        return if (selector == null || ValueChecks.isUnset(selector, raw)) p.default else raw
    }

    /**
     * Checks a native value from a config-file overlay exactly like an environment value (SPEC §4.7):
     * it is converted to the wire string it stands for (a string as it is, a boolean as `true` or
     * `false`, an integral number as a base-10 integer, any other number in shortest round-trip
     * decimal, a list item by item, and a `json` value as its compact JSON), then parsed in the
     * variable's own encoding. An object or list where the type takes a scalar is `invalid_type`.
     */
    public fun parseNative(spec: VarSpec, value: JsonValue, overlay: OverlaySpec): ValueChecks.Parsed {
        val where = "(from overlay ${overlay.name}, key ${spec.configKey})"
        val optional = spec.copy(required = false)
        fun bad(what: String) = ValueChecks.Parsed(listOf(Violation(Codes.INVALID_TYPE, spec.name, "is $what, not a ${spec.type.wire} $where")), null)
        fun located(p: ValueChecks.Parsed) = p.copy(violations = p.violations.map { it.copy(message = "${it.message} $where") })
        return when (spec.type) {
            VarType.JSON -> located(ValueChecks.parse(optional, value.toString()))
            VarType.LIST, VarType.KEY_SET -> when (value) {
                is JsonValue.Arr -> {
                    val items = value.items.map { scalarWire(it) ?: return bad("a list holding ${kind(it)}") }
                    located(ValueChecks.parseItems(optional, items))
                }
                is JsonValue.Obj -> bad(kind(value))
                else -> located(ValueChecks.parse(optional, scalarWire(value)))
            }
            else -> located(ValueChecks.parse(optional, scalarWire(value) ?: return bad(kind(value))))
        }
    }

    /** The wire string a native scalar stands for (SPEC §4.7), or null for an object, list or null. */
    public fun scalarWire(v: JsonValue): String? = when (v) {
        is JsonValue.Str -> v.value
        is JsonValue.Bool -> v.value.toString()
        is JsonValue.Int -> v.value.toString()
        is JsonValue.Float -> when {
            v.value.isFinite() && v.value % 1.0 == 0.0 && v.value >= -9.223372036854775808E18 && v.value < 9.223372036854775807E18 -> v.value.toLong().toString()
            else -> formatFloat(v.value)
        }
        else -> null
    }

    private fun kind(v: JsonValue) = when (v) {
        is JsonValue.Obj -> "an object"
        is JsonValue.Arr -> "a list"
        JsonValue.Null -> "null"
        else -> "a scalar"
    }

    /** The value at a key path, matched exactly (case-sensitive); null when absent or `null` (unset). */
    private fun at(root: JsonValue.Obj, path: List<String>): JsonValue? {
        var node: JsonValue = root
        for (segment in path) node = (node as? JsonValue.Obj)?.fields?.get(segment) ?: return null
        return node.takeIf { it != JsonValue.Null }
    }

    /** Whether the environment sets [spec]: a non-empty value (any value for a string), or any item of an indexed list. */
    private fun envPresent(spec: VarSpec, env: Map<String, String>): Boolean {
        if (spec.listEncoding == ListEncoding.INDEXED && (spec.type == VarType.LIST || spec.type == VarType.KEY_SET)) {
            val prefix = "${spec.name}__"
            return env.keys.any { it.startsWith(prefix) && indexSuffix.matches(it.substring(prefix.length)) }
        }
        return !ValueChecks.isUnset(spec, env[spec.name])
    }

    private val indexSuffix = Regex("^(0|[1-9][0-9]*)$")

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
        // A key set is secret, so it has no default (DeclarationChecks rejects one).
        VarType.KEY_SET -> null
        VarType.JSON -> d
    }

    private fun Map<String, JsonValue>.str(key: String): String? = (this[key] as? JsonValue.Str)?.value

    private fun <T> section(f: Map<String, JsonValue>, key: String, errors: MutableList<String>, read: (String, JsonValue) -> T?): List<T> = when (val v = f[key]) {
        null, JsonValue.Null -> emptyList()
        is JsonValue.Obj -> v.fields.mapNotNull { (name, spec) -> read(name, spec) }
        else -> emptyList<T>().also { errors += "contract: $key must be an object" }
    }

    /** Typed readers for one object's fields; problems go to [errors], prefixed with [p]. */
    private class Fields(val f: Map<String, JsonValue>, val p: String, val errors: MutableList<String>) {
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

        fun json(key: String): JsonValue? = f[key]?.takeIf { it != JsonValue.Null }

        fun deprecated(): Deprecation? = when (val v = f["deprecated"]) {
            null, JsonValue.Null -> null
            is JsonValue.Obj -> Deprecation(v.fields.str("message") ?: "", v.fields.str("replacedBy"))
            else -> null.also { errors += "$p deprecated must be an object" }
        }

        fun reload(): Reload = when (val r = str("reload")) {
            null -> Reload.RESTART
            else -> Reload.entries.firstOrNull { it.wire == r } ?: Reload.RESTART.also { errors += "$p unknown reload \"$r\"" }
        }

        fun <E> enum(key: String, entries: List<E>, wire: (E) -> String): E? = str(key)?.let { s ->
            entries.firstOrNull { wire(it) == s } ?: null.also { errors += "$p unknown $key \"$s\"" }
        }

        private fun Map<String, JsonValue>.str(key: String): String? = (this[key] as? JsonValue.Str)?.value
    }

    private fun readVar(name: String, json: JsonValue, errors: MutableList<String>): VarSpec? {
        val p = "$name:"
        val f = Fields((json as? JsonValue.Obj)?.fields ?: return null.also { errors += "$p expected an object" }, p, errors)
        val typeName = f.str("type") ?: return null.also { errors += "$p type is missing" }
        val type = VarType.entries.firstOrNull { it.wire == typeName } ?: return null.also { errors += "$p unknown type \"$typeName\"" }
        val encoding = f.str("encoding")
        val listLike = type == VarType.LIST || type == VarType.KEY_SET
        val durationEncoding = if (type == VarType.DURATION) {
            DurationEncoding.entries.firstOrNull { it.wire == (encoding ?: "go") } ?: DurationEncoding.GO.also { errors += "$p unknown duration encoding \"$encoding\"" }
        } else {
            DurationEncoding.GO
        }
        val listEncoding = if (listLike) {
            ListEncoding.entries.firstOrNull { it.wire == (encoding ?: "csv") } ?: ListEncoding.CSV.also { errors += "$p unknown list encoding \"$encoding\"" }
        } else {
            ListEncoding.CSV
        }
        val items = if (type == VarType.LIST) f.enum("items", ListItems.entries) { it.wire } else null
        return VarSpec(
            name = name,
            type = type,
            description = f.str("description") ?: "",
            details = f.str("details"),
            required = f.bool("required"),
            secret = f.bool("secret"),
            group = f.str("group"),
            examples = f.strings("examples") ?: emptyList(),
            configKey = f.str("configKey"),
            deprecated = f.deprecated(),
            default = f.json("default"),
            min = if (type == VarType.INT || type == VarType.FLOAT) f.number("min") else null,
            max = if (type == VarType.INT || type == VarType.FLOAT) f.number("max") else null,
            minDuration = if (type == VarType.DURATION) f.str("min") else null,
            maxDuration = if (type == VarType.DURATION) f.str("max") else null,
            durationEncoding = durationEncoding,
            minLength = f.int("minLength"),
            maxLength = f.int("maxLength"),
            pattern = f.str("pattern"),
            schemes = f.strings("schemes"),
            values = f.strings("values"),
            items = items,
            listEncoding = listEncoding,
            separator = f.str("separator") ?: ",",
            minItems = f.int("minItems"),
            maxItems = f.int("maxItems"),
            itemMin = f.long("itemMin"),
            itemMax = f.long("itemMax"),
            itemMinLength = f.int("itemMinLength"),
            itemMaxLength = f.int("itemMaxLength"),
            minKeys = f.int("minKeys"),
            maxKeys = f.int("maxKeys"),
            keyMinLength = f.int("keyMinLength"),
            keyMaxLength = f.int("keyMaxLength"),
            schema = f.json("schema"),
        )
    }

    private fun readFile(name: String, json: JsonValue, errors: MutableList<String>): FileSpec? {
        val p = "file $name:"
        val f = Fields((json as? JsonValue.Obj)?.fields ?: return null.also { errors += "$p expected an object" }, p, errors)
        val type = f.enum("type", FileType.entries) { it.wire } ?: return null.also { if (f.str("type") == null) errors += "$p type is missing" }
        return FileSpec(
            name = name,
            type = type,
            description = f.str("description") ?: "",
            details = f.str("details"),
            path = f.str("path") ?: "".also { errors += "$p path is missing" },
            required = f.bool("required"),
            secret = f.bool("secret"),
            group = f.str("group"),
            deprecated = f.deprecated(),
            pathEnv = f.str("pathEnv"),
            reload = f.reload(),
            maxSize = f.long("maxSize"),
            format = if (type == FileType.CONFIG) f.enum("format", ConfigFormat.entries) { it.wire } else null,
            schema = f.json("schema"),
            dnsNames = f.strings("dnsNames"),
            keyAlgorithms = f.strings("keyAlgorithms")?.mapNotNull { a -> KeyAlgorithm.entries.firstOrNull { it.wire == a } ?: null.also { errors += "$p unknown key algorithm \"$a\"" } },
            minRemaining = f.str("minRemaining"),
            requireCA = f.bool("requireCA"),
            minCertificates = f.int("minCertificates"),
            keystoreFormat = if (type == FileType.KEYSTORE) f.enum("format", KeystoreFormat.entries) { it.wire } else null,
            passwordVar = f.str("passwordVar"),
            pattern = f.str("pattern"),
            minLength = f.int("minLength"),
            maxLength = f.int("maxLength"),
        )
    }

    private fun readOverlay(name: String, json: JsonValue, errors: MutableList<String>): OverlaySpec? {
        val p = "overlay $name:"
        val f = Fields((json as? JsonValue.Obj)?.fields ?: return null.also { errors += "$p expected an object" }, p, errors)
        return OverlaySpec(
            name = name,
            format = f.enum("format", ConfigFormat.entries) { it.wire } ?: return null.also { if (f.str("format") == null) errors += "$p format is missing" },
            path = f.str("path") ?: return null.also { errors += "$p path is missing" },
            keySeparator = f.str("keySeparator") ?: return null.also { errors += "$p keySeparator is missing" },
            description = f.str("description"),
            reload = f.reload(),
        )
    }

    private fun readProfiles(json: JsonValue.Obj, errors: MutableList<String>): ProfilesSpec? {
        val f = Fields(json.fields, "profiles:", errors)
        val selector = f.str("selector") ?: return null.also { errors += "profiles: selector is missing" }
        val default = f.str("default") ?: return null.also { errors += "profiles: default is missing" }
        val defaults = when (val d = json.fields["defaults"]) {
            null, JsonValue.Null -> emptyMap()
            is JsonValue.Obj -> d.fields.mapValues { (profile, values) ->
                (values as? JsonValue.Obj)?.fields ?: emptyMap<String, JsonValue>().also { errors += "profiles: defaults.$profile must be an object" }
            }
            else -> emptyMap<String, Map<String, JsonValue>>().also { errors += "profiles: defaults must be an object" }
        }
        return ProfilesSpec(selector, default, defaults)
    }
}

/**
 * Typed values from [ContractFirst], by variable or file input name. An unset optional variable
 * without a default, and an absent optional file, is null. The accessors throw
 * [IllegalArgumentException] for a name the contract does not declare, or an input of another type.
 */
public class ContractValues internal constructor(public val contract: Contract, private val values: Map<String, Any?>) {
    /** The declared variable names, then the file input names when they were read. */
    public val names: Set<String> get() = values.keys

    /**
     * The typed value: `String` (string, url, enum), `Long` (int), `Double` (float), `Boolean` (bool),
     * [Duration] (duration), `List<String>` or `List<Long>` (list), [KeySet] (keySet), [JsonValue]
     * (json), a file input's value (see [file]), or null.
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
    public fun keySet(name: String): KeySet? = typed(name, VarType.KEY_SET)

    public fun stringList(name: String): List<String>? = list(name, ListItems.STRING)
    public fun longList(name: String): List<Long>? = list(name, ListItems.INT)

    /**
     * A file input's value, or null when it is absent: the data as a [JsonValue] for a `config` file,
     * the text as a `String` for a `text` file, and the reader's object for the others (in
     * `docuconf-hoplite`: `TlsKeyPair`, `CaBundle`, `Keystore`, `BinaryFile`).
     */
    public fun file(name: String): Any? {
        require(contract.file(name) != null && name in values) { "$name is not a file input read from the contract" }
        return values[name]
    }

    /**
     * Every value as JSON, as the conformance suite writes it (SPEC §12): durations in canonical Go
     * form (`1m30s`), lists and key sets as arrays, absent values as null; a `config` file as its data,
     * a `text` file as its text, and any other file input as `true` when present.
     */
    public fun toJson(): JsonValue.Obj = JsonValue.Obj(
        values.mapValues { (name, v) ->
            val file = contract.file(name)
            when {
                v == null -> JsonValue.Null
                file != null -> when (file.type) {
                    FileType.CONFIG -> v as JsonValue
                    FileType.TEXT -> JsonValue.Str(v as String)
                    else -> JsonValue.Bool(true)
                }
                v is Duration -> JsonValue.Str(Durations.formatGo(v.inWholeNanoseconds))
                v is KeySet -> JsonValue.Arr(v.keys.map { JsonValue.Str(it) })
                else -> JsonValue.of(v)
            }
        },
    )

    override fun toString(): String {
        val secrets = contract.vars.filter { it.secret }.map { it.name }.toSet() + contract.files.filter { it.isSecret }.map { it.name }
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
