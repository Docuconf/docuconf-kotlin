package dev.docuconf.ktor

import com.sksamuel.hoplite.Secret
import dev.docuconf.hoplite.Doc
import dev.docuconf.hoplite.Docuconf
import dev.docuconf.hoplite.DocuconfService
import dev.docuconf.hoplite.Max
import dev.docuconf.hoplite.Schemes
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@DocuconfService(name = "web")
data class WebConfig(
    @Doc("HTTP listen port") @Max(65535) val port: Int = 8080,
    @Doc("Greeting to answer with") val greeting: String = "hello",
    @Doc("Postgres connection URL") @Schemes("postgres") val databaseUrl: Secret,
)

fun Application.web(config: WebConfig = docuconfConfig()) {
    routing { get("/") { call.respondText(config.greeting) } }
}

class KtorTest {
    private val env = mapOf("DATABASE_URL" to "postgres://u:pw@db/web", "GREETING" to "hi")

    @Test
    fun testApplicationWithAMapEnvironment() = testApplication {
        application { docuconfConfig<WebConfig> { env = this@KtorTest.env }; web() }
        assertEquals("hi", client.get("/").bodyAsText())
    }

    @Test
    fun testApplicationWithALoadedConfig() = testApplication {
        val config = Docuconf.load<WebConfig> { env = this@KtorTest.env + ("GREETING" to "hey") }
        application {
            provideDocuconfConfig(config)
            assertSame(config, docuconfConfig<WebConfig>())
            web()
        }
        assertEquals("hey", client.get("/").bodyAsText())
    }

    @Test
    fun serverBindsThePortFromTheConfig() {
        val config = Docuconf.load<WebConfig> { env = this@KtorTest.env + ("PORT" to "0") }
        val server = docuconfServer(Netty, config, config.port, host = "127.0.0.1") { web(it) }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            val body = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).build(), HttpResponse.BodyHandlers.ofString()).body()
            assertEquals("hi", body)
        } finally {
            server.stop(100, 1000)
        }
    }
}
