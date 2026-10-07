package dev.docuconf.readme

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.Secret
import com.sksamuel.hoplite.addResourceSource
import dev.docuconf.hoplite.ConfigOverlay
import dev.docuconf.hoplite.Doc
import dev.docuconf.hoplite.DocuconfService
import dev.docuconf.hoplite.Env
import dev.docuconf.hoplite.Max
import dev.docuconf.hoplite.Min
import dev.docuconf.hoplite.Schemes
import dev.docuconf.hoplite.withDocuconf
import dev.docuconf.kotlin.core.ContractFirst
import dev.docuconf.ktor.docuconfConfig
import dev.docuconf.ktor.docuconfServer
import io.ktor.server.application.Application
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import java.time.Duration

// Code shown in README.md. ReadmeTest checks every README code block appears verbatim in a file
// that CI compiles, runs or diffs; these snippets are compiled here and run by ReadmeTest.

@DocuconfService(name = "app", baseSources = ["/application.yaml"])
data class AppConfig(
    @Doc("HTTP listen port") @Min(1) @Max(65535) val port: Int = 8080,
    @Doc("Minimum log level") val logLevel: String = "info",
    @Doc("Postgres connection URL") @Schemes("postgres") val databaseUrl: Secret,
)

fun loadWithYourLoader(): AppConfig {
    val config = ConfigLoaderBuilder.default()
        .addResourceSource("/application.yaml")
        .withDocuconf()
        .build()
        .loadConfigOrThrow<AppConfig>()
    return config
}

fun ktorMain() {
    docuconfServer(Netty, port = AppConfig::port) { config ->
        routing { get("/") { call.respondText("log level ${config.logLevel}") } }
    }.start(wait = true)
}

fun Application.module() {
    val config = docuconfConfig<AppConfig>()
    routing { get("/") { call.respondText("log level ${config.logLevel}") } }
}

data class Database(
    @Doc("Postgres connection URL") @Env("DATABASE_URL") @Schemes("postgres") val url: Secret,    // DATABASE_URL
    @Doc("Connection pool size") @Min(1) @Max(50) val poolSize: Int = 10,                       // DB_POOL_SIZE
)

@DocuconfService(name = "gateway")
@ConfigOverlay(name = "platform", path = "/app/config/gateway.yaml", description = "Settings the platform supplies per environment")
data class GatewayConfig(
    @Doc("HTTP listen port") @Min(1) @Max(65535) val port: Int = 8080,
    @Doc("Upstream request timeout") val timeout: Duration = Duration.ofSeconds(30),
    val db: Database,
)

fun contractFirst() {
    val contract = ContractFirst.parse(File("contract.json").readText())
    val values = ContractFirst.load(contract, System.getenv())   // throws ConfigViolationException
    val port: Long? = values.long("PORT")
    val timeout: kotlin.time.Duration? = values.duration("TIMEOUT")
    println("$port $timeout")
}
