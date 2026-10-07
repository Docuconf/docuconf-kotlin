package com.example

import com.sksamuel.hoplite.Secret
import dev.docuconf.hoplite.Doc
import dev.docuconf.hoplite.DocuconfService
import dev.docuconf.hoplite.DurationMax
import dev.docuconf.hoplite.Max
import dev.docuconf.hoplite.Min
import dev.docuconf.hoplite.Schemes
import dev.docuconf.hoplite.WireName
import java.time.Duration

@DocuconfService(name = "app")
data class AppConfig(
    @Doc("HTTP listen port") @Min(1) @Max(65535) val port: Int = 8080,                      // PORT
    @Doc("Minimum log level") val logLevel: LogLevel = LogLevel.INFO,                         // LOG_LEVEL
    @Doc("Postgres connection URL") @Schemes("postgres") val databaseUrl: Secret,              // DATABASE_URL
    @Doc("Upstream request timeout") @DurationMax("1m") val requestTimeout: Duration = Duration.ofSeconds(10), // REQUEST_TIMEOUT
)

enum class LogLevel { @WireName("debug") DEBUG, @WireName("info") INFO, @WireName("warn") WARN }
