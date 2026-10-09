# docuconf for Kotlin

Documentation: [docuconf.dev](https://docuconf.dev) · [Kotlin guide](https://docuconf.dev/languages/kotlin/)

Typed configuration contracts between a Kotlin service and the Kubernetes platform that runs it,
built on [Hoplite](https://github.com/sksamuel/hoplite). You keep writing a Hoplite data class;
docuconf adds what Hoplite cannot express (descriptions, secrets, constraints, URL schemes, file
inputs). From that one class it

- exports a **contract** (`contract.cue`) that the platform checks values against before anything is
  deployed, and
- validates the real environment and files **at boot**, reporting every problem at once with a stable
  error code, then lets Hoplite bind the class as usual.

Server-side Kotlin on the JVM (Ktor, http4k, plain `main`), JDK 17 or later. Status: v0.1,
`apiVersion: docuconf.dev/v1alpha1`, not yet released. Licence: [MIT](LICENSE).

The steps below are the [`examples/consumer`](examples/consumer/) project, which CI builds and runs
exactly as written. [`examples/orders`](examples/orders/) is a small HTTP service built the same way.

## 1. Install

docuconf is not on Maven Central yet. Build it from source: clone this repository next to your
project (`git clone https://github.com/docuconf/docuconf-kotlin ../docuconf-kotlin`), then in your
`settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("../docuconf-kotlin/docuconf-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
includeBuild("../docuconf-kotlin")
```

and in `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.2.21"
    application
    id("dev.docuconf")
}

dependencies {
    implementation("dev.docuconf:docuconf-hoplite:0.1.0-SNAPSHOT") // x-release-please-version
    testImplementation(kotlin("test"))
}
```

Gradle builds docuconf from source with your project (a composite build). If you prefer a binary,
run this in the docuconf checkout and add `mavenLocal()` to your repositories:

```sh
./gradlew publishToMavenLocal
```

That publishes `0.1.0-SNAPSHOT` to `~/.m2`. <!-- x-release-please-version -->
Registry install (`implementation("dev.docuconf:docuconf-hoplite:0.1.0")` <!-- x-release-please-version -->
from Maven Central, `id("dev.docuconf")` from the Gradle Plugin Portal) comes with the first release.

| Artifact | What it is |
|---|---|
| `dev.docuconf:docuconf-hoplite` | Annotations, file input types, boot validation, export. Depends on `hoplite-core` 3.0. |
| `dev.docuconf:docuconf-ktor` | Ktor integration (`docuconfServer`, `docuconfConfig`). |
| `dev.docuconf:docuconf-kotlin-core` | The declaration model, checks and contract writer (Kotlin Multiplatform). |
| `dev.docuconf` Gradle plugin | `docuconfExport` and `docuconfCheck` tasks; `docuconfKDoc` indexes KDoc for descriptions and details. |

Add Hoplite's parser modules (`com.sksamuel.hoplite:hoplite-yaml:3.0.0`) for the config file formats
you read, as with Hoplite itself. Kotlin 2.1 or later: Hoplite 3.0 itself depends on kotlin-stdlib 2.2.

## 2. Declare

```kotlin
@DocuconfService(name = "app")
data class AppConfig(
    @Doc("HTTP listen port") @Min(1) @Max(65535) val port: Int = 8080,                      // PORT
    @Doc("Minimum log level") val logLevel: LogLevel = LogLevel.INFO,                         // LOG_LEVEL
    @Doc("Postgres connection URL") @Schemes("postgres") val databaseUrl: Secret,              // DATABASE_URL
    @Doc("Upstream request timeout") @DurationMax("1m") val requestTimeout: Duration = Duration.ofSeconds(10), // REQUEST_TIMEOUT
)

enum class LogLevel { @WireName("debug") DEBUG, @WireName("info") INFO, @WireName("warn") WARN }
```

- **Names.** Each property reads its name in SCREAMING_SNAKE_CASE: `logLevel` reads `LOG_LEVEL`,
  `poolSize` in a nested `db` class reads `DB_POOL_SIZE`. `@Env("NAME")` sets a name explicitly.
- **Required, optional, defaults** come from Kotlin: a non-null parameter without a default is
  required, a default value is exported as `default`, a nullable one is optional.
- **`@DocuconfService`** holds the service name, and the env `prefix` and `baseSources` when you use
  them. Boot and export both read it, so they cannot drift apart.
- **Descriptions and details.** Every input needs a description: `@Doc("...")`, or the first sentence
  of the parameter's KDoc. The rest of the KDoc is the input's details, longer docs for generated
  docs: see [Descriptions and details](#descriptions-and-details).

## 3. Run

```kotlin
fun main() {
    // Checks every variable, then Hoplite binds AppConfig. On a bad environment it prints every
    // problem, writes them to /dev/termination-log and exits with status 1.
    val config = Docuconf.loadOrExit<AppConfig>()
    println("listening on ${config.port}")
}
```

For local development, `Docuconf.loadOrExit<AppConfig> { dotenv = Path.of(".env") }` also reads a
`.env` file (real environment variables win), and `DOCUCONF_FILE_ROOT=./dev-files` is prepended to
every file input path, so `/etc/app/tls` is read from `./dev-files/etc/app/tls`.

## 4. See an error

A bad environment fails at boot with every problem, never a secret value, and exit status 1. With
`PORT=0` and no `DATABASE_URL`:

```console
docuconf: 2 configuration problems:
  PORT: out_of_range: "0" is below min 1
  DATABASE_URL: missing_required: required, but not set
```

The same text goes to `/dev/termination-log`, so `kubectl describe pod` shows it. A set variable
that is not declared but is close to a declared name is a warning:
`docuconf: DATABSE_URL is set but not declared; did you mean DATABASE_URL?`.

## 5. Test your config

`env` replaces the process environment, so a test sets exactly the variables it needs, reads nothing
from the machine, changes nothing, and starts no threads. With JUnit 5 and `kotlin.test`:

```kotlin
class AppConfigTest {
    // `env` replaces the process environment: nothing is read from or written to the real one.
    private val good = mapOf("DATABASE_URL" to "postgres://app:pw@db/app")

    @Test
    fun loads() {
        val config = Docuconf.load<AppConfig> { env = good + ("LOG_LEVEL" to "debug") }
        assertEquals(LogLevel.DEBUG, config.logLevel)
    }

    @Test
    fun reportsEveryProblem() {
        val e = assertFailsWith<ConfigViolationException> {
            Docuconf.load<AppConfig> { env = mapOf("PORT" to "0", "REQUEST_TIMEOUT" to "PT5M") }
        }
        assertEquals(
            """
            docuconf: 3 configuration problems:
              PORT: out_of_range: "0" is below min 1
              DATABASE_URL: missing_required: required, but not set
              REQUEST_TIMEOUT: out_of_range: "PT5M" is longer than max 1m
            """.trimIndent(),
            e.message,
        )
    }

    @Test
    fun theContractIsCurrent() {
        // The same check as `./gradlew docuconfCheck`, as a unit test: it ignores only
        // metadata.generator.version, the docuconf version that wrote the file.
        val committed = java.io.File("contract.cue").readText()
        assertEquals(Docuconf.withoutGeneratorVersion(committed), Docuconf.withoutGeneratorVersion(Docuconf.exportCue(AppConfig::class)))
    }
}
```

`Docuconf.check(AppConfig::class, options)` returns a `LoadResult` instead of throwing. To build an
`AppConfig` by hand, the file input types have test factories: `ConfigFile.of(value)`,
`TlsKeyPair.of(chain, key)`, `CaBundle.of(certs)`, `Keystore.of(keyStore)`, `TextFile.of(text)`,
`BinaryFile.of(path)`. `fileRoot = tempDir.toString()` points file inputs at a test directory.

## 6. Export the contract

The `dev.docuconf` Gradle plugin (applied above) needs only the config class:

```kotlin
docuconf {
    configClass.set("com.example.AppConfig")
}
```

```sh
./gradlew docuconfExport
./gradlew check
```

`docuconfExport` writes `contract.cue`; commit it. `docuconfCheck`, part of `check`, fails with a
diff when the committed file differs from a fresh export, so CI catches a stale contract. It ignores
only the value of `metadata.generator.version`, the docuconf version that wrote the file, so
upgrading docuconf does not by itself make a contract stale. Export
needs no environment and is deterministic. For this project:

```cue
// Code generated by docuconf. DO NOT EDIT.
package app

import "docuconf.dev/contract"

contract.#Contract & {
	apiVersion: "docuconf.dev/v1alpha1"
	kind: "ConfigContract"
	metadata: {
		name: "app"
		generator: {language: "kotlin", sdk: "docuconf-hoplite", version: "0.1.0"}
	}
	vars: {
		DATABASE_URL: {
			type: "url"
			description: "Postgres connection URL"
			required: true
			secret: true
			configKey: "databaseUrl"
			schemes: ["postgres"]
		}
		LOG_LEVEL: {
			type: "enum"
			description: "Minimum log level"
			configKey: "logLevel"
			values: ["debug", "info", "warn"]
			default: "info"
		}
		PORT: {
			type: "int"
			description: "HTTP listen port"
			configKey: "port"
			min: 1
			max: 65535
			default: 8080
		}
		REQUEST_TIMEOUT: {
			type: "duration"
			description: "Upstream request timeout"
			configKey: "requestTimeout"
			encoding: "iso8601"
			max: "1m"
			default: "10s"
		}
	}
}
```

Without the plugin, run the `dev.docuconf.hoplite.Export` main class on the app's classpath
(`--class`, `--out`, `--markdown`, `--app-version`, `--check`; `--help` lists them), or call
`Docuconf.exportCue(AppConfig::class)`.

## 7. Deploy

The platform never runs your code to learn its configuration: it reads the committed
`contract.cue`. Before a deploy it checks the values for an environment with `docuconf vet` and
renders them into Kubernetes resources with `docuconf render` (from
[docuconf-go](https://github.com/Docuconf/docuconf-go)) or the
[docuconf Helm chart](https://github.com/Docuconf/docuconf-go/tree/main/helm/docuconf). The boot
check is the last line of defence.

## With your own Hoplite loader

Keep the `ConfigLoaderBuilder` you have and add one line:

```kotlin
val config = ConfigLoaderBuilder.default()
    .addResourceSource("/application.yaml")
    .withDocuconf()
    .build()
    .loadConfigOrThrow<AppConfig>()
```

Your sources, decoders, preprocessors and settings stay. docuconf checks every input first and
reads each variable by its declared name, so it leaves out Hoplite's own environment source (which
reads `_` as nesting). `withDocuconf().build()` also offers `loadConfig<T>()` (a `LoadResult`) and
`loadOrExit<T>()`.

A config file your loader reads ships in the image, so its values are defaults the contract must
know. Name it on the class too:

```kotlin
@DocuconfService(name = "app", baseSources = ["/application.yaml"])
```

If your loader reads a value from a file the contract does not list, boot fails with a declaration
error that says which variable and which file, instead of exporting a contract that disagrees with
the app.

## Ktor

`dev.docuconf:docuconf-ktor` loads and checks the config before the server starts, binds the port
from it, and makes it available to modules:

```kotlin
docuconfServer(Netty, port = AppConfig::port) { config ->
    routing { get("/") { call.respondText("log level ${config.logLevel}") } }
}.start(wait = true)
```

On a bad environment it prints the report and exits with status 1 before Netty starts. With
`EngineMain` and `application.yaml`, read the config in your module (it is loaded once, the same way):

```kotlin
fun Application.module() {
    val config = docuconfConfig<AppConfig>()
    routing { get("/") { call.respondText("log level ${config.logLevel}") } }
}
```

In `application.yaml`, set `ktor.deployment.port: ${?PORT}` only if `port` is declared in your
config class, so the platform knows to set `PORT`. In tests, hand the module a config loaded from a
map:

```kotlin
@Test
fun greets() = testApplication {
    application {
        provideDocuconfConfig(Docuconf.load<AppConfig> { env = mapOf("DATABASE_URL" to "postgres://u:pw@db/app") })
        module()
    }
    assertEquals(HttpStatusCode.OK, client.get("/").status)
    assertEquals("log level info", client.get("/").bodyAsText())
}
```

## Troubleshooting

- **My variable is ignored.** Check the name docuconf expects: `./gradlew docuconfExport` and look
  in `contract.cue`, or read the boot warnings: a near-miss name (`LOGLEVEL` for `LOG_LEVEL`) is
  reported with a "did you mean". With a `prefix` on `@DocuconfService`, every name carries it.
- **`file_missing` locally.** File inputs are read at their absolute paths. Set
  `DOCUCONF_FILE_ROOT=./dev-files` and put the files under it, or set the input's `pathEnv` variable.
- **`invalid docuconf declaration`.** A programming error in the config class, such as `@Min` on a
  `String`, a default outside its own bounds, or two properties reading one name. Each line names
  the property and how to fix it.
- **A Hoplite notice about sealed types.** docuconf silences it when your config has no sealed
  types. If it does, call `withExplicitSealedTypes()` in `hoplite { }` or on your own builder.

## Reference

### Environment variable names

| Kotlin | Variable | `configKey` (overlays) |
|---|---|---|
| `val port: Int` | `PORT` | `port` |
| `val logLevel: LogLevel` | `LOG_LEVEL` | `logLevel` |
| `val db: Database` with `val poolSize: Int` | `DB_POOL_SIZE` | `db.poolSize` |
| `val httpURL: String`, `val oauth2Token: String` | `HTTP_URL`, `OAUTH2_TOKEN` | |
| `@Env("DATABASE_URL") val url: Secret` in `db` | `DATABASE_URL` | `db.url` |
| `@Env("PG") val database: Database` | `PG_POOL_SIZE`, ... | `database.poolSize` |

`@DocuconfService(prefix = "APP_")` prepends `APP_` to every name, `@Env` ones included. Two
properties that read one name are a declaration error. docuconf hands values to Hoplite by property
path, so Hoplite's own environment naming never applies.

```kotlin
data class Database(
    @Doc("Postgres connection URL") @Env("DATABASE_URL") @Schemes("postgres") val url: Secret,    // DATABASE_URL
    @Doc("Connection pool size") @Min(1) @Max(50) val poolSize: Int = 10,                       // DB_POOL_SIZE
)
```

### Types

| Kotlin type | Contract type | Wire form (what the platform renders) |
|---|---|---|
| `String` | `string` | as is |
| `Secret` (Hoplite) | `string`, `secret: true` (`url` with `@Url`/`@Schemes`) | as is |
| `Int`, `Long`, `Short`, `Byte` | `int` (`Int`, `Short`, `Byte` export their range as `min`/`max`) | `8080` |
| `Double`, `Float` | `float` | `0.5` |
| `Boolean` | `bool` | `true` / `false` |
| `java.time.Duration`, `kotlin.time.Duration` | `duration`, `encoding: "iso8601"` | `PT1M30S` |
| `KeySet` | `keySet` (always secret), `encoding: "csv"`: keys that are all valid at once, such as the orders example's `WEBHOOK_KEYS` ([below](#key-sets)) | `old,new` |
| `java.net.URI`, `java.net.URL`, `String` + `@Url`/`@Schemes` | `url` | as is |
| `enum class`, `String` + `@OneOf` | `enum` | the constant's `@WireName`, else its name |
| `List<String>`, `List<Int>`, `List<Long>`, `Set<…>` | `list`, `encoding: "csv"` | `a,b` |
| `List<Secret>` | `list` of strings, `encoding: "csv"`, `secret: true` (for a set of keys, use `KeySet`) | `a,b` |
| `Json<T>` | `json`, `schema` generated from `T` | compact JSON |
| a data class | nested variables | |

**Parsing is exact** ([SPEC §5](https://github.com/Docuconf/docuconf-go/blob/main/spec/SPEC.md#5-wire-encoding-and-parsing)):
docuconf checks and parses every value itself, before Hoplite sees it, and hands Hoplite the parsed
value, so Hoplite's more lenient parsing never applies and every docuconf SDK accepts the same
strings. A form outside these rules is `invalid_type`:

- **Values are never trimmed.** `" true"`, `"8080\n"` and `"PT5S "` fail; digits are ASCII only.
- **Booleans** are `true` or `false` in any case (`TRUE`, `False`), and nothing else: never `yes`,
  `no`, `on`, `t`, `1` or `0`.
- **Integers** are `^[+-]?[0-9]+$` in base 10: `+5` and `007` (7) are fine; `0x10`, `1_000`, `1e3`
  and `5.0` are not. A value beyond 64 bits, or beyond an `Int`'s range, is `out_of_range`.
- **Floats** are decimal with a digit on each side of the point and an optional exponent (`1.5`,
  `25e-2`); never `.5`, `5.`, `inf`, `NaN`, a hex float, `1e400` or `0,5`.
- **Durations** are ISO 8601 (`P[nD][T[nH][nM][nS]]`, upper case, a fraction after `.` or `,`, such as
  `PT1M30S`, `PT1,5S` or `P1DT2H`): what the platform renders and `java.time.Duration` reads. Go
  syntax (`1m30s`) and Hoplite's `30s` are `invalid_type: ... expected an ISO 8601 duration like PT30S`.
  Annotation bounds (`@DurationMax("5m")` or `"PT5M"`) and defaults are exported in Go syntax, as the
  spec requires.
- **Lists and key sets** are split on every separator (`,`, or `@Separator(";")`) and never trimmed:
  `a, b` is `a` and ` b`, and `a,,b` has an empty middle item (bound items with
  `@ItemLength(min = 1)` to reject it).
- **Enums.** `enum class LogLevel { @WireName("debug") DEBUG }` exports and reads `debug`. Matching is
  exact: the contract and the boot check agree.

### Key sets

A `KeySet` is a set of secret keys that are all valid at once, so a key that verifies something
(webhook signatures, inbound API keys, JWT HMACs) can be rotated without an outage: during the
overlap the platform supplies `old,new` from one Kubernetes Secret key. It is always secret, holds 1
or 2 keys unless `@Keys(min, max)` says otherwise, and `@KeyLength` bounds each key:

```kotlin
data class WebhookConfig(
    @Doc("Keys that verify webhook signatures") @KeyLength(min = 32, max = 256) val webhookKeys: KeySet,
    @Doc("API keys callers present") @Keys(min = 1, max = 3) val apiKeys: KeySet? = null,
)

fun verified(config: WebhookConfig, body: ByteArray, signature: ByteArray): Boolean =
    config.webhookKeys.verify { key ->
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        MessageDigest.isEqual(mac.doFinal(body), signature)
    }

fun allowed(config: WebhookConfig, presented: String): Boolean = config.apiKeys?.contains(presented) ?: false
```

`keys` lists the keys in the order the platform gave them. `verify` tries every key, even after one
matches, and `contains` compares in constant time, so the time taken never says which key matched.
`toString()` is `KeySet(****)`, and no error message holds a key: too few or too many keys is
`too_few_items` or `too_many_items`, and a key outside `@KeyLength`, or an empty one (a stray
comma), is `out_of_range`. `docuconf docs` prints the rotation steps for every key set.

### Annotations

All go on primary-constructor parameters, except `@DocuconfService` and `@ConfigOverlay` (on the
root class) and `@WireName` (on enum constants). An annotation on a type it does not apply to (`@Min`
on a `String`, `@Schemes` on an `Int`, a constraint on a nested class parameter) is a declaration error.

| Annotation | Meaning |
|---|---|
| `@DocuconfService(name, prefix, baseSources)` | Service name, env prefix, config files in the image. Read by boot and export. |
| `@Doc("...", details = "...")` | Description, at least 5 characters, and optional details (CommonMark). Every variable and file input needs a description: `@Doc`, or the parameter's KDoc ([below](#descriptions-and-details)). |
| `@Env("NAME")` | The variable's name (or a nested class's segment). |
| `@Min`, `@Max` / `@DecimalMin`, `@DecimalMax` | Integer / float bounds. |
| `@DurationMin("1s")`, `@DurationMax("5m")` | Duration bounds, in Go syntax or ISO 8601. |
| `@Length(min, max)` | String or text-file length in characters (code points). On a `url` or `Json<T>` variable only `max` applies (`maxLength`): a url is measured as it is, a json value as received, whitespace included, before it is parsed (as compact JSON when it comes from an overlay as nested keys). |
| `@Pattern("re")` | RE2 pattern, matched **anywhere** in the value (anchor with `^`/`$`). Non-RE2 features are rejected. |
| `@Url`, `@Schemes("postgres", ...)` | URL variable, allowed schemes. |
| `@OneOf("a", "b")` | A `String` restricted to values (an `enum class` needs nothing). |
| `@WireName("debug")` | An enum constant's value on the wire. |
| `@Items(min, max)`, `@ItemMin(n)`, `@ItemMax(n)` | List length; bounds on every item of a `List<Int>`/`List<Long>`. |
| `@ItemLength(min, max)` | Length in characters of every item of a `List<String>` (`itemMinLength`/`itemMaxLength`), checked after splitting, so the separator is never counted. On a `List<Int>` it is a declaration error. |
| `@Keys(min, max)`, `@KeyLength(min, max)` | A `KeySet`'s number of keys (default 1 to 2) and each key's length in characters. |
| `@Separator(";")` | The separator of a list or key set (default `,`). |
| `@Group`, `@Examples` | Docs metadata. |
| `@DeprecatedInput(message, replacedBy)` | Deprecated (SPEC §4.2): the platform should stop setting it. `message` is not blank and at most 500 characters, and a required input cannot be deprecated; both fail when the class is declared. When it is still set, boot logs `OLD_PORT is deprecated: Use PORT instead (replaced by PORT)`, never the value; it still loads and is checked. |
| `@NotInContract` | Leave a parameter out, e.g. a value Hoplite reads from Vault (SPEC §4.4). |
| `@FileInput(name, path, pathEnv, maxSize, secret)` | Declares a file input on a file-typed parameter. |
| `@Format`, `@Tls`, `@MinCertificates`, `@KeystoreSpec` | File input details: config format, TLS constraints, CA bundle size, keystore format and password variable. |
| `@ConfigOverlay(name, path, description)` | A config-file overlay the platform mounts (below). |

Lengths count characters, meaning Unicode code points (`codePointCount`), never bytes or UTF-16 units
(SPEC §4.3): `日本` is 2, `ZÜ01` fits `@ItemLength(max = 4)`, and an emoji is 1 although Kotlin's `String.length`
says 2. A value above a limit is `out_of_range` at boot; a too-long secret reports its length, never its value.

Defaults are read by constructing the class once with placeholder values for required parameters,
so keep `init` checks off parameters that have defaults (use constraints). The declaration is checked
when it is first used (load or export): names, descriptions, defaults against their own constraints,
secrets without defaults or examples, RE2 patterns, file paths and mount directories. Names that look
like feature flags (`FF_`, `FEATURE_`, `ENABLE_`) get a warning (SPEC §10).

### Descriptions and details

Every input has a **description**: what it is, in one phrase of plain text. It is `@Doc("...")`, or
else the first sentence of the parameter's KDoc, as Spring's configuration metadata takes it for Java:
up to the first period followed by white space, or the first blank line. An input may also have
**details**: CommonMark on why it exists and when to change it, at most 4000 characters (Unicode code
points), for generated docs only and never read at runtime. They are `@Doc(details = "...")`, or else
the rest of the KDoc. From the orders example:

```kotlin
    /**
     * Number of background workers that process orders
     *
     * A KDoc works instead of @Doc: its first sentence is the description, and the rest is the details,
     * longer docs for `docuconf docs`. Each worker holds one connection from the pool of [databaseUrl],
     * so keep this below the database's connection limit.
     *
     * - Raise it when the order queue backs up.
     * - Lower it when the database is the bottleneck.
     */
    @Min(1) @Max(64) val workerCount: Int = 4,
```

KDoc is not in compiled classes, so the `dev.docuconf` Gradle plugin's `docuconfKDoc` task reads it
from the sources at build time into the resource `META-INF/docuconf/kdoc.properties`, which boot and
export both read. A KDoc on the parameter, or an `@property` tag in the class's KDoc, both count.
KDoc is Markdown already: links to declarations (`[databaseUrl]`) become code spans, and block tags
(`@see`, `@sample`) are dropped. Without the plugin, only `@Doc` counts. Blank details, or more than
4000 characters, fail at export and at boot.

`docuconf docs` in the [docuconf CLI](https://github.com/docuconf/docuconf-go) generates CONFIG.md and
CONFIG.agents.md from the exported contract: `docuconf docs contract.cue -o CONFIG.md`, and
`--format agents -o CONFIG.agents.md`.

## File inputs

| Parameter type | Contract type | Checked at boot |
|---|---|---|
| `ConfigFile<T>` | `config` (`json`, `yaml`, `toml`), `schema` generated from `T` | parses with Hoplite's parser for the format (`file_malformed`), matches the schema (`schema_mismatch`), binds to `T` with Hoplite. |
| `TlsKeyPair` | `tls` (directory with `tls.crt`, `tls.key`, `ca.crt`) | certificate and key parse (PKCS#8, PKCS#1 and SEC 1 keys, as cert-manager writes them) and match, validity and `minRemaining`, every `dnsNames` entry covered by a SAN (wildcards cover one label), key algorithm, PKIX chain to `ca.crt` with `requireCA`. A `tls.crt` or `tls.key` with no PEM certificate or key at all is `file_malformed`; one that does not parse is `certificate_invalid`. |
| `CaBundle` | `caBundle` | at least `minCertificates` parseable PEM certificates (`file_malformed` for too few, `certificate_invalid` for one that does not parse). |
| `Keystore` | `keystore` (`pkcs12`, `jks`) | opens with the password variable, and its integrity MAC checks out (`keystore_unreadable`); an unset password variable is an empty password. |
| `TextFile` | `text` | UTF-8, length, RE2 pattern. Never trimmed. |
| `BinaryFile` | `binary` | size only; not read into memory. |

Every file is checked for presence (`file_missing` if required), readability (`file_unreadable`, with
an `fsGroup` hint) and `maxSize` (`file_too_large`). All TLS checks use `java.security` only
(`CertificateFactory`, `KeyFactory`, `Signature`, `CertPathValidator`, `KeyStore`). `TlsKeyPair`
offers `toKeyStore()` and `keyManagerFactory()`, `CaBundle` offers `toTrustStore()` and
`trustManagerFactory()`.

The schema of a config file is closed (`additionalProperties: false`) and uses the Kotlin property
names exactly. Hoplite itself would also accept `pool-size` or `pool_size` for `poolSize`; the
platform will not, so write the names as declared.

`reload: watch` is not offered in v0.1: files are read once at boot (`reload: restart`), and the
platform rolls the pods when a source changes.

## Boot validation

### Boot validation

`Docuconf.loadOrExit<T>()` (or `load`, which throws `ConfigViolationException`, or `check`, which
returns a `LoadResult`):

1. Reads the declaration and checks it.
2. Checks every declared variable's raw string before Hoplite sees it: type, constraints and
   `missing_required`. An **empty string counts as unset** for every type except `string`, so a
   defaulted variable takes its default. Values are never trimmed. Undeclared variables are ignored,
   apart from the near-miss warning.
3. Checks every file input, with `DOCUCONF_FILE_ROOT` prepended to absolute paths.
4. On failure, writes every violation to `/dev/termination-log` when it exists (or
   `DOCUCONF_TERMINATION_LOG` / `terminationLog`).
5. Otherwise Hoplite binds the class. A problem only Hoplite finds, such as a missing
   `@NotInContract` value, is reported the same way, by property name.

Codes (SPEC §11.2): `missing_required`, `invalid_type`, `out_of_range`, `pattern_mismatch`,
`not_in_enum`, `invalid_scheme`, `too_few_items`, `too_many_items`, `file_missing`,
`file_unreadable`, `file_too_large`, `file_malformed`, `schema_mismatch`, `certificate_invalid`,
`certificate_expiring`, `certificate_name_mismatch`, `key_mismatch`, `keystore_unreadable`. Secret
values never appear in messages, `toString()` (Hoplite's `Secret` prints `****`), or errors from
your own `init` validators.

**Injected secrets.** docuconf reads the environment as the process sees it, after injectors such as
Bank-Vaults' `vault-env` or `op run` have run. If one did not run, a secret still holding a
`vault:`, `op://` or `ref+` reference is reported without its value:
`DB_URL: invalid_type: holds an unresolved vault: reference; the injector that should resolve it did not run`.

| Option (`Docuconf.load<T> { ... }`) | Default | |
|---|---|---|
| `env` | `System.getenv()` | Replace in tests. |
| `dotenv` | none | A `.env` file for local development. Real environment variables win. |
| `fileRoot` | `DOCUCONF_FILE_ROOT` | |
| `terminationLog` | `DOCUCONF_TERMINATION_LOG`, else `/dev/termination-log` if present | |
| `clock` | UTC system clock | For certificate checks. |
| `warn` | stderr | Deprecation, feature-flag and near-miss warnings. |
| `hoplite { ... }` | | Customise docuconf's `ConfigLoaderBuilder`; each call adds a block. |

### Config files in the image

`@DocuconfService(baseSources = ["/application.yaml"])` (Hoplite resource-or-file paths) adds config
files that ship in the image. Hoplite reads them **below** environment variables and overlays. On
export their values become each variable's `default`, and a required variable with a value there is
exported as optional. A secret with a value in a base file is a declaration error. A declared class
does not read profile files (SPEC §4.4) yet; the contract-first mode applies a contract's `profiles`
([docs/ADVANCED.md](docs/ADVANCED.md)).

### Config-file overlays

Platforms often mount one more config file per environment, from a ConfigMap. Declare it on the root
class with `@ConfigOverlay` (repeatable):

```kotlin
@DocuconfService(name = "gateway")
@ConfigOverlay(name = "platform", path = "/app/config/gateway.yaml", description = "Settings the platform supplies per environment")
data class GatewayConfig(
    @Doc("HTTP listen port") @Min(1) @Max(65535) val port: Int = 8080,
    @Doc("Upstream request timeout") val timeout: Duration = Duration.ofSeconds(30),
    val db: Database,
)
```

- **Precedence** (SPEC §4.7): base files < overlay < environment variables.
- **Format** follows the extension (`.yaml`, `.yml`, `.json`, `.toml`), with the Hoplite parser on
  the classpath. Keys nest as Hoplite reads files: `keySeparator: "."`, and each variable's
  `configKey` is its property path (`db.poolSize: 30`, `timeout: PT1M30S`).
- The file is **optional**, and `DOCUCONF_FILE_ROOT` is prepended to its path. Its values are
  **checked like env values**, with the overlay and key in the message. A secret in an overlay is
  `invalid_type`.
- The platform mounts the overlay's **directory**, so it must not hold files the app ships with; use
  a directory of its own such as `/app/config`. `reload = Reload.WATCH` is rejected: docuconf
  validates once, at boot, and the platform rolls the pods when the ConfigMap changes.

## More

[docs/ADVANCED.md](docs/ADVANCED.md): the contract-first mode (validate against a hand-written
contract, with its files, profiles and overlays), the shared conformance suite, the plan for Android
and iOS, and what v0.1 does not do.

### Conformance

CI runs docuconf-go's shared conformance suite (SPEC §12) through the contract-first mode, and the
SDK supports every capability tag in it: `int64`, `json-schema`, `key-set`, `deprecated`,
`strict-parsing`, `files`, `profiles` and `overlays`. **No case is skipped**, and the run fails if one
ever is; a tag the runner does not know is skipped rather than run, so that failure is how a new tag
gets noticed. CI also exports the shared export fixture and compares it with docuconf-go's golden
contract; the one difference is `reload: watch` on two file inputs, which this SDK rejects because it
reads files once, at boot ([docs/ADVANCED.md](docs/ADVANCED.md#conformance)).

## Development

`./gradlew check` runs the unit tests, the Ktor and Gradle plugin tests, the README check (every code
block here must appear in a file CI compiles, runs or diffs), and `cue vet` and the conformance suite
when they are available (`DOCUCONF_REQUIRE_CUE=1` and `DOCUCONF_REQUIRE_CONFORMANCE=1` make them
mandatory, as in CI). `UPDATE_GOLDEN=1 ./gradlew :docuconf-hoplite:test --tests '*ExportTest*'`
refreshes the golden contract. Releases: [RELEASING.md](RELEASING.md).

## Licence

[MIT](LICENSE).
