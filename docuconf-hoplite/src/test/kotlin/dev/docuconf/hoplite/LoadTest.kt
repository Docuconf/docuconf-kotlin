package dev.docuconf.hoplite

import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.ConfigViolationException
import dev.docuconf.kotlin.core.Violation
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A complete, valid environment for [GatewayConfig] under a temporary file root. */
class World(val root: Path) {
    val env = mutableMapOf(
        "PUBLIC_URL" to "https://gw.example.com",
        "POD_NAMESPACE" to "prod",
        "DB_URL" to "postgres://app:hunter2@db/gw",
        "DOCUCONF_FILE_ROOT" to root.toString(),
        "HOSTNAME" to "gateway-7d9f", // undeclared variables are ignored
    )
    val serving: Certs.Issued = Certs.issue(Certs.ec(), listOf("gateway.internal", "api.example.com"))
    val terminationLog: Path = root.resolve("termination-log")
    val warnings = ArrayList<String>()

    init {
        write("/etc/gateway/routes/routes.yaml", "routes:\n  - match: /api\n    upstream: https://api.internal\n    timeout: 5s\n")
        write("/etc/gateway/license/license.key", "ABCDE-12345-FGHIJ-67890\n")
        Certs.writeTls(path("/etc/gateway/tls"), serving, Certs.sec1WithCurve(serving.keys))
    }

    fun path(abs: String): Path = Path.of(root.toString() + abs)

    fun write(abs: String, content: String): Path = path(abs).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, content)
    }

    val options: DocuconfOptions.() -> Unit = {
        env = this@World.env
        terminationLog = this@World.terminationLog.toString()
        warn = { warnings += it }
    }

    fun check() = Docuconf.check(GatewayConfig::class, DocuconfOptions().apply(options))

    fun violations(): List<Violation> = when (val r = check()) {
        is LoadResult.Failure -> r.violations
        is LoadResult.Success -> emptyList()
    }

    fun codes(): List<String> = violations().map { it.code }
}

class LoadTest {
    @Test
    fun loadsTypedValues(@TempDir root: Path) {
        val w = World(root)
        w.env += mapOf(
            "PORT" to "9090",
            "LOG_LEVEL" to "debug",
            "TRACE_RATIO" to "0.25",
            "COMPRESS" to "false",
            "TIMEOUT" to "PT1M30S",
            "IDLE" to "PT2M",
            "BROKERS" to "a:9092,b:9092",
            "ADMIN_PORTS" to "9100,9101",
            "RATE_LIMITS" to """{"perMinute":600,"burst":50}""",
            "TIER" to "pro",
            "DB_POOL_SIZE" to "20",
            "PARTNER_PASSWORD" to "changeit",
        )
        val cfg = Docuconf.load<GatewayConfig>(w.options)
        assertEquals(9090, cfg.port)
        assertEquals(LogLevel.debug, cfg.logLevel)
        assertEquals(0.25, cfg.traceRatio)
        assertFalse(cfg.compress)
        assertEquals(Duration.ofSeconds(90), cfg.timeout)
        assertEquals(2.minutes, cfg.idle)
        assertEquals(URI("https://gw.example.com"), cfg.publicUrl)
        assertEquals(listOf("a:9092", "b:9092"), cfg.brokers)
        assertEquals(listOf(9100, 9101), cfg.adminPorts)
        assertEquals(RateLimits(600, 50), cfg.rateLimits!!.value)
        assertEquals("pro", cfg.tier)
        assertEquals("prod", cfg.pod.namespace)
        assertEquals("postgres://app:hunter2@db/gw", cfg.db.url.value)
        assertEquals(20, cfg.db.poolSize)
        assertEquals("changeit", cfg.partner.password!!.value)
        assertEquals(listOf(Route("/api", "https://api.internal", Duration.ofSeconds(5))), cfg.routes.value.routes)
        assertEquals(listOf("gateway.internal", "api.example.com"), FileLoader.dnsNames(cfg.servingTls.certificate))
        assertEquals("ABCDE-12345-FGHIJ-67890\n", cfg.license.text, "text files are never trimmed")
        assertNull(cfg.upstreamCa)
        assertNull(cfg.geoip)
        assertNull(cfg.vaultToken)
        // A usable key store for Ktor's sslConnector.
        assertTrue(cfg.servingTls.toKeyStore().isKeyEntry("tls"))
    }

    @Test
    fun appliesDefaults(@TempDir root: Path) {
        val cfg = Docuconf.load<GatewayConfig>(World(root).options)
        assertEquals(8080, cfg.port)
        assertEquals(Duration.ofSeconds(30), cfg.timeout)
        assertEquals(90.seconds, cfg.idle)
        assertEquals(listOf("kafka-0:9092", "kafka-1:9092"), cfg.brokers)
        assertEquals(10, cfg.db.poolSize)
    }

    @Test
    fun durationsAreExactlyIso8601(@TempDir root: Path) {
        // The contract says iso8601, so that grammar exactly (SPEC §5): PT1,5S and days too, but not
        // the Go or Hoplite forms Hoplite alone would take.
        val w = World(root)
        w.env += mapOf("TIMEOUT" to "PT1,5S", "IDLE" to "P1DT3M")
        val cfg = Docuconf.load<GatewayConfig>(w.options)
        assertEquals(Duration.ofMillis(1500), cfg.timeout)
        assertEquals(24.hours + 3.minutes, cfg.idle)
        w.env += mapOf("TIMEOUT" to "45s", "IDLE" to "pt3m")
        assertEquals(listOf("TIMEOUT" to Codes.INVALID_TYPE, "IDLE" to Codes.INVALID_TYPE), w.violations().map { it.input to it.code }.sortedByDescending { it.first })
    }

    @Test
    fun missingRequiredVariable(@TempDir root: Path) {
        val w = World(root)
        w.env.remove("POD_NAMESPACE")
        assertEquals(listOf(Violation(Codes.MISSING_REQUIRED, "POD_NAMESPACE", "required, but not set")), w.violations())
    }

    @Test
    fun badInt(@TempDir root: Path) {
        val w = World(root)
        w.env["PORT"] = "eighty"
        val v = w.violations().single()
        assertEquals(Codes.INVALID_TYPE, v.code)
        assertEquals("PORT", v.input)
    }

    @Test
    fun listItemOutOfBounds(@TempDir root: Path) {
        val w = World(root)
        w.env["ADMIN_PORTS"] = "9100,0,70000"
        assertEquals(listOf("ADMIN_PORTS:out_of_range", "ADMIN_PORTS:out_of_range"), w.violations().map { "${it.input}:${it.code}" })
    }

    @Test
    fun intBeyond64BitsIsOutOfRange(@TempDir root: Path) {
        val w = World(root)
        w.env["DB_POOL_SIZE"] = "99999999999999999999"
        assertEquals(listOf("DB_POOL_SIZE:out_of_range"), w.violations().map { "${it.input}:${it.code}" })
    }

    @Test
    fun emptyStringIsUnsetForNonStrings(@TempDir root: Path) {
        val w = World(root)
        w.env += mapOf("PORT" to "", "TIMEOUT" to "", "COMPRESS" to "", "LEGACY_PORT" to "")
        val cfg = Docuconf.load<GatewayConfig>(w.options)
        assertEquals(8080, cfg.port)
        assertEquals(Duration.ofSeconds(30), cfg.timeout)
        assertNull(cfg.legacyPort)
        w.env["DB_URL"] = ""
        assertEquals(listOf(Codes.MISSING_REQUIRED), w.codes())
    }

    @Test
    fun emptyStringIsAValueForStrings(@TempDir root: Path) {
        val w = World(root)
        w.env["REGION"] = ""
        assertEquals(listOf(Codes.PATTERN_MISMATCH), w.codes())
    }

    @Test
    fun secretsAreRedacted(@TempDir root: Path) {
        val w = World(root)
        w.env["DB_URL"] = "mysql://app:hunter2@db/gw"
        val e = assertFailsWith<ConfigViolationException> { Docuconf.load<GatewayConfig>(w.options) }
        assertEquals(listOf(Codes.INVALID_SCHEME), e.violations.map { it.code })
        assertFalse("hunter2" in e.message!!)
        assertFalse("hunter2" in Files.readString(w.terminationLog))
        assertFalse("hunter2" in e.violations.toString())
    }

    @Test
    fun unresolvedInjectorReferenceFailsWithoutPrintingIt(@TempDir root: Path) {
        val w = World(root)
        w.env["DB_URL"] = "vault:secret/data/gateway/db#url"
        val e = assertFailsWith<ConfigViolationException> { Docuconf.load<GatewayConfig>(w.options) }
        assertEquals(
            listOf(Violation(Codes.INVALID_TYPE, "DB_URL", "holds an unresolved vault: reference; the injector that should resolve it did not run")),
            e.violations,
        )
        val log = Files.readString(w.terminationLog)
        assertContains(log, "DB_URL: invalid_type: holds an unresolved vault: reference")
        for (text in listOf(e.message!!, log)) assertFalse("secret/data/gateway" in text, text)
    }

    @Test
    fun reportsAllViolationsTogether(@TempDir root: Path) {
        val w = World(root)
        w.env.remove("POD_NAMESPACE")
        w.env += mapOf(
            "PORT" to "70000",
            "LOG_LEVEL" to "verbose",
            "TRACE_RATIO" to "NaN",
            "BROKERS" to "",
            "ADMIN_PORTS" to "1,2,3,4,5",
            "RATE_LIMITS" to """{"perMinute":0}""",
            "PUBLIC_URL" to "http://gw.example.com",
            "TIMEOUT" to "PT10M",
        )
        Files.delete(w.path("/etc/gateway/license/license.key"))
        w.write("/etc/gateway/routes/routes.yaml", "routes: []\n")
        val e = assertFailsWith<ConfigViolationException> { Docuconf.load<GatewayConfig>(w.options) }
        val got = e.violations.map { "${it.input}:${it.code}" }.toSet()
        assertEquals(
            setOf(
                "POD_NAMESPACE:missing_required",
                "PORT:out_of_range",
                "LOG_LEVEL:not_in_enum",
                "TRACE_RATIO:invalid_type",
                "ADMIN_PORTS:too_many_items",
                "RATE_LIMITS:schema_mismatch",
                "PUBLIC_URL:invalid_scheme",
                "TIMEOUT:out_of_range",
                "license:file_missing",
                "routes:schema_mismatch",
            ),
            got,
        )
        val log = Files.readString(w.terminationLog)
        for (line in got) assertContains(log, line.substringBefore(':'))
    }

    @Test
    fun warnsWhenDeprecatedVariableIsSet(@TempDir root: Path) {
        val w = World(root)
        w.env["LEGACY_PORT"] = "81"
        Docuconf.load<GatewayConfig>(w.options)
        assertTrue(w.warnings.any { "LEGACY_PORT is deprecated" in it && "PORT" in it }, w.warnings.toString())
    }

    @Test
    fun dotenvIsOptInAndRealEnvWins(@TempDir root: Path) {
        val w = World(root)
        w.env.remove("POD_NAMESPACE")
        val dotenv = root.resolve(".env")
        Files.writeString(dotenv, "# local dev\nPOD_NAMESPACE=dev\nPORT=\"7000\"\n")
        w.env["PORT"] = "7001"
        val cfg = Docuconf.load(GatewayConfig::class, DocuconfOptions().apply(w.options).apply { this.dotenv = dotenv })
        assertEquals("dev", cfg.pod.namespace)
        assertEquals(7001, cfg.port)
    }

    @Test
    fun pathEnvIsRootedToo(@TempDir root: Path) {
        val w = World(root)
        w.write("/srv/routes/custom.yaml", "routes:\n  - match: /other\n    upstream: http://other\n")
        w.env["ROUTES_FILE"] = "/srv/routes/custom.yaml"
        val cfg = Docuconf.load<GatewayConfig>(w.options)
        assertEquals("/other", cfg.routes.value.routes.single().match)
        assertEquals(w.path("/srv/routes/custom.yaml"), cfg.routes.path)
    }

    @Test
    fun prefixedEnvironment(@TempDir root: Path) {
        val w = World(root)
        val prefixed = w.env.mapKeys { (k, _) -> if (k == "DOCUCONF_FILE_ROOT") k else "GW_$k" }
        val cfg = Docuconf.load<GatewayConfig> {
            w.options(this)
            env = prefixed + ("GW_PORT" to "1234") + ("PORT" to "1")
            prefix = "GW_"
        }
        assertEquals(1234, cfg.port)
    }

    @Test
    fun baseFileValuesSatisfyRequiredVariables(@TempDir root: Path) {
        val w = World(root)
        w.env.remove("POD_NAMESPACE")
        w.env["PORT"] = "9999"
        val base = w.write("/app-config/application.yaml", "port: 1111\npod:\n  namespace: from-file\n")
        val cfg = Docuconf.load<GatewayConfig> {
            w.options(this)
            baseSources = listOf(base.toString())
        }
        assertEquals("from-file", cfg.pod.namespace)
        assertEquals(9999, cfg.port, "environment variables override base files")
    }

    @Test
    fun loadResultCarriesWarnings(@TempDir root: Path) {
        val r = World(root).check()
        assertIs<LoadResult.Success<GatewayConfig>>(r)
    }

    @Test
    fun lengthLimitsAtBoot(@TempDir dir: Path) {
        data class Limits(val max: Int)
        data class Batch(
            @Doc("Where to report each run") @Length(max = 24) val callback: URI? = null,
            @Doc("Run limits as a JSON object") @Length(max = 16) val limits: Json<Limits>? = null,
            @Doc("Branch codes, two to four characters each") @ItemLength(min = 2, max = 4) val branches: List<String> = emptyList(),
            @Doc("Database URL") @Url @Length(max = 30) val dbUrl: com.sksamuel.hoplite.Secret? = null,
        )
        fun check(env: Map<String, String>) = Docuconf.check(Batch::class, DocuconfOptions().apply { this.env = env; terminationLog = dir.resolve("termination-log").toString() })
        val ok = check(mapOf("BRANCHES" to "ZÜ01,日本,\uD83D\uDE80\uD83D\uDE80", "LIMITS" to "{\"max\":12345678}", "CALLBACK" to "https://例え.jp/日本語の道/一二三四"))
        assertIs<LoadResult.Success<Batch>>(ok, ok.toString())
        assertEquals(listOf("ZÜ01", "日本", "\uD83D\uDE80\uD83D\uDE80"), ok.value.branches)
        val bad = check(mapOf("BRANCHES" to "BE,ZÜRICH", "LIMITS" to "{ \"max\": 123456 }", "CALLBACK" to "https://a.example/runs/42", "DB_URL" to "postgres://app:s3cr3t@db:5432/app"))
        assertIs<LoadResult.Failure>(bad)
        assertEquals(listOf("BRANCHES", "CALLBACK", "DB_URL", "LIMITS"), bad.violations.map { it.input }.sorted())
        assertTrue(bad.violations.all { it.code == Codes.OUT_OF_RANGE }, bad.violations.toString())
        assertFalse(bad.violations.toString().contains("s3cr3t"))
    }

    // A key set (SPEC §6.1, docuconf-go conformance/load/key_set.yaml): a List<Secret> of one or two keys.
    @Test
    fun secretListAtBoot(@TempDir dir: Path) {
        data class Hooks(
            @Doc("Keys that verify webhook signatures") @Items(min = 1, max = 2) @ItemLength(min = 32, max = 256)
            val webhookKeys: List<com.sksamuel.hoplite.Secret>? = null,
        )
        val old = "old-webhook-key-0123456789abcdef0123"
        val new = "new-webhook-key-0123456789abcdef0123"
        fun check(value: String) = Docuconf.check(Hooks::class, DocuconfOptions().apply { env = mapOf("WEBHOOK_KEYS" to value); terminationLog = dir.resolve("termination-log").toString() })
        val ok = check("$old,$new")
        assertIs<LoadResult.Success<Hooks>>(ok, ok.toString())
        assertEquals(listOf(old, new), ok.value.webhookKeys!!.map { it.value })
        assertFalse(ok.value.toString().contains(old), ok.value.toString())
        val unset = check("")
        assertIs<LoadResult.Success<Hooks>>(unset, unset.toString())
        assertEquals(null, unset.value.webhookKeys)
        for ((value, code) in listOf("$old," to Codes.OUT_OF_RANGE, "$old,new-webhook-key" to Codes.OUT_OF_RANGE, "$old,$new,$old" to Codes.TOO_MANY_ITEMS)) {
            val bad = check(value)
            assertIs<LoadResult.Failure>(bad)
            // One problem each: an empty item is out_of_range under itemMinLength, not also invalid_type.
            assertEquals(listOf(code to "WEBHOOK_KEYS"), bad.violations.map { it.code to it.input }, bad.violations.toString())
            assertFalse(bad.violations.toString().contains("webhook-key"), bad.violations.toString())
        }
    }
}
