package dev.docuconf.examples.shop

import dev.docuconf.hoplite.Docuconf
import dev.docuconf.hoplite.checkContract
import dev.docuconf.kotlin.core.ContractFirst
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * The shop example's test matrix (README.md): roles (SPEC §4.9), requiredIf (SPEC §4.2.1) and the
 * included `otel@1` fragment (SPEC §4.8). Each case starts from [base] with a fresh, empty file root.
 */
class ShopTest {
    @TempDir
    lateinit var root: Path

    private val base = mapOf("DATABASE_URL" to "postgres://shop@db:5432/shop")

    private class Run(val status: Int, val out: String, val err: String)

    private fun env(vararg vars: Pair<String, String>): Map<String, String> =
        base + vars + mapOf("DOCUCONF_FILE_ROOT" to root.toString(), "DOCUCONF_TERMINATION_LOG" to "/dev/null")

    /** Runs the app's entry point, as `main` does, with [vars] on top of [base]. */
    private fun run(vararg vars: Pair<String, String>): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status = run(env(*vars), PrintStream(out, true), PrintStream(err, true))
        return Run(status, out.toString(), err.toString())
    }

    private fun assertOk(expected: String, vararg vars: Pair<String, String>) {
        val r = run(*vars)
        assertEquals(0, r.status, r.err)
        assertEquals("$expected\n", r.out)
        assertEquals("", r.err)
    }

    /** Asserts exit status 1 and exactly one violation, of [code] on [input]; returns its message. */
    private fun assertOneProblem(input: String, code: String, vararg vars: Pair<String, String>): String {
        val r = run(*vars)
        assertEquals(1, r.status, r.out)
        assertEquals("", r.out)
        val result = Docuconf.checkContract(ShopConfig.contract, env(*vars))
        assertIs<ContractFirst.Result.Failure>(result)
        assertEquals(listOf(input to code), result.violations.map { it.input to it.code }, result.violations.toString())
        val violation = result.violations.single()
        assertEquals("docuconf: 1 configuration problem:\n  $violation\n", r.err)
        return violation.message
    }

    private fun writeSigningKey(key: String) {
        val file = root.resolve("etc/shop/webhooks/signing.key")
        Files.createDirectories(file.parent)
        Files.writeString(file, key)
    }

    @Test
    fun `1 web starts with its origins`() =
        assertOk("shop web: port 8080, origins https://shop.example", "DOCUCONF_ROLE" to "web", "ALLOWED_ORIGINS" to "https://shop.example")

    @Test
    fun `2 worker needs no ALLOWED_ORIGINS`() =
        assertOk("shop worker: queues default,mail, webhooks off", "DOCUCONF_ROLE" to "worker", "QUEUES" to "default,mail")

    @Test
    fun `3 web without ALLOWED_ORIGINS`() {
        assertOneProblem("ALLOWED_ORIGINS", "missing_required", "DOCUCONF_ROLE" to "web")
    }

    @Test
    fun `4 no role is the only problem`() {
        assertOneProblem("DOCUCONF_ROLE", "missing_required", "QUEUES" to "default")
    }

    @Test
    fun `5 an unknown role is the only problem`() {
        assertOneProblem("DOCUCONF_ROLE", "not_in_enum", "DOCUCONF_ROLE" to "cron")
    }

    @Test
    fun `6 json logs require LOG_SCHEMA_URL`() {
        val message = assertOneProblem(
            "LOG_SCHEMA_URL", "missing_required",
            "DOCUCONF_ROLE" to "worker", "QUEUES" to "default", "LOG_FORMAT" to "json",
        )
        assertContains(message, "is required when LOG_FORMAT is \"json\"")
    }

    @Test
    fun `7 json logs with LOG_SCHEMA_URL`() = assertOk(
        "shop worker: queues default, webhooks off",
        "DOCUCONF_ROLE" to "worker", "QUEUES" to "default", "LOG_FORMAT" to "json",
        "LOG_SCHEMA_URL" to "https://logs.example/v1.json",
    )

    @Test
    fun `8 webhooks require the signing key file`() {
        val message = assertOneProblem(
            "webhook-signing-key", "file_missing",
            "DOCUCONF_ROLE" to "worker", "QUEUES" to "default", "WEBHOOKS_ENABLED" to "true",
        )
        assertContains(message, ", and is required when WEBHOOKS_ENABLED is true")
    }

    @Test
    fun `9 webhooks with the signing key file`() {
        writeSigningKey("k".repeat(40))
        assertOk(
            "shop worker: queues default, webhooks on",
            "DOCUCONF_ROLE" to "worker", "QUEUES" to "default", "WEBHOOKS_ENABLED" to "true",
        )
    }

    @Test
    fun `10 the signing key file belongs to the worker`() = assertOk(
        "shop web: port 8080, origins https://shop.example",
        "DOCUCONF_ROLE" to "web", "ALLOWED_ORIGINS" to "https://shop.example", "WEBHOOKS_ENABLED" to "true",
    )

    @Test
    fun `11 fragment variables are checked at boot`() {
        assertOneProblem(
            "OTEL_EXPORTER_OTLP_PROTOCOL", "not_in_enum",
            "DOCUCONF_ROLE" to "web", "ALLOWED_ORIGINS" to "https://shop.example",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "carrier-pigeon",
        )
    }

    @Test
    fun `the worker's queues and signing key are typed`() {
        val key = "k".repeat(40)
        writeSigningKey(key)
        val result = Docuconf.checkContract(
            ShopConfig.contract,
            env("DOCUCONF_ROLE" to "worker", "QUEUES" to "default,mail", "WEBHOOKS_ENABLED" to "true"),
        )
        assertIs<ContractFirst.Result.Success>(result)
        val config = assertIs<ShopConfig.Worker>(ShopConfig.from(result.values))
        assertEquals(listOf("default", "mail"), config.queues)
        assertEquals(key, config.webhookSigningKey)
    }

    @Test
    fun `secrets are never printed`() {
        val r = run("DATABASE_URL" to "mysql://shop:s3cret-pw@db/shop", "DOCUCONF_ROLE" to "web", "ALLOWED_ORIGINS" to "https://shop.example")
        assertEquals(1, r.status)
        assertContains(r.err, "DATABASE_URL: ")
        assertFalse("s3cret" in r.err, r.err)
        writeSigningKey("short-s3cret")
        val w = run("DOCUCONF_ROLE" to "worker", "QUEUES" to "default", "WEBHOOKS_ENABLED" to "true")
        assertEquals(1, w.status)
        assertContains(w.err, "webhook-signing-key: ")
        assertFalse("s3cret" in w.err, w.err)
    }
}
