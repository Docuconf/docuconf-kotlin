package dev.docuconf.hoplite

import dev.docuconf.kotlin.core.ConfigViolationException
import dev.docuconf.kotlin.core.ContractFirst
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.JsonValue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.fail
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createDirectories

/**
 * Runs docuconf-go's shared conformance suite (SPEC §12, `conformance/README.md`) through the
 * contract-first mode with files ([Docuconf.checkContract]). Each case is its own test, named by its
 * `id`, so a failure points at its YAML source.
 *
 * For every case the runner makes a fresh directory, writes the case's files under it at their
 * paths, and loads with the case's `env` plus `DOCUCONF_FILE_ROOT` set to that directory as the whole
 * environment, so no case reads the machine's own files.
 *
 * Finds `cases.json` with `DOCUCONF_CONFORMANCE`, else `../docuconf-go/conformance/cases.json` next to
 * this repository. A missing file skips the suite, unless `DOCUCONF_REQUIRE_CONFORMANCE=1`.
 */
class ConformanceTest {
    /**
     * The capability tags this SDK supports (an allow-list). A case that requires any other tag,
     * including one this runner has never heard of, is skipped, never run (SPEC §12). The SDK supports
     * every tag in the suite, so the last test fails when anything is skipped.
     */
    private val supported = setOf(
        "int64", // Kotlin Long holds every 64-bit integer.
        "json-schema", // JsonSchemaValidator checks json values against their schema.
        "key-set", // The keySet type (KeySet).
        "deprecated", // Deprecated inputs load, with a warning.
        "strict-parsing", // ValueChecks parses exactly the SPEC §5 grammars.
        "files", // File inputs, checked by the boot code (FileLoader).
        "profiles", // profiles.defaults, selected by the selector.
        "overlays", // Config-file overlays, layered between the profile and the environment.
    )

    @TestFactory
    fun conformance(): List<DynamicTest> {
        val file = casesFile()
        if (!file.isFile) {
            if (System.getenv("DOCUCONF_REQUIRE_CONFORMANCE") == "1") {
                fail("conformance cases not found at $file (DOCUCONF_REQUIRE_CONFORMANCE=1); set DOCUCONF_CONFORMANCE to docuconf-go's conformance/cases.json")
            }
            return listOf(DynamicTest.dynamicTest("conformance suite") { assumeTrue(false, "conformance cases not found at $file; set DOCUCONF_CONFORMANCE") })
        }
        val doc = JsonValue.parse(file.readText()) as JsonValue.Obj
        require(doc.fields["version"] == JsonValue.Int(1)) { "unsupported cases.json version ${doc.fields["version"]}" }
        val cases = (doc.fields["cases"] as JsonValue.Arr).items.map { it as JsonValue.Obj }
        require(cases.isNotEmpty()) { "$file holds no cases" }
        val skipped = cases.filter { c -> requires(c).any { it !in supported } }
        val skippedTags = skipped.flatMap { c -> requires(c).filter { it !in supported } }.groupingBy { it }.eachCount()
        val summary = "conformance: ${cases.size} cases from $file, ${cases.size - skipped.size} run, ${skipped.size} skipped $skippedTags"
        println(summary)
        // scripts/conformance.sh prints it, since Gradle hides test output.
        System.getProperty("docuconf.projectDir")?.let { File(it, "build/conformance-summary.txt").apply { parentFile.mkdirs() }.writeText(summary + "\n") }
        return cases.map { c ->
            val id = (c.fields["id"] as JsonValue.Str).value
            DynamicTest.dynamicTest(id) {
                val missing = requires(c).filter { it !in supported }
                assumeTrue(missing.isEmpty(), "requires $missing")
                val problems = run(c)
                if (problems.isNotEmpty()) fail("conformance case \"$id\" (${(c.fields["source"] as? JsonValue.Str)?.value}):\n" + problems.joinToString("\n") { "  - $it" })
            }
        } + DynamicTest.dynamicTest("every case runs (0 skipped)") {
            if (skipped.isNotEmpty()) {
                fail("the Kotlin SDK must run every case, but skipped ${skipped.size} (tags $skippedTags): ${skipped.joinToString { (it.fields["id"] as JsonValue.Str).value }}")
            }
        }
    }

    private fun casesFile(): File {
        System.getenv("DOCUCONF_CONFORMANCE")?.takeIf { it.isNotEmpty() }?.let { return File(it) }
        val root = System.getProperty("docuconf.rootDir")?.let(::File) ?: File(".").absoluteFile
        return File(root, "../docuconf-go/conformance/cases.json").normalize()
    }

    private fun requires(c: JsonValue.Obj): List<String> =
        (c.fields["requires"] as? JsonValue.Arr)?.items?.map { (it as JsonValue.Str).value } ?: emptyList()

    /** Runs one case; returns what went wrong, empty when it passes. */
    private fun run(c: JsonValue.Obj): List<String> {
        val root = Files.createTempDirectory("docuconf-conformance")
        try {
            writeFiles(root, c.fields["files"] as? JsonValue.Obj)
            val env = (c.fields["env"] as JsonValue.Obj).fields.mapValues { (it.value as JsonValue.Str).value } +
                ("DOCUCONF_FILE_ROOT" to root.toString())
            val contract = try {
                ContractFirst.parse(c.fields["contract"]!!)
            } catch (e: DeclarationException) {
                return listOf("contract rejected: ${e.message}")
            }
            return check(c, contract, env)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Writes each file of the case under [root] at its absolute path. */
    private fun writeFiles(root: Path, files: JsonValue.Obj?) {
        for ((path, content) in files?.fields.orEmpty()) {
            val f = (content as JsonValue.Obj).fields
            val bytes = when {
                f["text"] is JsonValue.Str -> (f["text"] as JsonValue.Str).value.toByteArray(Charsets.UTF_8)
                f["base64"] is JsonValue.Str -> Base64.getDecoder().decode((f["base64"] as JsonValue.Str).value)
                else -> error("file $path has neither text nor base64")
            }
            val target = root.resolve(path.removePrefix("/"))
            target.parent.createDirectories()
            Files.write(target, bytes)
        }
    }

    private fun check(c: JsonValue.Obj, contract: dev.docuconf.kotlin.core.Contract, env: Map<String, String>): List<String> {
        val result = Docuconf.checkContract(contract, env)
        val expect = c.fields["expect"] as? JsonValue.Obj
        val errors = c.fields["errors"] as? JsonValue.Arr
        val problems = ArrayList<String>()
        when {
            expect != null -> when (result) {
                is ContractFirst.Result.Failure -> problems += "expected success, got ${result.violations}"
                is ContractFirst.Result.Success -> {
                    val actual = result.values.toJson().fields
                    for ((name, want) in expect.fields) {
                        val got = actual[name]
                        if (got == null) {
                            problems += "$name: missing from the result"
                        } else if (!sameValue(want, got)) {
                            problems += "$name: expected $want, got $got"
                        }
                    }
                    for (name in actual.keys - expect.fields.keys) problems += "$name: in the result but not expected"
                }
            }
            errors != null -> {
                val want = errors.items.map { e -> (e as JsonValue.Obj).fields.let { (it["var"] as JsonValue.Str).value to (it["code"] as JsonValue.Str).value } }.toSet()
                when (result) {
                    is ContractFirst.Result.Success -> problems += "expected errors $want, got success: ${result.values}"
                    is ContractFirst.Result.Failure -> {
                        val got = result.violations.map { it.input to it.code }.toSet()
                        if (got != want) problems += "expected errors $want, got $got (${result.violations})"
                        // No message, warning or report may contain a secret's raw value.
                        val output = (result.violations.map { it.toString() } + result.warnings + ConfigViolationException(result.violations).message!!).joinToString("\n")
                        for ((name, raw) in env) {
                            val spec = contract.variable(name) ?: contract.variable(name.substringBefore("__")) ?: continue
                            if (spec.secret && raw.isNotEmpty() && raw in output) problems += "the error output contains the value of secret $name"
                        }
                    }
                }
            }
            else -> problems += "case has neither expect nor errors"
        }
        return problems
    }

    /** JSON equality with ints exact and other numbers numeric (`3` equals `3.0`). */
    private fun sameValue(a: JsonValue, b: JsonValue): Boolean = when {
        a is JsonValue.Int && b is JsonValue.Int -> a.value == b.value
        (a is JsonValue.Int || a is JsonValue.Float) && (b is JsonValue.Int || b is JsonValue.Float) -> number(a) == number(b)
        a is JsonValue.Arr && b is JsonValue.Arr -> a.items.size == b.items.size && a.items.indices.all { sameValue(a.items[it], b.items[it]) }
        a is JsonValue.Obj && b is JsonValue.Obj -> a.fields.keys == b.fields.keys && a.fields.all { (k, v) -> sameValue(v, b.fields[k]!!) }
        else -> a == b
    }

    private fun number(v: JsonValue): Double = when (v) {
        is JsonValue.Int -> v.value.toDouble()
        is JsonValue.Float -> v.value
        else -> Double.NaN
    }
}
