package dev.docuconf.hoplite

import com.sksamuel.hoplite.Secret
import dev.docuconf.kotlin.core.DeclarationException
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExportTest {
    private val projectDir = Path.of(System.getProperty("docuconf.projectDir") ?: ".")
    private val golden = projectDir.resolve("src/test/resources/golden/gateway.cue")

    private fun export() = Docuconf.exportCue(GatewayConfig::class, "gateway", configure = { warn = {} })

    @Test
    fun matchesGolden() {
        val cue = export()
        if (System.getenv("UPDATE_GOLDEN") == "1") {
            Files.createDirectories(golden.parent)
            Files.writeString(golden, cue)
        }
        assertEquals(Files.readString(golden), cue, "contract differs from $golden; rerun with UPDATE_GOLDEN=1 if intended")
    }

    @Test
    fun versionMatchesBuild() {
        assertEquals(System.getProperty("docuconf.version"), Docuconf.VERSION)
    }

    @Test
    fun isDeterministic() {
        assertEquals(export(), export())
    }

    @Test
    fun namesFollowHopliteEnvironmentSource() {
        val names = Docuconf.contract(GatewayConfig::class, "gateway") { warn = {} }.vars.map { it.name }.toSet()
        // Nesting is "_"; within one level Hoplite drops "_" and "-" and ignores case.
        assertTrue("PUBLICURL" in names, names.toString())
        assertTrue("DB_POOLSIZE" in names, names.toString())
        assertTrue("POD_NAMESPACE" in names, names.toString())
        assertTrue("PARTNER_PASSWORD" in names, names.toString())
        assertTrue("VAULTTOKEN" !in names, "@NotInContract parameters stay out")
    }

    @Test
    fun prefixIsPrepended() {
        val names = Docuconf.contract(GatewayConfig::class, "gateway") { prefix = "GW_"; warn = {} }.vars.map { it.name }
        assertTrue(names.all { it.startsWith("GW_") }, names.toString())
    }

    @Test
    fun passesCueVet(@TempDir dir: Path) {
        val cue = Cue.module(dir, export())
        val (exit, out) = Cue.run(cue, dir, "vet", "-c", "./svc")
        assertEquals(0, exit, "cue vet -c failed:\n$out")
    }

    @Test
    fun rejectsBadDeclarations() {
        data class NoDoc(val port: Int = 1)
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(NoDoc::class, "svc") }.message!!, "@Doc")

        data class BadDefault(@Doc("Listen port") @Min(1) val port: Int = 0)
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(BadDefault::class, "svc") }.message!!, "default")

        data class Lookahead(@Doc("Some code") @Pattern("^(?!x)") val code: String = "a")
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(Lookahead::class, "svc") }.message!!, "lookahead")

        data class SecretDefault(@Doc("API token") val token: Secret = Secret("abc"))
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(SecretDefault::class, "svc") }.message!!, "secret cannot have a default")

        data class ShortDoc(@Doc("Port") val port: Int = 1)
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(ShortDoc::class, "svc") }.message!!, "at least 5")

        data class Clash(@Doc("Listen port") val httpPort: Int = 1, @Doc("Same name") val http_port: Int = 2)
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(Clash::class, "svc") }.message!!, "more than once")

        data class Watch(
            @Doc("Licence key") @FileInput(name = "license", path = "/etc/svc/license.key") val license: TextFile,
            @Doc("Another key") @FileInput(name = "other", path = "/etc/svc/other.key") val other: TextFile,
        )
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(Watch::class, "svc") }.message!!, "shares its mount directory")
    }

    @Test
    fun warnsAboutFeatureFlags() {
        data class Flags(val enable: Enable = Enable())
        val warnings = ArrayList<String>()
        Docuconf.contract(Flags::class, "svc") { warn = { warnings += it } }
        assertTrue(warnings.any { "ENABLE_CHECKOUT" in it && "feature flag" in it }, warnings.toString())
    }

    @Test
    fun baseFileValuesBecomeDefaults(@TempDir dir: Path) {
        val base = dir.resolve("application.yaml")
        Files.writeString(base, "port: 9000\ndb:\n  poolSize: 20\n")
        val c = Docuconf.contract(GatewayConfig::class, "gateway") { baseSources = listOf(base.toString()); warn = {} }
        assertEquals("9000", c.variable("PORT")!!.default.toString())
        assertEquals("20", c.variable("DB_POOLSIZE")!!.default.toString())
    }

    @Test
    fun secretInBaseFileIsRejected(@TempDir dir: Path) {
        val base = dir.resolve("application.yaml")
        Files.writeString(base, "db:\n  url: postgres://u:p@db/x\n")
        val e = assertFailsWith<DeclarationException> { Docuconf.contract(GatewayConfig::class, "gateway") { baseSources = listOf(base.toString()) } }
        assertContains(e.message!!, "DB_URL")
        assertTrue("u:p@db" !in e.message!!)
    }

    @Test
    fun markdownListsEveryInput() {
        val md = Docuconf.exportMarkdown(GatewayConfig::class, "gateway") { warn = {} }
        assertContains(md, "`DB_URL`")
        assertContains(md, "`serving-tls`")
    }

    @Test
    fun commandLineExport(@TempDir dir: Path) {
        val out = dir.resolve("contract.cue")
        val md = dir.resolve("CONFIG.md")
        main(arrayOf("--class", GatewayConfig::class.java.name, "--service", "gateway", "--out", out.toString(), "--markdown", md.toString()))
        assertEquals(Files.readString(golden), Files.readString(out))
        assertContains(Files.readString(md), "# gateway configuration")
    }
}

data class Enable(@Doc("Turns on the new checkout") val checkout: Boolean = false)
