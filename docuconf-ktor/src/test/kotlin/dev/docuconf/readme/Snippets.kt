package dev.docuconf.readme

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.Secret
import com.sksamuel.hoplite.addResourceSource
import dev.docuconf.hoplite.ConfigOverlay
import dev.docuconf.hoplite.Docuconf
import dev.docuconf.hoplite.checkContract
import dev.docuconf.hoplite.Doc
import dev.docuconf.hoplite.DocuconfService
import dev.docuconf.hoplite.Env
import dev.docuconf.hoplite.KeyLength
import dev.docuconf.hoplite.KeySet
import dev.docuconf.hoplite.Keys
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
import java.security.MessageDigest
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

data class WebhookConfig(
    @Doc("Keys that verify webhook signatures") @KeyLength(min = 32, max = 256) val webhookKeys: KeySet,
    @Doc("API keys callers present") @Keys(min = 1, max = 3) val apiKeys: KeySet? = null,
)

fun verified(config: WebhookConfig, body: ByteArray, signature: ByteArray): Boolean =
    config.webhookKeys.verify { key ->
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        MessageDigest.isEqual(mac.doFinal(body), signature)
    }

fun allowed(config: WebhookConfig, presented: String): Boolean = config.apiKeys?.contains(presented) ?: false

fun contractFirstWithFiles(): Any? {
    val contract = ContractFirst.parse(File("contract.json").readText())
    when (val r = Docuconf.checkContract(contract, System.getenv())) {
        is ContractFirst.Result.Success -> r.values.file("routes")   // the config file's data
        is ContractFirst.Result.Failure -> error(r.violations.joinToString("\n"))
    }
    return null
}

fun contractFirst() {
    val contract = ContractFirst.parse(File("contract.json").readText())
    val values = ContractFirst.load(contract, System.getenv())   // throws ConfigViolationException
    val port: Long? = values.long("PORT")
    val timeout: kotlin.time.Duration? = values.duration("TIMEOUT")
    println("$port $timeout")
}
