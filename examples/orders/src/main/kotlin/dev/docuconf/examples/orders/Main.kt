package dev.docuconf.examples.orders

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.docuconf.hoplite.Docuconf
import dev.docuconf.kotlin.core.ConfigViolationException
import dev.docuconf.kotlin.core.Durations
import dev.docuconf.kotlin.core.JsonValue
import java.net.InetSocketAddress
import kotlin.system.exitProcess

fun main() {
    // Checks every variable against the declaration and reports all problems at once, before
    // Hoplite binds OrdersConfig. Secret values never appear in the report.
    val config = try {
        Docuconf.load<OrdersConfig>()
    } catch (e: ConfigViolationException) {
        System.err.println(e.message)
        exitProcess(1)
    }

    val server = HttpServer.create(InetSocketAddress(config.port), 0)
    server.createContext("/healthz") { it.respond("text/plain", "ok") }
    server.createContext("/config") { it.respond("application/json", config.redacted()) }
    server.start()
    println("orders listening on :${config.port} (log level ${config.log.level})")
}

/** The loaded configuration as JSON, with the secret replaced by `***`. */
fun OrdersConfig.redacted(): String = JsonValue.of(
    mapOf(
        "PORT" to port,
        "LOG_LEVEL" to log.level.name,
        "DATABASE_URL" to "***",
        "ALLOWED_ORIGINS" to allowed.origins,
        "REQUEST_TIMEOUT" to Durations.formatGo(request.timeout.toNanos()),
        "WORKER_COUNT" to worker.count,
    ),
).toString()

private fun HttpExchange.respond(contentType: String, body: String) {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
