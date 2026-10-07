package dev.docuconf.examples.orders

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.docuconf.hoplite.Docuconf
import dev.docuconf.kotlin.core.Durations
import dev.docuconf.kotlin.core.JsonValue
import java.net.InetSocketAddress

fun main() {
    // Checks every variable against the declaration. On a bad environment it prints every problem
    // at once (never a secret value), writes them to the termination log and exits with status 1.
    val config = Docuconf.loadOrExit<OrdersConfig>()

    val server = HttpServer.create(InetSocketAddress(config.port), 0)
    server.createContext("/healthz") { it.respond("text/plain", "ok") }
    server.createContext("/config") { it.respond("application/json", config.redacted()) }
    server.start()
    println("orders listening on :${config.port} (log level ${config.logLevel})")
}

/** The loaded configuration as JSON, with the secret replaced by `***`. */
fun OrdersConfig.redacted(): String = JsonValue.of(
    mapOf(
        "PORT" to port,
        "LOG_LEVEL" to logLevel.name.lowercase(),
        "DATABASE_URL" to "***",
        "ALLOWED_ORIGINS" to allowedOrigins,
        "REQUEST_TIMEOUT" to Durations.formatGo(requestTimeout.toNanos()),
        "WORKER_COUNT" to workerCount,
    ),
).toString()

private fun HttpExchange.respond(contentType: String, body: String) {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
