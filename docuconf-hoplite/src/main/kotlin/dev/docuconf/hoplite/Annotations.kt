package dev.docuconf.hoplite

import dev.docuconf.kotlin.core.ConfigFormat
import dev.docuconf.kotlin.core.KeyAlgorithm
import dev.docuconf.kotlin.core.KeystoreFormat
import dev.docuconf.kotlin.core.Reload

// docuconf metadata for Hoplite config classes. Hoplite binds the data class; these annotations add
// what it cannot express: descriptions (KDoc is not available at runtime), constraints, URL schemes
// and file inputs. All of them go on primary-constructor parameters, except [ConfigOverlay], which
// goes on the root config class.

/**
 * The service a root config class belongs to, and how the platform reaches it. Declared once, here,
 * so loading at boot and exporting the contract always agree: [Docuconf.load], `withDocuconf()` and
 * the `Export` command all read it.
 *
 * @property name the service name in the contract (`metadata.name`), a DNS label such as `orders`.
 *   `Export --service` may be left out when it is set.
 * @property prefix prepended to every environment variable name: `APP_` makes `port` read `APP_PORT`.
 * @property baseSources config files baked into the image, as Hoplite resource-or-file paths
 *   (`/application.yaml`). Hoplite reads them below environment variables and overlays, and their
 *   values are exported as defaults (SPEC §4.4).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class DocuconfService(
    val name: String = "",
    val prefix: String = "",
    val baseSources: Array<String> = [],
)

/**
 * Sets an environment variable name explicitly. Without it, a property's name is its path in
 * SCREAMING_SNAKE_CASE: `logLevel` reads `LOG_LEVEL`, and `poolSize` in a nested `db` class reads
 * `DB_POOL_SIZE`.
 *
 * On a variable, [value] is the whole name (`@Env("DATABASE_URL") val db: Secret`). On a nested
 * config class parameter, it replaces that level's segment for everything inside
 * (`@Env("PG") val database: Database` gives `PG_URL`). The [DocuconfService.prefix] is still
 * prepended in both cases.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class Env(val value: String)

/**
 * The value an enum constant has on the wire, in the environment and in the contract. Without it the
 * constant's name is used. Use it for lowercase values with idiomatic Kotlin constants:
 *
 * ```
 * enum class LogLevel { @WireName("debug") DEBUG, @WireName("info") INFO }
 * ```
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class WireName(val value: String)

/** The description of a variable or file input. Required for every input; at least 5 characters. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class Doc(val value: String)

/** A free-form group for docs (`database`, `http`). On a nested config class parameter it applies to everything inside. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Group(val value: String)

/** Example values for docs. Not allowed on secrets. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Examples(vararg val values: String)

/** Marks a variable or file input deprecated. docuconf warns at boot when it is still set. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class DeprecatedInput(val message: String, val replacedBy: String = "")

/**
 * Leaves a parameter out of the contract. Use it for settings the platform does not supply, such
 * as values Hoplite reads from AWS Secrets Manager or Vault (SPEC §4.4).
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class NotInContract

/** Lower bound of an integer variable, or of an integer property in a config file. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Min(val value: Long)

/** Upper bound of an integer variable, or of an integer property in a config file. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Max(val value: Long)

/** Lower bound of a floating-point variable. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class DecimalMin(val value: Double)

/** Upper bound of a floating-point variable. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class DecimalMax(val value: Double)

/** Shortest allowed duration, in Go syntax (`1s`, `1h30m`) or ISO 8601 (`PT1S`). Exported in Go syntax. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class DurationMin(val value: String)

/** Longest allowed duration, in Go syntax (`5m`, `720h`) or ISO 8601 (`PT5M`). Exported in Go syntax. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class DurationMax(val value: String)

/** Length bounds in characters (Unicode code points) for a string variable or text file. -1 means no bound. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Length(val min: Int = -1, val max: Int = -1)

/**
 * An RE2 pattern the value must match **somewhere** (partial match, as CUE's `=~` does). Anchor it
 * with `^` and `$` to match the whole value. Lookaround and backreferences are rejected.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Pattern(val value: String)

/** Makes a `String` or `Secret` parameter a `url` variable. Implied by [Schemes], `java.net.URI` and `java.net.URL`. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Url

/** Allowed URL schemes, such as `postgres`. Makes the parameter a `url` variable. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Schemes(vararg val value: String)

/** Makes a `String` parameter an `enum` variable with these values. A Kotlin `enum class` needs no annotation. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class OneOf(vararg val value: String)

/** Bounds on the number of items in a list. -1 means no bound. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Items(val min: Int = -1, val max: Int = -1)

/**
 * Lower bound of every item of a `List<Int>` or `List<Long>` (exported as `itemMin`). A `List<Int>`
 * is bounded by Int's range without it.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class ItemMin(val value: Long)

/**
 * Upper bound of every item of a `List<Int>` or `List<Long>` (exported as `itemMax`). A `List<Int>`
 * is bounded by Int's range without it.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class ItemMax(val value: Long)

/**
 * Declares a file input. Goes on a parameter of type [ConfigFile], [TlsKeyPair], [CaBundle],
 * [Keystore], [TextFile] or [BinaryFile]. A non-null parameter without a default is required.
 *
 * @property name the input name in the contract, a DNS label such as `serving-tls`.
 * @property path where the app reads it: a directory for [TlsKeyPair], a file otherwise. Absolute.
 * @property pathEnv an environment variable the platform sets to [path]; the app reads the path from it when set.
 * @property maxSize upper bound in bytes, or -1.
 * @property secret whether the content is secret. Always true for [TlsKeyPair] and [Keystore].
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class FileInput(
    val name: String,
    val path: String,
    val pathEnv: String = "",
    val maxSize: Long = -1,
    val secret: Boolean = false,
)

/** The format of a [ConfigFile], when the path's extension (`.json`, `.yaml`, `.yml`, `.toml`) does not say. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Format(val value: ConfigFormat)

/**
 * Constraints on a [TlsKeyPair].
 *
 * @property dnsNames names the certificate must cover (a wildcard covers one label).
 * @property keyAlgorithms allowed key algorithms; empty allows any.
 * @property minRemaining least validity the certificate must have left, in Go syntax (`720h`).
 * @property requireCA whether `ca.crt` must be present and the certificate must chain to it.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Tls(
    val dnsNames: Array<String> = [],
    val keyAlgorithms: Array<KeyAlgorithm> = [],
    val minRemaining: String = "",
    val requireCA: Boolean = false,
)

/** The least number of certificates a [CaBundle] must hold (default 1). */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class MinCertificates(val value: Int)

/**
 * The format of a [Keystore] and the secret variable holding its password. [passwordVar] is the
 * environment variable name of a `Secret` parameter in the same config class tree.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class KeystoreSpec(val format: KeystoreFormat = KeystoreFormat.PKCS12, val passwordVar: String = "")

/**
 * Declares a config-file overlay (SPEC §4.7): a file the platform mounts, which Hoplite layers
 * between the config files baked into the image ([DocuconfService.baseSources]) and environment
 * variables. Goes on the root config class; repeatable.
 *
 * The format follows the extension of [path] (`.json`, `.yaml`, `.yml`, `.toml`; the Hoplite parser
 * module must be on the classpath). Keys nest as Hoplite reads config files, so the overlay's key
 * separator is `.` and a variable's `configKey` is its property path (`db.poolSize`). The file is
 * optional: a missing overlay is fine.
 *
 * Its directory is mounted by the platform, hiding what the image has there, so [path] must be in a
 * directory of its own (`/app/config`), not one holding the app or its config files.
 *
 * @property name the overlay name in the contract, a DNS label such as `platform`.
 * @property path where the app reads the overlay. Absolute.
 * @property description what the overlay is for (optional, at least 5 characters).
 * @property reload only [Reload.RESTART]: a change rolls the pods. [Reload.WATCH] is rejected,
 *   because docuconf validates configuration once, at boot.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
@MustBeDocumented
public annotation class ConfigOverlay(
    val name: String,
    val path: String,
    val description: String = "",
    val reload: Reload = Reload.RESTART,
)
