package dev.docuconf.readme

import com.sksamuel.hoplite.addResourceSource
import dev.docuconf.hoplite.Docuconf
import dev.docuconf.hoplite.withDocuconf
import dev.docuconf.ktor.provideDocuconfConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadmeTest {
    private val root = File(System.getProperty("docuconf.rootDir"))

    @Test
    fun keySets() {
        val newKey = "n".repeat(32)
        val config = Docuconf.load<WebhookConfig> { env = mapOf("WEBHOOK_KEYS" to "${"o".repeat(32)},$newKey", "API_KEYS" to "a,b") }
        val body = "{}".toByteArray()
        val signature = javax.crypto.Mac.getInstance("HmacSHA256")
            .apply { init(javax.crypto.spec.SecretKeySpec(newKey.toByteArray(), "HmacSHA256")) }
            .doFinal(body)
        assertTrue(verified(config, body, signature))
        assertTrue(allowed(config, "b"))
        assertTrue(!allowed(config, "c"))
    }

    @Test
    fun greets() = testApplication {
        application {
            provideDocuconfConfig(Docuconf.load<AppConfig> { env = mapOf("DATABASE_URL" to "postgres://u:pw@db/app") })
            module()
        }
        assertEquals(HttpStatusCode.OK, client.get("/").status)
        assertEquals("log level info", client.get("/").bodyAsText())
    }

    @Test
    fun yourLoaderReadsTheBaseFileAndTheEnvironment() {
        val config = com.sksamuel.hoplite.ConfigLoaderBuilder.default()
            .addResourceSource("/application.yaml")
            .withDocuconf { env = mapOf("DATABASE_URL" to "postgres://u:pw@db/app") }
            .build()
            .loadConfigOrThrow<AppConfig>()
        assertEquals(9090, config.port)
        assertEquals(listOf("DATABASE_URL", "DB_POOL_SIZE", "PORT", "TIMEOUT"), Docuconf.contract(GatewayConfig::class).vars.map { it.name }.sorted())
    }

    /** Every code block in README.md appears verbatim (up to indentation) in a file CI compiles, runs or diffs. */
    @Test
    fun everyReadmeBlockIsChecked() {
        val checked = listOf(
            "examples/consumer", "examples/orders", "docuconf-ktor/src/test", "docuconf-hoplite/src/test",
            "docuconf-gradle-plugin/src", ".github/workflows/ci.yml",
        ).flatMap { p -> File(root, p).walkTopDown().filter { it.isFile && "/build/" !in it.path && "/.gradle/" !in it.path }.toList() }
            .map { it to normal(it.readText().lines()) }
        val missing = ArrayList<String>()
        for (doc in listOf("README.md", "docs/ADVANCED.md")) {
            for (block in blocks(File(root, doc).readText())) {
                val want = normal(block.lines().map { it.replace("../docuconf-kotlin", "../..") })
                if (want.isEmpty()) continue
                if (checked.none { (_, have) -> contains(have, want) }) missing += "$doc: block starting \"${want.first()}\""
            }
        }
        assertTrue(missing.isEmpty(), "README blocks not found in any checked file:\n" + missing.joinToString("\n"))
    }

    private fun blocks(md: String): List<String> = Regex("(?m)^```[a-z]*\\n(.*?)^```", RegexOption.DOT_MATCHES_ALL).findAll(md).map { it.groupValues[1] }.toList()

    private fun normal(lines: List<String>): List<String> = lines.map { it.trim() }.filter { it.isNotEmpty() }

    private fun contains(have: List<String>, want: List<String>): Boolean =
        (0..have.size - want.size).any { i -> want.indices.all { have[i + it] == want[it] } }
}
