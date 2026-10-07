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
  violation, with the same codes and secret redaction as boot validation.
- It parses every SPEC §5 encoding: lists as `csv` (with `separator`), `json` or `indexed`
  (`NAME__0`, `NAME__1`, ..., numbered from 0 with no gap; a gap is `invalid_type`), durations as `go`, `iso8601`, `seconds` or `timespan`
  (`[d.]hh:mm:ss[.fffffff]`). Values are never trimmed, and booleans are `true` or `false` in any case.
- Values are checked by `ValueChecks`, the code that checks a declared Hoplite class at boot, so the
  conformance suite tests the real checks. The contract itself goes through `DeclarationChecks`.
- Typed accessors: `string`, `long`, `double`, `boolean`, `duration`, `stringList`, `longList`,
  `json`, or `values[name]`. Absent optional variables are null; defaults come from the contract.
  `toJson()` gives every value as JSON, with durations in canonical Go form.
- It covers variables. File inputs and overlays in the contract are not read or checked.

## Conformance

`ConformanceTest` (in `docuconf-kotlin-core`'s JVM tests) runs docuconf-go's shared suite,
`conformance/cases.json` (SPEC §12), through the contract-first mode. Each case is its own JUnit test
named by its `id`, so a failure points at its YAML source.

Run it with `DOCUCONF_CONFORMANCE=../docuconf-go/conformance/cases.json DOCUCONF_REQUIRE_CONFORMANCE=1 ./gradlew :docuconf-kotlin-core:jvmTest`.

- `DOCUCONF_CONFORMANCE` is the path to `cases.json`; without it the runner looks for
  `../docuconf-go/conformance/cases.json` next to this repository.
- A missing file skips the suite, unless `DOCUCONF_REQUIRE_CONFORMANCE=1`, as in CI, where it fails.
- **Skipped tags: none.** `int64` is supported (Kotlin `Long` holds every 64-bit integer) and so is
  `json-schema` (`JsonSchemaValidator` checks `json` values against their schema).

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
- Profiles (`profiles` in the contract) from per-environment files.
- `indexed` and `json` list encodings for declared config classes (they are read as `csv`). The
  contract-first mode parses all three.
- `@ConfigAlias` names are not exported (a warning says so); use `@Env`.
- Reading a `deprecated.replacedBy` variable's old name as a fallback.
