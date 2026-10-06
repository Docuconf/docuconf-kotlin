package dev.docuconf.hoplite

import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.ConfigViolationException
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.Reload
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class OverlayTest {
    private val overlay = "/app/config/gateway.yaml"

    private fun World.load(configure: DocuconfOptions.() -> Unit = {}): GatewayConfig =
        Docuconf.load(GatewayConfig::class, DocuconfOptions().apply(options).apply(configure))

    private fun World.failure(): ConfigViolationException = assertFailsWith { load() }

    @Test
    fun exportsTheOverlayAndAConfigKeyForEveryVariable() {
        val c = Docuconf.contract(GatewayConfig::class, "gateway") { warn = {} }
        val o = c.overlay("platform")!!
        assertEquals(overlay, o.path)
        assertEquals(".", o.keySeparator)
        assertEquals("yaml", o.format.wire)
        assertEquals(Reload.RESTART, o.reload)
        assertEquals("db.poolSize", c.variable("DB_POOLSIZE")!!.configKey)
        assertEquals("port", c.variable("PORT")!!.configKey)
        assertTrue(c.vars.all { it.configKey != null })
        assertContains(Docuconf.exportMarkdown(GatewayConfig::class, "gateway") { warn = {} }, "| `platform` | yaml | `/app/config/gateway.yaml` |")
    }

    @Test
    fun aMissingOverlayIsFine(@TempDir root: Path) {
        val w = World(root)
        assertFalse(Files.exists(w.path(overlay)))
        assertEquals(8080, w.load().port)
    }

    @Test
    fun precedenceIsBaseThenOverlayThenEnvironment(@TempDir root: Path) {
        val w = World(root)
        val base = w.write("/app/application.yaml", "port: 9000\ntraceRatio: 0.5\ndb:\n  poolSize: 20\n")
        w.write(overlay, "port: 9100\ndb:\n  poolSize: 30\n")
        val withBase: DocuconfOptions.() -> Unit = { baseSources = listOf(base.toString()) }

        // The overlay beats the base file; the base file still supplies what the overlay leaves out.
        val fromOverlay = w.load(withBase)
        assertEquals(9100, fromOverlay.port)
        assertEquals(30, fromOverlay.db.poolSize)
        assertEquals(0.5, fromOverlay.traceRatio)

        // The environment beats the overlay.
        w.env["PORT"] = "9200"
        val fromEnv = w.load(withBase)
        assertEquals(9200, fromEnv.port)
        assertEquals(30, fromEnv.db.poolSize)
        assertTrue(w.warnings.any { "PORT is set in the environment and in overlay platform" in it }, w.warnings.toString())

        // An empty env value counts as unset, so the overlay applies.
        w.env["PORT"] = ""
        assertEquals(9100, w.load(withBase).port)
    }

    @Test
    fun overlayValuesBindInNativeTypes(@TempDir root: Path) {
        val w = World(root)
        w.write(
            overlay,
            """
            logLevel: debug
            compress: false
            timeout: PT1M30S
            idle: PT2M
            brokers: [a:9092, b:9092]
            adminPorts: [9100, 9101]
            rateLimits:
              perMinute: 600
            """.trimIndent(),
        )
        val cfg = w.load()
        assertEquals(LogLevel.debug, cfg.logLevel)
        assertFalse(cfg.compress)
        assertEquals(Duration.ofSeconds(90), cfg.timeout)
        assertEquals(2.minutes, cfg.idle)
        assertEquals(listOf("a:9092", "b:9092"), cfg.brokers)
        assertEquals(listOf(9100, 9101), cfg.adminPorts)
        assertEquals(600, cfg.rateLimits!!.value.perMinute)
    }

    @Test
    fun aRequiredVariableIsSatisfiedByTheOverlay(@TempDir root: Path) {
        val w = World(root)
        w.env.remove("POD_NAMESPACE")
        w.write(overlay, "pod:\n  namespace: staging\n")
        assertEquals("staging", w.load().pod.namespace)
    }

    @Test
    fun overlayValuesAreValidatedLikeEnvironmentValues(@TempDir root: Path) {
        val w = World(root)
        w.write(
            overlay,
            """
            port: 70000
            logLevel: verbose
            brokers: []
            adminPorts: [1, x]
            timeout: PT10M
            publicUrl:
              nested: true
            rateLimits: {perMinute: 0}
            """.trimIndent(),
        )
        val e = w.failure()
        assertEquals(
            setOf(
                "PORT:out_of_range",
                "LOGLEVEL:not_in_enum",
                "BROKERS:too_few_items",
                "ADMINPORTS:invalid_type",
                "TIMEOUT:out_of_range",
                "RATELIMITS:schema_mismatch",
            ),
            e.violations.map { "${it.input}:${it.code}" }.toSet(),
        )
        // PUBLICURL is set in the environment, which wins, so the overlay's bad value is not used or reported.
        val port = e.violations.single { it.input == "PORT" }
        assertEquals("\"70000\" is above max 65535 (from overlay platform, key port)", port.message)
        assertContains(Files.readString(w.terminationLog), "PORT: out_of_range")
    }

    @Test
    fun aMapWhereAScalarBelongsIsInvalid(@TempDir root: Path) {
        val w = World(root)
        w.env.remove("PUBLICURL")
        w.write(overlay, "publicUrl:\n  host: gw.example.com\n")
        val v = w.failure().violations.single()
        assertEquals(Codes.INVALID_TYPE, v.code)
        assertEquals("PUBLICURL", v.input)
        assertContains(v.message, "is a map, not a url")
    }

    @Test
    fun aSecretInAnOverlayIsRejectedWithoutPrintingIt(@TempDir root: Path) {
        val w = World(root)
        w.env.remove("DB_URL")
        w.write(overlay, "db:\n  url: postgres://app:hunter2@db/gw\n")
        val e = w.failure()
        val v = e.violations.single()
        assertEquals("DB_URL" to Codes.INVALID_TYPE, v.input to v.code)
        assertContains(v.message, "secret must come from the environment")
        assertFalse("hunter2" in e.message!!)
        assertFalse("hunter2" in Files.readString(w.terminationLog))
    }

    @Test
    fun aMalformedOverlayIsReported(@TempDir root: Path) {
        val w = World(root)
        w.write(overlay, "port: [unclosed\n")
        val v = w.failure().violations.single()
        assertEquals("platform" to Codes.FILE_MALFORMED, v.input to v.code)
    }

    @Test
    fun anEmptyOverlayIsFine(@TempDir root: Path) {
        val w = World(root)
        w.write(overlay, "")
        assertEquals(8080, w.load().port)
    }

    @Test
    fun watchIsRejectedAtDeclaration() {
        @ConfigOverlay(name = "platform", path = "/app/config/svc.yaml", reload = Reload.WATCH)
        data class Watched(@Doc("Listen port") val port: Int = 8080)
        val e = assertFailsWith<DeclarationException> { Docuconf.contract(Watched::class, "svc") }
        assertContains(e.message!!, "reload = WATCH is not supported")
        assertFailsWith<DeclarationException> { Docuconf.check(Watched::class, DocuconfOptions().apply { env = emptyMap() }) }
    }

    @Test
    fun rejectsBadOverlayDeclarations() {
        @ConfigOverlay(name = "platform", path = "/app/config/svc.conf")
        data class UnknownFormat(@Doc("Listen port") val port: Int = 8080)
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(UnknownFormat::class, "svc") }.message!!, "cannot tell the format")

        @ConfigOverlay(name = "platform", path = "/app/svc.yaml")
        data class Reserved(@Doc("Listen port") val port: Int = 8080)
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(Reserved::class, "svc") }.message!!, "would hide a directory")

        @ConfigOverlay(name = "Platform", path = "/app/config/svc.yaml")
        data class BadName(@Doc("Listen port") val port: Int = 8080)
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(BadName::class, "svc") }.message!!, "DNS labels")

        @ConfigOverlay(name = "platform", path = "/etc/svc/svc.yaml")
        data class SharedMount(
            @Doc("Licence key") @FileInput(name = "license", path = "/etc/svc/license.key") val license: TextFile,
        )
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(SharedMount::class, "svc") }.message!!, "shares its mount directory")
    }

    @Test
    fun severalOverlaysAreRepeatable() {
        @ConfigOverlay(name = "platform", path = "/app/config/svc.yaml")
        @ConfigOverlay(name = "team", path = "/app/team/svc.json")
        data class Two(@Doc("Listen port") val port: Int = 8080)
        val c = Docuconf.contract(Two::class, "svc")
        assertEquals(listOf("platform", "team"), c.overlays.map { it.name })
        assertEquals("json", c.overlay("team")!!.format.wire)
    }

    @Test
    fun anOverlayBesideTheBaseConfigFileIsRejected(@TempDir root: Path) {
        val w = World(root)
        // The app ships /app/config/application.yaml, so mounting /app/config would hide it.
        val base = w.write("/app/config/application.yaml", "port: 9000\n")
        val e = assertFailsWith<DeclarationException> { w.load { baseSources = listOf(base.toString()) } }
        assertContains(e.message!!, "overlay platform: /app/config/gateway.yaml is in a directory holding the base config file")
    }

    @Test
    fun theAppsOwnDirectoryCountsAsShipped(@TempDir root: Path) {
        val dirs = Overlays.shippedDirs(GatewayConfig::class, emptyList(), javaClass.classLoader)
        val code = Path.of(GatewayConfig::class.java.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize()
        assertTrue(code in dirs.keys, dirs.toString())
        @ConfigOverlay(name = "platform", path = "/config/svc.yaml")
        data class Svc(@Doc("Listen port") val port: Int = 8080)
        val spec = Docuconf.contract(Svc::class, "svc").overlays.single()
        val e = assertFailsWith<DeclarationException> { Overlays.checkDirs(listOf(spec), { code.resolve("svc.yaml") }, dirs) }
        assertContains(e.message!!, "the app's code")
    }

    // End to end: the platform renders the overlay from the exported contract with the CUE
    // meta-schema (#Render), and the app loads the rendered file.
    @Test
    fun anOverlayRenderedByThePlatformBindsInTheApp(@TempDir dir: Path, @TempDir root: Path) {
        val cue = Cue.module(dir, Docuconf.exportCue(GatewayConfig::class, "gateway") { warn = {} })
        dir.resolve("platform").createDirectories()
        Files.writeString(
            dir.resolve("platform/render.cue"),
            """
            package platform

            import (
            	"docuconf.dev/contract"
            	app "docuconf.dev/svc:gateway"
            )

            rendered: contract.#Render & {
            	contract: app
            	overlays: platform: {
            		PORT:          9100
            		LOGLEVEL:      "debug"
            		TRACERATIO:    0.25
            		COMPRESS:      false
            		TIMEOUT:       "1m30s"
            		IDLE:          "2m"
            		BROKERS:       ["a:9092", "b:9092"]
            		ADMINPORTS:    [9100]
            		DB_POOLSIZE:   30
            		POD_NAMESPACE: "staging"
            	}
            }
            file: rendered.configMaps[0].data["gateway.yaml"]
            mountPath: rendered.volumeMounts[0].mountPath

            """.trimIndent(),
        )
        val (exit, rendered) = Cue.run(cue, dir, "export", "./platform", "-e", "file", "--out", "text")
        assertEquals(0, exit, rendered)
        val (_, mount) = Cue.run(cue, dir, "export", "./platform", "-e", "mountPath", "--out", "text")
        assertEquals("/app/config", mount.trim())

        val w = World(root)
        w.env.remove("POD_NAMESPACE")
        w.write(overlay, rendered)
        val cfg = w.load()
        assertEquals(9100, cfg.port)
        assertEquals(LogLevel.debug, cfg.logLevel)
        assertEquals(0.25, cfg.traceRatio)
        assertFalse(cfg.compress)
        assertEquals(Duration.ofSeconds(90), cfg.timeout)
        assertEquals(2.minutes, cfg.idle)
        assertEquals(listOf("a:9092", "b:9092"), cfg.brokers)
        assertEquals(listOf(9100), cfg.adminPorts)
        assertEquals(30, cfg.db.poolSize)
        assertEquals("staging", cfg.pod.namespace)
        assertIs<LoadResult.Success<*>>(w.check())
    }
}
