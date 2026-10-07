package dev.docuconf.kotlin.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.fail
import java.io.File

/**
 * Runs docuconf-go's shared conformance suite (SPEC §12, `conformance/README.md`) through
 * [ContractFirst]. Each case is its own test, named by its `id`, so a failure points at its YAML source.
 *
 * Finds `cases.json` with `DOCUCONF_CONFORMANCE`, else `../docuconf-go/conformance/cases.json` next to
 * this repository. A missing file skips the suite, unless `DOCUCONF_REQUIRE_CONFORMANCE=1`.
 */
class ConformanceTest {
    /** Capability tags this SDK supports. Cases requiring any other tag are skipped. */
    private val supported = setOf(
        "int64", // Kotlin Long holds every 64-bit integer.
        "json-schema", // JsonSchemaValidator checks json values against their schema.
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
        val skipped = cases.count { c -> requires(c).any { it !in supported } }
        println("conformance: ${cases.size} cases from $file, ${cases.size - skipped} run, $skipped skipped (unsupported tags)")
        return cases.map { c ->
            val id = (c.fields["id"] as JsonValue.Str).value
            DynamicTest.dynamicTest(id) {
                val missing = requires(c).filter { it !in supported }
                assumeTrue(missing.isEmpty(), "requires $missing")
                val problems = run(c)
                if (problems.isNotEmpty()) fail("conformance case \"$id\" (${(c.fields["source"] as? JsonValue.Str)?.value}):\n" + problems.joinToString("\n") { "  - $it" })
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
        val env = (c.fields["env"] as JsonValue.Obj).fields.mapValues { (it.value as JsonValue.Str).value }
        val contract = try {
            ContractFirst.parse(c.fields["contract"]!!)
        } catch (e: DeclarationException) {
            return listOf("contract rejected: ${e.message}")
        }
        val result = ContractFirst.check(contract, env)
        val expect = c.fields["expect"] as? JsonValue.Obj
        val errors = c.fields["errors"] as? JsonValue.Arr
        val problems = ArrayList<String>()
        when {
            expect != null -> when (result) {
                is ContractFirst.Result.Failure -> problems += "expected success, got ${result.violations}"
                is ContractFirst.Result.Success -> {
                    val actual = result.values.toJson().fields
                    for ((name, want) in expect.fields) {
                        val got = actual[name] ?: JsonValue.Null
                        if (!sameValue(want, got)) problems += "$name: expected $want, got $got"
                    }
                }
            }
            errors != null -> {
                val want = errors.items.map { e -> (e as JsonValue.Obj).fields.let { (it["var"] as JsonValue.Str).value to (it["code"] as JsonValue.Str).value } }.toSet()
                when (result) {
                    is ContractFirst.Result.Success -> problems += "expected errors $want, got success: ${result.values}"
                    is ContractFirst.Result.Failure -> {
                        val got = result.violations.map { it.input to it.code }.toSet()
                        if (got != want) problems += "expected errors $want, got $got (${result.violations})"
                        // No message may contain a secret's raw value.
                        val output = result.violations.joinToString("\n") + "\n" + ConfigViolationException(result.violations).message
                        for (v in contract.vars.filter { it.secret }) {
                            val raw = env[v.name]?.takeIf { it.isNotEmpty() } ?: continue
                            if (raw in output) problems += "the error output contains the value of secret ${v.name}"
                        }
                    }
                }
            }
            else -> problems += "case has neither expect nor errors"
        }
        return problems
    }

    /** JSON equality with ints exact and floats numeric (`3` equals `3.0`). */
    private fun sameValue(a: JsonValue, b: JsonValue): Boolean = when {
        a is JsonValue.Int && b is JsonValue.Int -> a.value == b.value
        (a is JsonValue.Int || a is JsonValue.Float) && (b is JsonValue.Int || b is JsonValue.Float) -> a.asDouble() == b.asDouble()
        a is JsonValue.Arr && b is JsonValue.Arr -> a.items.size == b.items.size && a.items.indices.all { sameValue(a.items[it], b.items[it]) }
        a is JsonValue.Obj && b is JsonValue.Obj -> a.fields.keys == b.fields.keys && a.fields.all { (k, v) -> sameValue(v, b.fields[k]!!) }
        else -> a == b
    }
}
