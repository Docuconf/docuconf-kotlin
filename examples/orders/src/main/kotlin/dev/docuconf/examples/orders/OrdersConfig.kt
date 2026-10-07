package dev.docuconf.examples.orders

import com.sksamuel.hoplite.Secret
import dev.docuconf.hoplite.Doc
import dev.docuconf.hoplite.DurationMax
import dev.docuconf.hoplite.DurationMin
import dev.docuconf.hoplite.Items
import dev.docuconf.hoplite.Max
import dev.docuconf.hoplite.Min
import dev.docuconf.hoplite.Schemes
import java.time.Duration

// A plain Hoplite config class. The docuconf annotations add what Hoplite cannot express
// (descriptions, bounds, the secret, URL schemes); docuconf exports them to contract.cue and checks
// them at boot.
//
// Hoplite reads `_` in an environment variable as a nesting level, so LOG_LEVEL is `log.level`.
// A flat `logLevel` would read LOGLEVEL instead.
data class OrdersConfig(
    @Doc("HTTP listen port") @Min(1) @Max(65535) val port: Int = 8080,
    val log: Log = Log(),
    val database: Database,
    val allowed: Allowed = Allowed(),
    val request: Request = Request(),
    val worker: Worker = Worker(),
)

@Suppress("EnumEntryName")
enum class LogLevel { debug, info, warn, error }

data class Log(
    @Doc("Minimum level of log messages") val level: LogLevel = LogLevel.info,
)

data class Database(
    // A Hoplite Secret is exported with `secret: true`; its value never appears in errors.
    @Doc("Postgres connection URL for the orders database") @Schemes("postgres") val url: Secret,
)

data class Allowed(
    @Doc("Origins allowed to call the API (CORS)") @Items(min = 1) val origins: List<String> = listOf("http://localhost:3000"),
)

data class Request(
    @Doc("Time limit for handling one request") @DurationMin("1s") @DurationMax("5m") val timeout: Duration = Duration.ofSeconds(30),
)

data class Worker(
    @Doc("Number of background workers that process orders") @Min(1) @Max(64) val count: Int = 4,
)
