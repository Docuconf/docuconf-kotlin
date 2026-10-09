package dev.docuconf.hoplite

import com.sksamuel.hoplite.Secret
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.ListItems
import dev.docuconf.kotlin.core.VarType
import org.junit.jupiter.api.io.TempDir
import java.io.OutputStream
import java.io.PrintStream
import java.net.URI
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

    // metadata.generator.version is Docuconf.VERSION, which every release PR bumps, so comparisons with the
    // committed golden file ignore its value, as `--check` does.
    private fun withoutGeneratorVersion(cue: String) = Docuconf.withoutGeneratorVersion(cue)

    @Test
    fun matchesGolden() {
        val cue = export()
        if (System.getenv("UPDATE_GOLDEN") == "1") {
            Files.createDirectories(golden.parent)
            Files.writeString(golden, cue)
        }
        assertEquals(withoutGeneratorVersion(Files.readString(golden)), withoutGeneratorVersion(cue), "contract differs from $golden; rerun with UPDATE_GOLDEN=1 if intended")
    }

    @Test
    fun goldenComparisonIgnoresOnlyTheGeneratorVersion() {
        val cue = export()
        val bumped = cue.replaceFirst("\"${Docuconf.VERSION}\"", "\"99.0.0\"")
        assertTrue(bumped != cue)
        assertEquals(withoutGeneratorVersion(cue), withoutGeneratorVersion(bumped))
        assertTrue(withoutGeneratorVersion(cue.replace("docuconf-hoplite", "other")) != withoutGeneratorVersion(cue))
    }

    @Test
    fun checkIgnoresOnlyTheGeneratorVersion(@TempDir dir: Path) {
        val file = dir.resolve("contract.cue")
        val quiet = PrintStream(OutputStream.nullOutputStream())
        fun check(committed: String, vararg extra: String): Int {
            Files.writeString(file, committed)
            val args = arrayOf("--class", GatewayConfig::class.java.name, "--service", "gateway", "--out", file.toString(), *extra, "--check")
            return export(args, quiet, quiet)
        }
        val fresh = export()
        val version = "version: \"${Docuconf.VERSION}\"}"
        assertContains(fresh, "\t\tgenerator: {language: \"kotlin\", sdk: \"docuconf-hoplite\", $version\n")
        assertEquals(0, check(fresh))

        // A contract committed before a release PR bumped Docuconf.VERSION is still current.
        assertEquals(0, check(fresh.replace(version, "version: \"0.0.1\"}")))
        assertEquals(0, check(fresh.replace(version, "version: \"99.1.0-SNAPSHOT\"}")))

        // Any other difference is still stale, including the rest of the generator and a version elsewhere.
        val bumped = fresh.replace(version, "version: \"0.0.1\"}")
        assertEquals(1, check(bumped.replace("sdk: \"docuconf-hoplite\"", "sdk: \"other\"")))
        assertEquals(1, check(bumped.replace("language: \"kotlin\"", "language: \"java\"")))
        assertEquals(1, check(bumped.replace("name: \"gateway\"", "name: \"other\"")))
        assertEquals(1, check(bumped.replaceFirst("description: \"", "description: \"An ")))
        assertEquals(1, check(bumped + "\n"))
        assertEquals(1, check(bumped, "--app-version", "1.4.0"))
        val withApp = Docuconf.exportCue(GatewayConfig::class, "gateway", "1.4.0") { warn = {} }
        assertEquals(0, check(withApp, "--app-version", "1.4.0"))
        assertEquals(1, check(withApp.replace("appVersion: \"1.4.0\"", "appVersion: \"1.3.0\""), "--app-version", "1.4.0"))
        // Only metadata's generator: the same line anywhere else keeps its version.
        val outside = fresh.replace("\tvars: {\n", "\tvars: {\n\t\tgenerator: {language: \"kotlin\", sdk: \"docuconf-hoplite\", version: \"1\"}\n")
        assertTrue(Docuconf.withoutGeneratorVersion(outside) != Docuconf.withoutGeneratorVersion(outside.replace("version: \"1\"}", "version: \"2\"}")))
    }

    @Test
    fun versionMatchesBuild() {
        assertEquals(System.getProperty("docuconf.version").removeSuffix("-SNAPSHOT"), Docuconf.VERSION)
    }

    @Test
    fun isDeterministic() {
        assertEquals(export(), export())
    }

    @Test
    fun namesFollowHopliteEnvironmentSource() {
        val names = Docuconf.contract(GatewayConfig::class, "gateway") { warn = {} }.vars.map { it.name }.toSet()
        // Nesting is "_"; within one level Hoplite drops "_" and "-" and ignores case.
        assertTrue("PUBLIC_URL" in names, names.toString())
        assertTrue("DB_POOL_SIZE" in names, names.toString())
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
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(Clash::class, "svc") }.message!!, "Clash.httpPort and Clash.http_port all read HTTP_PORT")

        data class Watch(
            @Doc("Licence key") @FileInput(name = "license", path = "/etc/svc/license.key") val license: TextFile,
            @Doc("Another key") @FileInput(name = "other", path = "/etc/svc/other.key") val other: TextFile,
        )
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(Watch::class, "svc") }.message!!, "shares its mount directory")
    }

    @Test
    fun intListItemsExportTheirRange(@TempDir dir: Path) {
        data class Lists(
            @Doc("Shard ids this instance owns") val shards: List<Int> = emptyList(),
            @Doc("Offsets as 64-bit integers") val offsets: List<Long> = emptyList(),
            @Doc("Bounded shard ids") @ItemMin(0) @ItemMax(1023) val bounded: List<Int> = listOf(1),
            @Doc("Wider than Int holds") @ItemMin(-5_000_000_000) val wide: Set<Int> = emptySet(),
            @Doc("Bounded offsets") @ItemMax(10) val small: List<Long> = emptyList(),
        )
        val c = Docuconf.contract(Lists::class, "svc")
        assertEquals(Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong(), c.variable("SHARDS")!!.let { it.itemMin to it.itemMax })
        assertEquals(null to null, c.variable("OFFSETS")!!.let { it.itemMin to it.itemMax }, "Long is 64-bit: no implicit bounds")
        assertEquals(0L to 1023L, c.variable("BOUNDED")!!.let { it.itemMin to it.itemMax })
        assertEquals(Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong(), c.variable("WIDE")!!.let { it.itemMin to it.itemMax }, "narrowed to Int")
        assertEquals(null to 10L, c.variable("SMALL")!!.let { it.itemMin to it.itemMax })

        val cue = Docuconf.exportCue(Lists::class, "svc")
        assertContains(cue, "itemMin: 0\n\t\t\titemMax: 1023")
        val (exit, out) = Cue.run(Cue.module(dir, cue), dir, "vet", "-c", "./svc")
        assertEquals(0, exit, "cue vet -c failed:\n$out")

        data class NotInts(@Doc("Names of things") @ItemMin(0) val names: List<String> = emptyList())
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(NotInts::class, "svc") }.message!!, "@ItemMin")

        data class BadDefault(@Doc("Bounded shard ids") @ItemMax(10) val shards: List<Int> = listOf(11))
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(BadDefault::class, "svc") }.message!!, "itemMax")
    }

    @Test
    fun lengthLimitsAreExported(@TempDir dir: Path) {
        data class Limits(val max: Int)
        data class Batch(
            @Doc("Where to report each run") @Schemes("https") @Length(max = 24) val callback: URI? = null,
            @Doc("Run limits as a JSON object") @Length(max = 16) val limits: Json<Limits>? = null,
            @Doc("Branch codes, two to four characters each") @ItemLength(min = 2, max = 4) val branches: List<String> = listOf("ZÜ01", "BE"),
        )
        val c = Docuconf.contract(Batch::class, "svc")
        assertEquals(24, c.variable("CALLBACK")!!.maxLength)
        assertEquals(16, c.variable("LIMITS")!!.maxLength)
        assertEquals(2 to 4, c.variable("BRANCHES")!!.let { it.itemMinLength to it.itemMaxLength })
        val cue = Docuconf.exportCue(Batch::class, "svc")
        assertContains(cue, "itemMinLength: 2")
        val (exit, out) = Cue.run(Cue.module(dir, cue), dir, "vet", "-c", "./svc")
        assertEquals(0, exit, "cue vet -c failed:\n$out")

        data class IntItems(@Doc("Worker ports") @ItemLength(max = 4) val ports: List<Int> = emptyList())
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(IntItems::class, "svc") }.message!!, "@ItemLength only applies to List<String>")
        data class MinOnUrl(@Doc("Callback URL") @Length(min = 1) val hook: URI? = null)
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(MinOnUrl::class, "svc") }.message!!, "minLength only applies to strings")
        data class BadDefault(@Doc("Branch codes") @ItemLength(max = 4) val codes: List<String> = listOf("BE", "ZÜRICH"))
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(BadDefault::class, "svc") }.message!!, "above itemMaxLength 4")
    }

    @Test
    fun aListOfSecretsIsASecretList(@TempDir dir: Path) {
        data class Hooks(
            @Doc("Keys that verify webhook signatures") @Items(min = 1, max = 2) @ItemLength(min = 32, max = 256)
            val webhookKeys: List<Secret>? = null,
        )
        val v = Docuconf.contract(Hooks::class, "svc").variable("WEBHOOK_KEYS")!!
        assertTrue(v.secret)
        assertEquals(VarType.LIST, v.type)
        assertEquals(ListItems.STRING, v.items)
        assertEquals(32 to 256, v.itemMinLength to v.itemMaxLength)
        val (exit, out) = Cue.run(Cue.module(dir, Docuconf.exportCue(Hooks::class, "svc")), dir, "vet", "-c", "./svc")
        assertEquals(0, exit, "cue vet -c failed:\n$out")
        data class WithDefault(@Doc("Keys that verify webhook signatures") val keys: List<Secret> = listOf(Secret("k")))
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(WithDefault::class, "svc") }.message!!, "a secret cannot have a default")
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
        assertEquals("20", c.variable("DB_POOL_SIZE")!!.default.toString())
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
        assertEquals(0, export(arrayOf("--class", GatewayConfig::class.java.name, "--service", "gateway", "--out", out.toString(), "--markdown", md.toString()), System.out, System.err))
        assertEquals(withoutGeneratorVersion(Files.readString(golden)), withoutGeneratorVersion(Files.readString(out)))
        assertContains(Files.readString(md), "# gateway configuration")
    }
}

data class Enable(@Doc("Turns on the new checkout") val checkout: Boolean = false)
