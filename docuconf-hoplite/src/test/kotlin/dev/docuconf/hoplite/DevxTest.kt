package dev.docuconf.hoplite

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.Secret
import com.sksamuel.hoplite.addResourceSource
import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.ConfigViolationException
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.Violation
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Regression tests for the first-time-user review: env naming, withDocuconf(), @DocuconfService,
// declaration mistakes, loadOrExit, typo hints, durations, enum wire names and test helpers.

enum class Level { @WireName("debug") DEBUG, @WireName("info") INFO, @WireName("warn") WARN }

/** The orders example's six variables, declared flat, the way a Hoplite user writes them. */
data class FlatOrders(
    @Doc("HTTP listen port") @Min(1) @Max(65535) val port: Int = 8080,
    @Doc("Minimum log level") val logLevel: Level = Level.INFO,
    @Doc("Postgres connection URL") @Env("DATABASE_URL") @Schemes("postgres") val db: Secret,
    @Doc("Origins allowed to call the API") @Items(min = 1) val allowedOrigins: List<String> = listOf("http://localhost:3000"),
    @Doc("Time limit for one request") @DurationMin("1s") @DurationMax("PT5M") val requestTimeout: Duration = Duration.ofSeconds(30),
    @Doc("Background workers") @Min(1) @Max(64) val workerCount: Int = 4,
)

data class Pool(@Doc("Connection pool size") val poolSize: Int = 10, @Doc("Database host name") val host: String = "db")

data class Nested(
    val db: Pool = Pool(),
    @Env("PG") val postgres: Pool = Pool(),
    @Doc("Upstream base URL") val httpURL: String = "http://x",
    @Doc("OAuth client token") val oauth2Token: String = "t",
)

@DocuconfService(name = "orders", prefix = "APP_", baseSources = ["/devx/application.yaml"])
data class Prefixed(
    @Doc("HTTP listen port") val port: Int = 8080,
    @Doc("Background workers") val workerCount: Int = 4,
)

@DocuconfService(name = "orders")
data class Plain(
    @Doc("HTTP listen port") val port: Int = 8080,
    @Doc("Background workers") val workerCount: Int = 4,
)

sealed interface Shape

data class Circle(val r: Int) : Shape

data class WithSealed(@Doc("HTTP listen port") val port: Int = 8080, @NotInContract val shape: Shape? = null)

data class Nic(@Doc("HTTP listen port") val port: Int = 8080, @NotInContract val apiKey: Secret)

data class Validated(@Doc("Database password here") val password: Secret) {
    init {
        require(password.value.length > 20) { "password ${password.value} is too short" }
    }
}

class DevxTest {
    private fun flatEnv(vararg extra: Pair<String, String>) = mapOf("DATABASE_URL" to "postgres://u:hunter2@h/db") + extra

    @Test
    fun camelCaseReadsScreamingSnake() {
        val c = Docuconf.contract(FlatOrders::class, "orders")
        assertEquals(listOf("ALLOWED_ORIGINS", "DATABASE_URL", "LOG_LEVEL", "PORT", "REQUEST_TIMEOUT", "WORKER_COUNT"), c.vars.map { it.name }.sorted())
        assertEquals("db", c.variable("DATABASE_URL")!!.configKey)

        val cfg = Docuconf.load<FlatOrders> {
            env = flatEnv("LOG_LEVEL" to "debug", "WORKER_COUNT" to "12", "REQUEST_TIMEOUT" to "PT1M30S", "ALLOWED_ORIGINS" to "https://a,https://b")
            warn = {}
        }
        assertEquals(Level.DEBUG, cfg.logLevel)
        assertEquals(12, cfg.workerCount)
        assertEquals(Duration.ofSeconds(90), cfg.requestTimeout)
        assertEquals(listOf("https://a", "https://b"), cfg.allowedOrigins)

        // The critic's run: WORKER_COUNT=999 is no longer ignored.
        val e = assertFailsWith<ConfigViolationException> { Docuconf.load<FlatOrders> { env = flatEnv("WORKER_COUNT" to "999"); warn = {} } }
        assertEquals(listOf(Violation(Codes.OUT_OF_RANGE, "WORKER_COUNT", "\"999\" is above max 64")), e.violations)
    }

    @Test
    fun nestedNamesAndEnvOverrides() {
        assertEquals("LOG_LEVEL", DeclarationReader.envSegment("logLevel"))
        assertEquals("HTTP_URL", DeclarationReader.envSegment("httpURL"))
        assertEquals("HTTP_PORT", DeclarationReader.envSegment("HTTPPort"))
        assertEquals("OAUTH2_TOKEN", DeclarationReader.envSegment("oauth2Token"))
        assertEquals("POOL_SIZE", DeclarationReader.envSegment("pool_size"))
        val names = Docuconf.contract(Nested::class, "svc").vars.map { it.name }.sorted()
        assertEquals(listOf("DB_HOST", "DB_POOL_SIZE", "HTTP_URL", "OAUTH2_TOKEN", "PG_HOST", "PG_POOL_SIZE"), names)
        val cfg = Docuconf.load<Nested> { env = mapOf("DB_POOL_SIZE" to "3", "PG_POOL_SIZE" to "7", "HTTP_URL" to "http://up") }
        assertEquals(3, cfg.db.poolSize)
        assertEquals(7, cfg.postgres.poolSize)
        assertEquals("http://up", cfg.httpURL)
    }

    @Test
    fun enumWireNames() {
        val c = Docuconf.contract(FlatOrders::class, "orders")
        assertEquals(listOf("debug", "info", "warn"), c.variable("LOG_LEVEL")!!.values)
        assertEquals("\"info\"", c.variable("LOG_LEVEL")!!.default.toString())
        val e = assertFailsWith<ConfigViolationException> { Docuconf.load<FlatOrders> { env = flatEnv("LOG_LEVEL" to "DEBUG"); warn = {} } }
        assertEquals(Codes.NOT_IN_ENUM, e.violations.single().code)
    }

    @Test
    fun serviceAnnotationIsTheOnePlaceForPrefixAndBaseSources() {
        // Export needs no --service, --prefix or --base: the annotation has them.
        val c = Docuconf.contract(Prefixed::class)
        assertEquals("orders", c.service)
        assertEquals(listOf("APP_PORT", "APP_WORKER_COUNT"), c.vars.map { it.name })
        assertEquals("8", c.variable("APP_WORKER_COUNT")!!.default.toString(), "the base file's value is the exported default")
        // Boot reads the same names and the same base file.
        val warnings = ArrayList<String>()
        val cfg = Docuconf.load<Prefixed> { env = mapOf("APP_PORT" to "9000", "PORT" to "1"); warn = { warnings += it } }
        assertEquals(9000, cfg.port)
        assertEquals(8, cfg.workerCount)
        // The service name cannot disagree with the annotation.
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(Prefixed::class, "billing") }.message!!, "set it in one place")
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(Nested::class) }.message!!, "@DocuconfService")
    }

    @Test
    fun withDocuconfExtendsTheUsersLoader() {
        val cfg = ConfigLoaderBuilder.default()
            .addResourceSource("/devx/application.yaml")
            .withDocuconf { env = mapOf("APP_PORT" to "7000") }
            .build()
            .loadConfigOrThrow<Prefixed>()
        assertEquals(7000, cfg.port)
        assertEquals(8, cfg.workerCount)

        // Hoplite's own environment source is replaced: an empty PORT counts as unset, and
        // Hoplite's `_`-as-nesting never applies.
        val plain = ConfigLoaderBuilder.default()
            .withDocuconf { env = mapOf("PORT" to "", "WORKER_COUNT" to "6") }
            .build()
            .loadConfigOrThrow<Plain>()
        assertEquals(Plain(8080, 6), plain)

        val failed = ConfigLoaderBuilder.default().withDocuconf { env = mapOf("PORT" to "x"); warn = {} }.build().loadConfig<Plain>()
        assertIs<LoadResult.Failure>(failed)
        assertEquals(Codes.INVALID_TYPE, failed.violations.single().code)
    }

    @Test
    fun aLoaderFileTheContractDoesNotKnowIsADeclarationError() {
        // Plain's contract has no base sources, so it exports WORKER_COUNT with default 4. The
        // user's loader reads 16 from a file: the platform and the app would disagree.
        val e = assertFailsWith<DeclarationException> {
            ConfigLoaderBuilder.default().addResourceSource("/devx/other.yaml").withDocuconf { env = emptyMap() }.build().loadConfigOrThrow<Plain>()
        }
        assertContains(e.message!!, "WORKER_COUNT: your Hoplite loader reads workerCount = 16")
        assertContains(e.message!!, "@DocuconfService(baseSources = [...])")
    }

    @Test
    fun declarationMistakesAreErrors() {
        fun problems(k: kotlin.reflect.KClass<*>) = assertFailsWith<DeclarationException> { Docuconf.contract(k, "svc") }.problems

        data class MinOnString(@Doc("A name here") @Min(1) val name: String = "x")
        assertEquals(listOf("MinOnString.name: @Min applies to Int, Long, Short or Byte, not String; use @Length(min = ..., max = ...) for a string's length"), problems(MinOnString::class))

        data class SchemesOnInt(@Doc("A port here") @Schemes("http") val port: Int = 1)
        assertEquals(listOf("SchemesOnInt.port: @Schemes applies to String, Secret, URI or URL, not Int"), problems(SchemesOnInt::class))

        data class BadDuration(@Doc("A timeout here") @DurationMax("1 minute") val t: Duration = Duration.ofSeconds(1))
        assertEquals(
            listOf("BadDuration.t: @DurationMax(\"1 minute\") is not a duration; write Go syntax such as \"1m\" or \"1h30m\", or ISO 8601 such as \"PT1M\""),
            problems(BadDuration::class),
        )

        data class SecretDefault(@Doc("A secret token") val token: Secret = Secret("abc"))
        assertEquals(listOf("SecretDefault.token: a secret cannot have a default; remove it, and let the platform set TOKEN"), problems(SecretDefault::class))

        data class OnNested(@Doc("The pool") @Min(1) val db: Pool = Pool())
        assertEquals(2, problems(OnNested::class).count { "has no effect on a nested config class" in it })

        data class FormatOnVar(@Doc("A name here") @Format(dev.docuconf.kotlin.core.ConfigFormat.YAML) val name: String = "x")
        assertContains(problems(FormatOnVar::class).single(), "@Format applies to file inputs")

        data class EnvOnFile(@Doc("Licence key") @Env("LICENSE") @FileInput(name = "license", path = "/etc/svc/license.key") val license: TextFile)
        assertContains(problems(EnvOnFile::class).single(), "pathEnv")

        data class LengthOnList(@Doc("Some names") @Length(min = 1) val names: List<String> = listOf("a"))
        assertContains(problems(LengthOnList::class).single(), "use @Items")

        data class OneOfOnEnum(@Doc("Minimum level") @OneOf("debug") val level: Level = Level.INFO)
        assertContains(problems(OneOfOnEnum::class).single(), "an enum class needs no annotation")
    }

    @Test
    fun durationErrorsNameTheExpectedForm() {
        val e = assertFailsWith<ConfigViolationException> { Docuconf.load<FlatOrders> { env = flatEnv("REQUEST_TIMEOUT" to "soon"); warn = {} } }
        assertEquals(
            "\"soon\" is not a duration; expected an ISO 8601 duration like PT30S",
            e.violations.single().message,
        )
        // Bounds written in ISO 8601 are exported in Go syntax, as the spec requires.
        assertEquals("5m", Docuconf.contract(FlatOrders::class, "orders").variable("REQUEST_TIMEOUT")!!.maxDuration)

        data class K(@Doc("Idle timeout here") val idle: kotlin.time.Duration = 5.seconds)
        assertEquals(90.seconds, Docuconf.load<K> { env = mapOf("IDLE" to "PT1M30S") }.idle)
    }

    @Test
    fun typoedNamesGetAHint() {
        val warnings = ArrayList<String>()
        val e = assertFailsWith<ConfigViolationException> {
            Docuconf.load<FlatOrders> { env = mapOf("DATABSE_URL" to "postgres://u:hunter2@h/db", "PATH" to "/bin"); warn = { warnings += it } }
        }
        assertEquals(listOf("DATABSE_URL is set but not declared; did you mean DATABASE_URL?"), warnings)
        assertEquals("required, but not set (DATABSE_URL is set; a typo?)", e.violations.single().message)
        assertFalse(warnings.any { "hunter2" in it })
    }

    @Test
    fun loadOrExitPrintsTheReportAndExits(@TempDir dir: Path) {
        val out = ByteArrayOutputStream()
        val log = dir.resolve("termination-log")
        val options = DocuconfOptions().apply { env = mapOf("PORT" to "0"); terminationLog = log.toString(); warn = {} }
        val status = assertFailsWith<Exit> {
            Docuconf.loadOrExit(FlatOrders::class, options, null, PrintStream(out)) { throw Exit(it) }
        }.status
        assertEquals(1, status)
        val expected = """
            docuconf: 2 configuration problems:
              PORT: out_of_range: "0" is below min 1
              DATABASE_URL: missing_required: required, but not set
        """.trimIndent()
        assertEquals(expected + "\n", out.toString())
        assertEquals(expected + "\n", Files.readString(log))

        data class Broken(@Doc("A name here") @Min(1) val name: String = "x")
        val out2 = ByteArrayOutputStream()
        assertFailsWith<Exit> { Docuconf.loadOrExit(Broken::class, DocuconfOptions().apply { env = emptyMap(); terminationLog = log.toString() }, null, PrintStream(out2)) { throw Exit(it) } }
        assertTrue(out2.toString().startsWith("docuconf: invalid docuconf declaration:\n  - Broken.name: @Min applies"), out2.toString())
        assertFalse("Exception" in out2.toString())
    }

    class Exit(val status: Int) : RuntimeException()

    @Test
    fun noSealedTypeNoticeOnBoot() {
        val (out, err) = captured { Docuconf.load<Plain> { env = emptyMap() } }
        assertFalse("sealed" in out + err, out + err)
        val (out2, err2) = captured { ConfigLoaderBuilder.default().withDocuconf { env = emptyMap() }.build().loadConfigOrThrow<Plain>() }
        assertFalse("sealed" in out2 + err2, out2 + err2)
        // A config that does hold a sealed type keeps Hoplite's behaviour (and its notice).
        Docuconf.load<WithSealed> { env = emptyMap() }
    }

    @Test
    fun hopliteFailuresAreMappedToVariables() {
        val e = assertFailsWith<ConfigViolationException> { Docuconf.load<Nic> { env = emptyMap() } }
        assertEquals(
            listOf(Violation(Codes.MISSING_REQUIRED, "apiKey", "required, but no config source sets it (it is not in the contract, so the platform does not supply it)")),
            e.violations,
        )
    }

    @Test
    fun secretsNeverPrint() {
        val cfg = Docuconf.load<FlatOrders> { env = flatEnv() }
        assertFalse("hunter2" in cfg.toString(), cfg.toString())
        // A user's own validator that puts the value in its message.
        val e = assertFailsWith<ConfigViolationException> { Docuconf.load<Validated> { env = mapOf("PASSWORD" to "hunter2") } }
        assertFalse("hunter2" in e.message!!, e.message)
        assertContains(e.message!!, "is too short")
    }

    @Test
    fun csvItemsAreNeverTrimmed() {
        // SPEC §5: split on every separator, never trimmed, so an empty item is an item; bound it with
        // @ItemLength(min = 1) to reject one.
        val cfg = Docuconf.load<FlatOrders> { env = flatEnv("ALLOWED_ORIGINS" to " https://a,,https://b "); warn = {} }
        assertEquals(listOf(" https://a", "", "https://b "), cfg.allowedOrigins)
    }

    @Test
    fun hopliteBlocksAccumulate() {
        var calls = 0
        Docuconf.load<Plain> {
            env = emptyMap()
            hoplite { calls++ }
            hoplite { calls += 10 }
        }
        assertEquals(11, calls)
    }

    @Test
    fun fileTypesHaveTestFactories() {
        data class Routes(val routes: List<String>)
        data class App(
            @Doc("Routing table") @FileInput(name = "routes", path = "/etc/app/routes.yaml") val routes: ConfigFile<Routes>,
            @Doc("Licence key") @FileInput(name = "license", path = "/etc/app/license/key.txt", secret = true) val license: TextFile,
        )
        val app = App(ConfigFile.of(Routes(listOf("/api"))), TextFile.of("KEY", secret = true))
        assertEquals("/api", app.routes.value.routes.single())
        assertFalse("KEY" in app.toString())
        val issued = Certs.issue(Certs.ec(), listOf("app.example.com"))
        assertEquals(issued.cert, TlsKeyPair.of(listOf(issued.cert), issued.keys.private).certificate)
    }

    @Test
    fun loadingFromAMapNeverTouchesTheProcessEnvironment() {
        val before = System.getenv()
        Docuconf.load<Plain> { env = mapOf("PORT" to "1234") }
        assertEquals(before, System.getenv())
        assertEquals(null, System.getenv("PORT")?.takeIf { it == "1234" })
    }

    @Test
    fun exportCommandHelpAndCheck(@TempDir dir: Path) {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        assertEquals(0, export(arrayOf("--help"), PrintStream(out), PrintStream(err)))
        assertContains(out.toString(), "--check")

        val file = dir.resolve("contract.cue")
        val args = arrayOf("--class", Prefixed::class.java.name, "--out", file.toString())
        assertEquals(0, export(args, PrintStream(out), PrintStream(err)))
        assertContains(Files.readString(file), "APP_WORKER_COUNT")
        assertEquals(0, export(args + "--check", PrintStream(out), PrintStream(err)))

        Files.writeString(file, Files.readString(file).replace("default: 8\n", "default: 9\n"))
        val err2 = ByteArrayOutputStream()
        assertEquals(1, export(args + "--check", PrintStream(out), PrintStream(err2)))
        assertContains(err2.toString(), "is out of date")
        assertContains(err2.toString(), "-\t\t\tdefault: 9\n+\t\t\tdefault: 8")

        val err3 = ByteArrayOutputStream()
        assertEquals(2, export(args + listOf("--prefix", "APP_"), PrintStream(out), PrintStream(err3)))
        assertContains(err3.toString(), "@DocuconfService(prefix = ...)")
    }

    private fun captured(block: () -> Unit): Pair<String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val (o, e) = System.out to System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        try {
            block()
        } finally {
            System.setOut(o)
            System.setErr(e)
        }
        return out.toString() to err.toString()
    }
}
