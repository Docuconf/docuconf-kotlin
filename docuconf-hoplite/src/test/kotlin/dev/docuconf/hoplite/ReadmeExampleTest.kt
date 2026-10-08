package dev.docuconf.hoplite

import com.sksamuel.hoplite.Secret
import dev.docuconf.kotlin.core.ConfigViolationException
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// An end-to-end load with every kind of input: env variables, a TLS key pair and a config file.

data class ExampleDatabase(
    @Doc("Postgres connection string") @Schemes("postgres") val url: Secret,
    @Doc("Connection pool size") @Min(1) @Max(50) val poolSize: Int = 10,
)

data class ExampleRoute(@Pattern("^/") val prefix: String, val upstream: String)

data class ExampleRoutes(@Items(min = 1) val routes: List<ExampleRoute>)

data class AppConfig(
    @Doc("HTTP listen port") @Min(1) @Max(65535) val port: Int = 8080,
    @Doc("Upstream request timeout") @DurationMax("1m") val timeout: Duration = Duration.ofSeconds(10),
    val db: ExampleDatabase,

    @Doc("Certificate served on HTTPS")
    @FileInput(name = "serving-tls", path = "/etc/app/tls")
    @Tls(dnsNames = ["app.example.com"], minRemaining = "720h")
    val tls: TlsKeyPair,

    @Doc("Routing table")
    @FileInput(name = "routes", path = "/etc/app/routes/routes.yaml", pathEnv = "ROUTES_FILE")
    val routes: ConfigFile<ExampleRoutes>,
)

class ReadmeExampleTest {
    @Test
    fun example(@TempDir root: Path) {
        val leaf = Certs.issue(Certs.ec(), listOf("app.example.com"))
        Certs.writeTls(Path.of("$root/etc/app/tls"), leaf)
        Files.createDirectories(Path.of("$root/etc/app/routes"))
        Files.writeString(Path.of("$root/etc/app/routes/routes.yaml"), "routes:\n  - prefix: /api\n    upstream: http://api:8080\n")
        val env = mapOf("DB_URL" to "postgres://app:pw@db/app", "TIMEOUT" to "PT30S", "DOCUCONF_FILE_ROOT" to root.toString())

        val config = Docuconf.load<AppConfig> { this.env = env }
        assertEquals(10, config.db.poolSize)
        assertEquals(Duration.ofSeconds(30), config.timeout)
        assertEquals("/api", config.routes.value.routes.single().prefix)

        val e = assertFailsWith<ConfigViolationException> {
            Docuconf.load<AppConfig> { this.env = env + mapOf("PORT" to "0", "DB_URL" to "mysql://app:pw@db/app", "TIMEOUT" to "PT5M"); terminationLog = "$root/log" }
        }
        val expected = """
            docuconf: 3 configuration problems:
              PORT: out_of_range: "0" is below min 1
              TIMEOUT: out_of_range: "PT5M" is longer than max 1m
              DB_URL: invalid_scheme: scheme "mysql" is not one of postgres
        """.trimIndent()
        assertEquals(expected, e.message)

        val cue = Docuconf.exportCue(AppConfig::class, "app", appVersion = "1.4.0")
        assertContains(cue, "DB_URL: {")
        if (System.getenv("PRINT_README_EXAMPLE") == "1") println(cue)
    }
}
