package dev.docuconf.hoplite

import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.Reload
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

        fun load(interval: Duration = Duration.ZERO): WatchedConfig = Docuconf.load {
            env = this@Setup.env
            warn = { warnings += it }
            reloadInterval = interval
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
}
