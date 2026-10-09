package dev.docuconf.kotlin.core

/** Contract variable types (SPEC §4.3). [wire] is the name used in the contract. */
public enum class VarType(public val wire: String) {
    STRING("string"),
    INT("int"),
    FLOAT("float"),
    BOOL("bool"),
    DURATION("duration"),
    URL("url"),
    ENUM("enum"),
    LIST("list"),
    /** A set of secret keys that are all valid at once, for rotation without an outage (SPEC §4.3). */
    KEY_SET("keySet"),
    JSON("json"),
}

/** How a duration reaches the app as a string (SPEC §5). */
public enum class DurationEncoding(public val wire: String) {
    GO("go"),
    ISO8601("iso8601"),
    SECONDS("seconds"),
    TIMESPAN("timespan"),
}

/** How a list reaches the app as a string (SPEC §5). */
public enum class ListEncoding(public val wire: String) {
    CSV("csv"),
    JSON("json"),
    INDEXED("indexed"),
}

/** List item types. */
public enum class ListItems(public val wire: String) {
    STRING("string"),
    INT("int"),
}

/** File input types (SPEC §4.6). */
public enum class FileType(public val wire: String) {
    CONFIG("config"),
    TLS("tls"),
    CA_BUNDLE("caBundle"),
    KEYSTORE("keystore"),
    TEXT("text"),
    BINARY("binary"),
}

/** Formats of a `config` file input. */
public enum class ConfigFormat(public val wire: String) {
    JSON("json"),
    YAML("yaml"),
    TOML("toml"),
}

/** Formats of a `keystore` file input. */
public enum class KeystoreFormat(public val wire: String) {
    PKCS12("pkcs12"),
    JKS("jks"),
}

/** TLS key algorithms. */
public enum class KeyAlgorithm(public val wire: String) {
    RSA("RSA"),
    ECDSA("ECDSA"),
    ED25519("Ed25519"),
}

/** How the app picks up a changed file (SPEC §4.6.2). */
public enum class Reload(public val wire: String) {
    RESTART("restart"),
    WATCH("watch"),
}

/**
 * `deprecated` on a variable or file input (SPEC §4.2): the platform should stop setting it. [message]
 * says what to use instead, or why the input is going away (not blank, at most 500 characters), and
 * [replacedBy] names the input that replaces it. A required input cannot be deprecated.
 */
public data class Deprecation(val message: String, val replacedBy: String? = null)

/** One environment variable in the contract (SPEC §4.2). Defaults are held as [JsonValue]s. */
public data class VarSpec(
    val name: String,
    val type: VarType,
    val description: String,
    val required: Boolean = false,
    val secret: Boolean = false,
    val group: String? = null,
    val examples: List<String> = emptyList(),
    val configKey: String? = null,
    val deprecated: Deprecation? = null,
    val default: JsonValue? = null,
    // int and float bounds
    val min: JsonValue? = null,
    val max: JsonValue? = null,
    // duration bounds, Go syntax
    val minDuration: String? = null,
    val maxDuration: String? = null,
    val durationEncoding: DurationEncoding = DurationEncoding.GO,
    // string; maxLength also bounds a url or json value (SPEC §4.3), in characters (Unicode code points)
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    // url
    val schemes: List<String>? = null,
    // enum
    val values: List<String>? = null,
    // list and keySet: a key set travels in a list's encodings, with the same separator (SPEC §5)
    val items: ListItems? = null,
    val listEncoding: ListEncoding = ListEncoding.CSV,
    val separator: String = ",",
    val minItems: Int? = null,
    val maxItems: Int? = null,
    // int list item bounds
    val itemMin: Long? = null,
    val itemMax: Long? = null,
    // string list item lengths, in characters (Unicode code points)
    val itemMinLength: Int? = null,
    val itemMaxLength: Int? = null,
    // keySet: the number of keys (null is the default, 1 and 2) and each key's length in characters
    val minKeys: Int? = null,
    val maxKeys: Int? = null,
    val keyMinLength: Int? = null,
    val keyMaxLength: Int? = null,
    // json
    val schema: JsonValue? = null,
    /** Longer docs in CommonMark (SPEC §4.2): the KDoc after its first sentence, or `@Doc(details = ...)`. Never read at runtime. */
    val details: String? = null,
)

/** One file input in the contract (SPEC §4.6). */
public data class FileSpec(
    val name: String,
    val type: FileType,
    val description: String,
    val path: String,
    val required: Boolean = false,
    val secret: Boolean = false,
    val group: String? = null,
    val deprecated: Deprecation? = null,
    val pathEnv: String? = null,
    val reload: Reload = Reload.RESTART,
    val maxSize: Long? = null,
    // config
    val format: ConfigFormat? = null,
    val schema: JsonValue? = null,
    // tls
    val dnsNames: List<String>? = null,
    val keyAlgorithms: List<KeyAlgorithm>? = null,
    val minRemaining: String? = null,
    val requireCA: Boolean = false,
    // caBundle
    val minCertificates: Int? = null,
    // keystore
    val keystoreFormat: KeystoreFormat? = null,
    val passwordVar: String? = null,
    // text
    val pattern: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    /** Longer docs in CommonMark (SPEC §4.2): the KDoc after its first sentence, or `@Doc(details = ...)`. Never read at runtime. */
    val details: String? = null,
) {
    /** TLS key pairs and keystores are always secret (SPEC §4.6). */
    val isSecret: Boolean get() = secret || type == FileType.TLS || type == FileType.KEYSTORE
}

/**
 * A config-file overlay (SPEC §4.7): one more file in the host's format, mounted by the platform and
 * layered between the files baked into the image and environment variables. Each variable it may
 * carry is written at the variable's `configKey`, split on [keySeparator].
 */
public data class OverlaySpec(
    val name: String,
    val format: ConfigFormat,
    val path: String,
    val keySeparator: String,
    val description: String? = null,
    val reload: Reload = Reload.RESTART,
) {
    /** The directory the platform mounts. */
    val mountDir: String get() = path.substringBeforeLast('/').ifEmpty { "/" }
}

/**
 * Profiles (SPEC §4.4): values from per-profile config files baked into the image. [selector] is the
 * variable that names the profile, [default] the profile in effect when it is unset, and [defaults]
 * each profile's values by variable name, in the platform's typed form (as a variable's `default`).
 */
public data class ProfilesSpec(
    val selector: String,
    val default: String,
    val defaults: Map<String, Map<String, JsonValue>> = emptyMap(),
)

/** `metadata.generator`. */
public data class Generator(val language: String, val sdk: String, val version: String)

/** A service's declaration: everything that goes into `contract.cue`. Vars, files and overlays are sorted by name on export. */
public data class Contract(
    val service: String,
    val generator: Generator,
    val vars: List<VarSpec>,
    val files: List<FileSpec> = emptyList(),
    val appVersion: String? = null,
    val overlays: List<OverlaySpec> = emptyList(),
    val profiles: ProfilesSpec? = null,
) {
    public fun variable(name: String): VarSpec? = vars.firstOrNull { it.name == name }
    public fun file(name: String): FileSpec? = files.firstOrNull { it.name == name }
    public fun overlay(name: String): OverlaySpec? = overlays.firstOrNull { it.name == name }
}
