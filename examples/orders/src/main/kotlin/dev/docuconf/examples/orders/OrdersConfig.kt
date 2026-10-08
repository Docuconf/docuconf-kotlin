package dev.docuconf.examples.orders

import com.sksamuel.hoplite.Secret
import dev.docuconf.hoplite.Doc
import dev.docuconf.hoplite.DocuconfService
import dev.docuconf.hoplite.DurationMax
import dev.docuconf.hoplite.DurationMin
import dev.docuconf.hoplite.Items
import dev.docuconf.hoplite.Max
import dev.docuconf.hoplite.Min
import dev.docuconf.hoplite.Schemes
import dev.docuconf.hoplite.WireName
import java.time.Duration

// A plain Hoplite config class. The docuconf annotations add what Hoplite cannot express
// (descriptions, bounds, the secret, URL schemes); docuconf exports them to contract.cue and checks
// them at boot. Each property reads its name in SCREAMING_SNAKE_CASE: logLevel reads LOG_LEVEL.
@DocuconfService(name = "orders")
data class OrdersConfig(
    @Doc("HTTP listen port") @Min(1) @Max(65535) val port: Int = 8080,
    @Doc("Minimum level of log messages") val logLevel: LogLevel = LogLevel.INFO,
    // A Hoplite Secret is exported with `secret: true`; its value never appears in errors or toString.
    @Doc("Postgres connection URL for the orders database") @Schemes("postgres") val databaseUrl: Secret,
    @Doc("Origins allowed to call the API (CORS)") @Items(min = 1) val allowedOrigins: List<String> = listOf("http://localhost:3000"),
    @Doc("Time limit for handling one request") @DurationMin("1s") @DurationMax("5m") val requestTimeout: Duration = Duration.ofSeconds(30),
    @Doc("Number of background workers that process orders") @Min(1) @Max(64) val workerCount: Int = 4,
)

/** Lowercase on the wire (`LOG_LEVEL=debug`), idiomatic constants in Kotlin. */
enum class LogLevel {
    @WireName("debug") DEBUG,
    @WireName("info") INFO,
    @WireName("warn") WARN,
    @WireName("error") ERROR,
}
