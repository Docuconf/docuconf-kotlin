package dev.docuconf.hoplite

import com.sksamuel.hoplite.Secret
import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.ContractFirst
import dev.docuconf.kotlin.core.JsonValue
import dev.docuconf.kotlin.core.KeystoreFormat
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.Reload
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

data class WatchedSettings(val name: String, @Min(1) val replicas: Long)

data class WatchedConfig(
    @Doc("Application settings")
    @FileInput(name = "settings", path = "/etc/app/settings/settings.json")
    val settings: Watched<ConfigFile<WatchedSettings>>,
    @Doc("Certificate served on HTTPS")
    @FileInput(name = "serving-tls", path = "/etc/app/tls")
    @Tls(dnsNames = ["app.example.test"])
    val tls: Watched<TlsKeyPair>,
    @Doc("Licence key")
    @FileInput(name = "licence", path = "/etc/app/licence/licence.key", secret = true)
    @Pattern("^[A-Z0-9-]+$")
    val licence: Watched<TextFile>,
    @Doc("Partner settings, read once")
    @FileInput(name = "partner", path = "/etc/app/partner/partner.json", secret = true)
    val partner: Watched<ConfigFile<WatchedSettings>>? = null,
    @Doc("Read once at boot")
    @FileInput(name = "rules", path = "/etc/app/rules/rules.json")
    val rules: ConfigFile<WatchedSettings>? = null,
)

/**
 * A volume as the kubelet writes a Secret or ConfigMap: each file is a symlink to `..data/<file>`,
 * and `..data` a symlink to a timestamped directory, swapped atomically on every update.
 */
class Projected(val dir: Path) {
    private var generation = 0

    fun update(files: Map<String, String>) {
        val target = dir.resolve("..2026_10_09_${generation++}")
        Files.createDirectories(target)
        files.forEach { (name, content) -> Files.writeString(target.resolve(name), content) }
        val tmp = dir.resolve("..data_tmp")
        Files.deleteIfExists(tmp)
        Files.createSymbolicLink(tmp, target.fileName)
        Files.move(tmp, dir.resolve("..data"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        for (name in files.keys) {
            val link = dir.resolve(name)
            if (!Files.isSymbolicLink(link)) Files.createSymbolicLink(link, Path.of("..data", name))
        }
    }
}

data class WatchedKeystoreConfig(
    @Doc("Password for the client keystore") @Env("CLIENT_KS_PASSWORD") val password: Secret? = null,
    @Doc("Client certificate for mTLS")
    @FileInput(name = "client-keystore", path = "/etc/app/ks/client.p12")
    @KeystoreSpec(format = KeystoreFormat.PKCS12, passwordVar = "CLIENT_KS_PASSWORD")
    val keystore: Watched<Keystore>,
)

class WatchTest {
    private class Setup(root: Path) {
        val settings = Projected(root.resolve("etc/app/settings"))
        val tls = Projected(root.resolve("etc/app/tls"))
        val licence = Projected(root.resolve("etc/app/licence"))
        val first: Certs.Issued = Certs.issue(Certs.ec(), listOf("app.example.test"))
        val warnings = ArrayList<String>()
        val env = mapOf("DOCUCONF_FILE_ROOT" to root.toString())

        init {
            settings.update(mapOf("settings.json" to """{"name": "one", "replicas": 1}"""))
            tls(first)
            licence.update(mapOf("licence.key" to "ABCD-1234"))
        }

        fun tls(issued: Certs.Issued, key: Certs.Issued = issued) =
            tls.update(mapOf("tls.crt" to Certs.pem(issued.cert), "tls.key" to Certs.pkcs8(key.keys)))

        fun load(interval: Duration = Duration.ZERO, clock: Clock = Clock.systemUTC()): WatchedConfig = Docuconf.load {
            env = this@Setup.env
            warn = { synchronized(warnings) { warnings += it } }
            reloadInterval = interval
            this.clock = clock
        }
    }

    @Test
    fun aSymlinkSwapIsReloaded(@TempDir root: Path) {
        val s = Setup(root)
        val config = s.load()
        assertEquals("one", config.settings.current().value.name)
        assertEquals(s.first.cert, config.tls.current().certificate)

        s.settings.update(mapOf("settings.json" to """{"name": "two", "replicas": 2}"""))
        val renewed = Certs.issue(Certs.ec(), listOf("app.example.test"))
        s.tls(renewed)
        assertEquals("two", config.settings.current().value.name)
        val replicas = config.settings.current().value.replicas
        assertEquals(2, replicas)
        assertEquals(renewed.cert, config.tls.current().certificate)
        assertEquals(renewed.keys.private, config.tls.current().privateKey)
        assertEquals(emptyList(), s.warnings)
    }

    @Test
    fun aFileRewrittenInPlaceIsReloaded(@TempDir root: Path) {
        val s = Setup(root)
        val config = s.load()
        assertEquals("ABCD-1234", config.licence.current().text)
        // Not a projected volume: the file itself changes (a new file moved over it).
        val tmp = root.resolve("etc/app/licence/new")
        Files.writeString(tmp, "WXYZ-9876-LONGER")
        Files.move(tmp, root.resolve("etc/app/licence/licence.key"), StandardCopyOption.REPLACE_EXISTING)
        assertEquals("WXYZ-9876-LONGER", config.licence.current().text)
    }

    @Test
    fun checksAtMostOncePerInterval(@TempDir root: Path) {
        val s = Setup(root)
        val config = s.load(Duration.ofHours(1))
        val before = config.settings.current()
        s.settings.update(mapOf("settings.json" to """{"name": "two", "replicas": 2}"""))
        assertSame(before, config.settings.current(), "not checked again within the interval")
        assertTrue(config.settings.refresh())
        assertEquals("two", config.settings.current().value.name)
        assertFalse(config.settings.refresh(), "nothing changed since")
    }

    @Test
    fun aBadChangeKeepsThePreviousValue(@TempDir root: Path) {
        val s = Setup(root)
        val config = s.load()

        s.settings.update(mapOf("settings.json" to """{"name": "two", "replicas": 0}"""))
        assertEquals("one", config.settings.current().value.name)
        assertEquals(1, s.warnings.size, s.warnings.toString())
        assertContains(s.warnings.single(), "file settings changed, but the change was rejected; keeping the previous content: ${Codes.SCHEMA_MISMATCH}")

        // Reported once per change, not at every read.
        config.settings.current()
        assertEquals(1, s.warnings.size)

        // A key that does not match the certificate: the old pair stays.
        s.tls(Certs.issue(Certs.ec(), listOf("app.example.test")), key = Certs.issue(Certs.ec()))
        assertEquals(s.first.cert, config.tls.current().certificate)
        assertContains(s.warnings.last(), "file serving-tls changed, but the change was rejected")
        assertContains(s.warnings.last(), Codes.KEY_MISMATCH)

        // A good change after a bad one is taken.
        s.settings.update(mapOf("settings.json" to """{"name": "three", "replicas": 3}"""))
        assertEquals("three", config.settings.current().value.name)
    }

    @Test
    fun aRemovedFileKeepsThePreviousValue(@TempDir root: Path) {
        val s = Setup(root)
        val config = s.load()
        Files.delete(root.resolve("etc/app/licence/licence.key"))
        assertEquals("ABCD-1234", config.licence.current().text)
        assertEquals(Codes.FILE_MISSING, s.warnings.single().substringAfter("previous content: ").substringBefore(':'))
    }

    @Test
    fun warningsNeverShowSecretContent(@TempDir root: Path) {
        val s = Setup(root)
        val partner = Projected(root.resolve("etc/app/partner"))
        partner.update(mapOf("partner.json" to """{"name": "partner", "replicas": 1}"""))
        val config = s.load()
        val secret = "hunter2-SECRET-value"

        s.licence.update(mapOf("licence.key" to "lower-case $secret"))
        partner.update(mapOf("partner.json" to """{"name": "$secret", "replicas": 0, """))
        assertEquals("ABCD-1234", config.licence.current().text)
        assertEquals("partner", config.partner!!.current().value.name)
        partner.update(mapOf("partner.json" to """{"name": "$secret", "replicas": "$secret"}"""))
        assertEquals("partner", config.partner.current().value.name)

        assertEquals(3, s.warnings.size, s.warnings.toString())
        assertContains(s.warnings[0], Codes.PATTERN_MISMATCH)
        assertContains(s.warnings[1], Codes.FILE_MALFORMED)
        assertContains(s.warnings[2], Codes.SCHEMA_MISMATCH)
        for (w in s.warnings) {
            assertFalse(secret in w, w)
            assertFalse("hunter2" in w, w)
        }
        assertFalse(secret in config.licence.toString())
        assertFalse(secret in config.partner.toString())
    }

    @Test
    fun exportsReloadWatch() {
        val contract = Docuconf.contract(WatchedConfig::class, service = "watched")
        val reload = contract.files.associate { it.name to it.reload }
        assertEquals(
            mapOf("settings" to Reload.WATCH, "serving-tls" to Reload.WATCH, "licence" to Reload.WATCH, "partner" to Reload.WATCH, "rules" to Reload.RESTART),
            reload,
        )
        assertContains(Docuconf.exportCue(WatchedConfig::class, service = "watched"), "reload: \"watch\"")
    }

    @Test
    fun anOptionalWatchedInputAbsentAtBootIsNull(@TempDir root: Path) {
        val config = Setup(root).load()
        assertNull(config.partner)
        assertNull(config.rules)
    }

    data class WatchedVariable(val port: Watched<Int>)

    @Test
    fun watchedAppliesOnlyToFileInputs() {
        val e = assertFailsWith<DeclarationException> { Docuconf.contract(WatchedVariable::class, service = "x") }
        assertContains(e.message!!, "WatchedVariable.port: Watched applies to file inputs")
        assertContains(e.message!!, "environment variables are read once")
    }

    @Test
    fun ofNeverReloads() {
        val w = Watched.of(TextFile.of("x", secret = true))
        assertEquals("x", w.current().text)
        assertFalse(w.refresh())
        assertEquals("Watched(TextFile(/test/file.txt, ****))", w.toString())
    }

    @Test
    fun onChangeHooksSeeAcceptedChangesOnly(@TempDir root: Path) {
        val s = Setup(root)
        val config = s.load()
        val first = ArrayList<String>()
        val second = ArrayList<String>()
        config.settings.onChange { first += it.value.name }
        config.settings.onChange { second += it.value.name }

        s.settings.update(mapOf("settings.json" to """{"name": "two", "replicas": 2}"""))
        assertTrue(config.settings.refresh())
        assertEquals(listOf("two"), first)
        assertEquals(listOf("two"), second)

        // Rejected: no hook runs, the value stays.
        s.settings.update(mapOf("settings.json" to """{"name": "bad", "replicas": 0}"""))
        assertFalse(config.settings.refresh())
        assertEquals(listOf("two"), first)
        assertEquals("two", config.settings.current().value.name)

        // A change found by a plain read also calls the hooks.
        s.settings.update(mapOf("settings.json" to """{"name": "three", "replicas": 3}"""))
        assertEquals("three", config.settings.current().value.name)
        assertEquals(listOf("two", "three"), first)
        assertEquals(listOf("two", "three"), second)
    }

    @Test
    fun aThrowingHookIsReportedAndTheOthersStillRun(@TempDir root: Path) {
        val s = Setup(root)
        val config = s.load()
        val secret = "hunter2-SECRET-value"
        val seen = ArrayList<String>()
        config.licence.onChange { throw IllegalStateException("leaked ${it.text}") }
        config.licence.onChange { seen += it.text }

        s.licence.update(mapOf("licence.key" to "NEW-$secret".uppercase()))
        assertTrue(config.licence.refresh(), "the reload stands")
        assertEquals("NEW-$secret".uppercase(), config.licence.current().text)
        assertEquals(listOf("NEW-$secret".uppercase()), seen)
        assertEquals(listOf("file licence: an on-change hook failed: java.lang.IllegalStateException"), s.warnings)
        assertEquals(2, config.licence.status.generation)
    }

    @Test
    fun closingTheHandleUnregistersTheHook(@TempDir root: Path) {
        val s = Setup(root)
        val config = s.load()
        val seen = ArrayList<String>()
        val handle = config.settings.onChange { seen += it.value.name }
        handle.close()
        handle.close()
        s.settings.update(mapOf("settings.json" to """{"name": "two", "replicas": 2}"""))
        assertTrue(config.settings.refresh())
        assertEquals(emptyList(), seen)
    }

    @Test
    fun hooksFireWithoutARead(@TempDir root: Path) {
        val s = Setup(root)
        val config = s.load(Duration.ofMillis(100))
        val fired = CountDownLatch(1)
        val names = java.util.Collections.synchronizedList(ArrayList<String>())
        val handle = config.settings.onChange { names += it.value.name; fired.countDown() }
        try {
            s.settings.update(mapOf("settings.json" to """{"name": "two", "replicas": 2}"""))
            assertTrue(fired.await(10, TimeUnit.SECONDS), "the background check never fired the hook")
            assertEquals(listOf("two"), names.toList())
            assertEquals(2, config.settings.status.generation)
        } finally {
            handle.close()
        }
    }

    @Test
    fun statusCountsAcceptedReloadsAndKeepsTheLastRejection(@TempDir root: Path) {
        val s = Setup(root)
        var now = Instant.parse("2026-10-10T10:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val config = s.load(clock = clock)
        assertEquals(ReloadStatus(generation = 1), config.settings.status)

        now = Instant.parse("2026-10-10T11:00:00Z")
        s.settings.update(mapOf("settings.json" to """{"name": "two", "replicas": 2}"""))
        assertTrue(config.settings.refresh())
        assertEquals(ReloadStatus(2, Instant.parse("2026-10-10T11:00:00Z"), null), config.settings.status)

        now = Instant.parse("2026-10-10T12:00:00Z")
        s.settings.update(mapOf("settings.json" to """{"name": "bad", "replicas": 0}"""))
        assertFalse(config.settings.refresh())
        assertEquals(
            ReloadStatus(2, Instant.parse("2026-10-10T11:00:00Z"), RejectedReload(now, "settings", listOf(Codes.SCHEMA_MISMATCH))),
            config.settings.status,
        )
        assertEquals(ReloadStatus(1), config.tls.status, "each input has its own status")

        // An accepted change clears the rejection.
        now = Instant.parse("2026-10-10T13:00:00Z")
        s.settings.update(mapOf("settings.json" to """{"name": "three", "replicas": 3}"""))
        assertTrue(config.settings.refresh())
        assertEquals(ReloadStatus(3, now, null), config.settings.status)
    }

    @Test
    fun aRejectedSecretChangeShowsOnlyCodesInTheStatus(@TempDir root: Path) {
        val s = Setup(root)
        val config = s.load()
        s.licence.update(mapOf("licence.key" to "lower-case hunter2-SECRET"))
        assertFalse(config.licence.refresh())
        val rejected = assertNotNull(config.licence.status.lastRejected)
        assertEquals("licence", rejected.input)
        assertEquals(listOf(Codes.PATTERN_MISMATCH), rejected.codes)
        assertFalse("hunter2" in config.licence.status.toString())
    }

    @Test
    fun aKeystoreReloadsWithTheBootPassword(@TempDir root: Path) {
        val file = root.resolve("etc/app/ks/client.p12")
        val first = Certs.issue(Certs.rsa(), listOf("client"))
        Certs.writePkcs12(file, first, "boot-pass")
        val env = mutableMapOf("DOCUCONF_FILE_ROOT" to root.toString(), "CLIENT_KS_PASSWORD" to "boot-pass")
        val warnings = ArrayList<String>()
        val config = Docuconf.load<WatchedKeystoreConfig> {
            this.env = env
            warn = { warnings += it }
            reloadInterval = Duration.ZERO
        }
        fun cert(w: Watched<Keystore>) = w.current().keyStore.getCertificate("client")
        assertEquals(first.cert, cert(config.keystore))

        // Same password: taken.
        val second = Certs.issue(Certs.rsa(), listOf("client"))
        Certs.writePkcs12(file, second, "boot-pass")
        assertTrue(config.keystore.refresh())
        assertEquals(second.cert, cert(config.keystore))

        // A new password: rejected, even if the map the app passed now holds it. Rotating it needs a rollout.
        env["CLIENT_KS_PASSWORD"] = "new-pass"
        Certs.writePkcs12(file, Certs.issue(Certs.rsa(), listOf("client")), "new-pass")
        assertFalse(config.keystore.refresh())
        assertEquals(second.cert, cert(config.keystore))
        assertEquals(listOf(Codes.KEYSTORE_UNREADABLE), config.keystore.status.lastRejected?.codes)
        assertEquals(2, config.keystore.status.generation)
        assertContains(warnings.single(), Codes.KEYSTORE_UNREADABLE)
        for (w in warnings) assertFalse("new-pass" in w || "boot-pass" in w, w)
    }

    @Test
    fun ofHasAStatusAndAHookThatNeverFires() {
        val w = Watched.of(TextFile.of("x"))
        assertEquals(ReloadStatus(1), w.status)
        w.onChange { error("never") }.close()
    }

    private val watchedContract = """
        {"apiVersion": "docuconf.dev/v1alpha1", "kind": "ConfigContract",
         "metadata": {"name": "watched", "generator": {"language": "kotlin", "sdk": "docuconf-kotlin", "version": "0"}},
         "files": {"settings": {"name": "settings", "type": "config", "description": "Application settings",
                                "path": "/etc/app/settings/settings.json", "format": "json", "reload": "watch"},
                   "licence": {"name": "licence", "type": "text", "description": "Licence key",
                               "path": "/etc/app/licence/licence.key", "secret": true, "pattern": "^[A-Z0-9-]+${'$'}"}}}
    """.trimIndent()

    @Test
    fun contractFirstReloadsWatchedInputs(@TempDir root: Path) {
        val s = Setup(root)
        val contract = ContractFirst.parse(watchedContract)
        val values = Docuconf.loadContract(contract, s.env, reloadInterval = Duration.ZERO, warn = { s.warnings += it })
        @Suppress("UNCHECKED_CAST")
        val settings = values.file("settings") as Watched<JsonValue>
        assertEquals("ABCD-1234", values.file("licence"), "an input declared restart is a plain value")
        assertEquals(JsonValue.Str("one"), (settings.current() as JsonValue.Obj).fields["name"])
        val seen = ArrayList<JsonValue>()
        settings.onChange { seen += it }

        s.settings.update(mapOf("settings.json" to """{"name": "two", "replicas": 2}"""))
        assertEquals(JsonValue.Str("two"), (settings.current() as JsonValue.Obj).fields["name"])
        assertEquals(1, seen.size)
        assertEquals(2, settings.status.generation)
        assertEquals(JsonValue.Str("two"), (values.toJson().fields["settings"] as JsonValue.Obj).fields["name"])

        s.settings.update(mapOf("settings.json" to """{"name": """))
        assertFalse(settings.refresh())
        assertEquals(listOf(Codes.FILE_MALFORMED), settings.status.lastRejected?.codes)
        assertEquals(1, seen.size)
    }

    @Test
    fun contractFirstRejectsWatchItCannotKeep() {
        val contract = ContractFirst.parse(watchedContract)
        // A reader of the app's own that reads each file once.
        val once = object : ContractFirst.Inputs {
            override fun loadFile(spec: dev.docuconf.kotlin.core.FileSpec, env: Map<String, String>) = ContractFirst.LoadedFile(null)
            override fun readOverlay(spec: dev.docuconf.kotlin.core.OverlaySpec, env: Map<String, String>) = ContractFirst.LoadedOverlay(null)
        }
        val e = assertFailsWith<DeclarationException> { ContractFirst.check(contract, emptyMap(), once) }
        assertEquals(listOf("file settings: reload \"watch\" is not supported by this reader, which reads the file once; declare it \"restart\""), e.problems)

        val overlay = ContractFirst.parse(
            watchedContract.replace(
                "\"files\":",
                "\"overlays\": {\"tuning\": {\"name\": \"tuning\", \"format\": \"yaml\", \"path\": \"/etc/app/tuning/tuning.yaml\", \"keySeparator\": \":\", \"reload\": \"watch\"}}, \"files\":",
            ),
        )
        val o = assertFailsWith<DeclarationException> { Docuconf.checkContract(overlay, emptyMap()) }
        assertContains(o.message!!, "overlay tuning: reload \"watch\" is not supported in the contract-first mode")
    }
}
