# docuconf for Kotlin: advanced topics

## Contract-first mode

For a contract written by hand in CUE (or one exported by another SDK), `ContractFirst` in
`docuconf-kotlin-core` validates an environment against it with no Kotlin declaration, and returns
typed values (SPEC §11.2 item 11). Export the contract as JSON with `cue export contract.cue --out json`:

```kotlin
val contract = ContractFirst.parse(File("contract.json").readText())
val values = ContractFirst.load(contract, System.getenv())   // throws ConfigViolationException
val port: Long? = values.long("PORT")
val timeout: kotlin.time.Duration? = values.duration("TIMEOUT")
```

- `ContractFirst.check(contract, env)` returns `Result.Success` or `Result.Failure` with every
  violation, with the same codes and secret redaction as boot validation, and the warnings
  (deprecated inputs that are set, an input set both in the environment and an overlay).
- It parses every SPEC §5 encoding, exactly: lists and key sets as `csv` (with `separator`), `json`
  or `indexed` (`NAME__0`, `NAME__1`, ..., numbered from 0 with no gap; a gap is `invalid_type`),
  durations as `go` (Go's `time.ParseDuration` grammar, signs and fractions included), `iso8601`,
  `seconds` or `timespan` (`[d.]hh:mm:ss[.fffffff]`). Values are never trimmed, and booleans are
  `true` or `false` in any case.
- Values are checked by `ValueChecks`, the code that checks a declared Hoplite class at boot, so the
  conformance suite tests the real checks. The contract itself goes through `DeclarationChecks`.
- Values are layered as SPEC §4.4 and §4.7 say: the variable's `default`, then the selected profile's
  default from `profiles.defaults` (the selector's value, else `profiles.default`), then a
  config-file overlay, then the environment.
- Typed accessors: `string`, `long`, `double`, `boolean`, `duration`, `stringList`, `longList`,
  `keySet`, `json`, `file`, or `values[name]`. Absent optional inputs are null. `toJson()` gives every
  value as JSON, as the conformance suite writes it.

File inputs and overlays are files, which the multiplatform core does not read. In
`docuconf-hoplite`, `Docuconf.checkContract` and `Docuconf.loadContract` read them too, with every
path under `DOCUCONF_FILE_ROOT`, and check every file input with the code that checks a declared
class's files at boot: config files in JSON, YAML and TOML (parsed by Hoplite's parser modules, which
must be on the classpath, and checked against their `schema`), TLS key pairs, CA bundles, PKCS#12
and JKS keystores, text and binary files:

```kotlin
val contract = ContractFirst.parse(File("contract.json").readText())
when (val r = Docuconf.checkContract(contract, System.getenv())) {
    is ContractFirst.Result.Success -> r.values.file("routes")   // the config file's data
    is ContractFirst.Result.Failure -> error(r.violations.joinToString("\n"))
}
```

A config file's value is its data as a `JsonValue` (YAML scalars get the type the file's schema asks
for, since Hoplite's YAML parser keeps them as strings), a text file's its text, and a TLS key pair,
CA bundle, keystore or binary file is the same `TlsKeyPair`, `CaBundle`, `Keystore` or `BinaryFile`
a declared class gets. Overlays are read as native values (JSON by docuconf's own strict reader, YAML
and TOML by Hoplite's), matched at each variable's `configKey` split on `keySeparator`, exactly.

## Conformance

`ConformanceTest` (in `docuconf-hoplite`'s tests) runs docuconf-go's shared suite,
`conformance/cases.json` (SPEC §12), through `Docuconf.checkContract`. For each case it makes a fresh
directory, writes the case's files under it, and loads with the case's `env` plus
`DOCUCONF_FILE_ROOT` as the whole environment. Each case is its own JUnit test named by its `id`, so a
failure points at its YAML source.

Run it with `DOCUCONF_GO_DIR=../docuconf-go scripts/conformance.sh`, which also runs the export
checks below, or with `DOCUCONF_CONFORMANCE=../docuconf-go/conformance/cases.json DOCUCONF_REQUIRE_CONFORMANCE=1 ./gradlew :docuconf-hoplite:test`.

- `DOCUCONF_CONFORMANCE` is the path to `cases.json`; without it the runner looks for
  `../docuconf-go/conformance/cases.json` next to this repository.
- A missing file skips the suite, unless `DOCUCONF_REQUIRE_CONFORMANCE=1`, as in CI, where it fails.
- **Capability tags: all supported, none skipped.** The runner keeps an allow-list of the tags this
  SDK supports: `int64` (Kotlin `Long` holds every 64-bit integer), `json-schema`
  (`JsonSchemaValidator` checks `json` values against their schema), `key-set`, `deprecated`,
  `strict-parsing`, `files`, `profiles` and `overlays`. A case with any other tag, including one the
  runner has never heard of, is skipped, never run, and a skipped case fails the run.

`ConformanceExportTest` declares docuconf-go's shared export fixture (`conformance/export/fixture.yaml`)
with this SDK's annotations, exports it, and runs
`docuconf conformance export --golden <docuconf-go>/conformance/export/golden.cue` on the result. It
finds the CLI with `DOCUCONF_CLI`, else `docuconf` on `PATH` (`scripts/conformance.sh` builds it from
`DOCUCONF_GO_DIR` when neither is there). The comparison is not clean, by one gap: the fixture's
`settings` and `serving-tls` inputs declare `reload: watch`, and this SDK reads files once, at boot,
so it rejects `watch` at declaration time (SPEC §11.2 item 8). The test requires exactly those two
differences and no other, and the SDK keeps its own golden contract
(`docuconf-hoplite/src/test/resources/golden/gateway.cue`) as well.

## Mobile (Android and iOS)

Android and iOS apps do not get per-environment configuration from Kubernetes: their configuration
is compiled in (Gradle `buildConfigField`, resources, xcconfig). v0.1 does not try to solve that, but
the code is split so a build-time contract can reuse everything that is not JVM-server specific:

- **`docuconf-kotlin-core`** is a Kotlin Multiplatform module whose code lives in `commonMain`: the
  declaration model (`Contract`, `VarSpec`, `FileSpec`), declaration checks, value checks with the
  spec's error codes, the JSON Schema validator, durations, RE2 validation and the `contract.cue` and
  Markdown writers. It has no dependencies, no reflection and no `java.*` imports. The only
  platform-specific piece is how an RE2 pattern is compiled to a native regex (`expect fun
  compileRe2`), which has a JVM `actual` today. v0.1 builds only the JVM target; adding `androidTarget()`,
  `iosArm64()` or `js()` needs an `actual` for that one function.
- **`docuconf-hoplite`** holds everything server-specific: Hoplite, reflection over data classes,
  `java.security` TLS checks, files and the termination log.

A later mobile integration would declare the app's build-time settings (for example from a Gradle
plugin reading `buildConfigField`s, or a small DSL), build a `Contract` with the core, export it with
`CueWriter`, and check the values for each build variant in CI with `ValueChecks` and
`DeclarationChecks`, so a release build with a missing or malformed setting fails before it ships.
The contract format may need a build-time variant for this (SPEC §13, open question 3).

## Not done in v0.1

- `reload: watch` (file watching); file inputs and overlays are read once, and overlays declared
  `watch` are rejected.
- Profiles for declared classes: a Hoplite class does not read per-profile files. The contract-first
  mode applies a contract's `profiles`.
- `indexed` and `json` list encodings for declared config classes (they are read as `csv`, with
  `@Separator` for another separator). The contract-first mode parses all three.
- `@ConfigAlias` names are not exported (a warning says so); use `@Env`.
- Reading a `deprecated.replacedBy` variable's old name as a fallback.
