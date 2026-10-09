package dev.docuconf.hoplite

import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.KeyAlgorithm
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TlsTest {
    private fun World.tls(leaf: Certs.Issued, key: String = Certs.pkcs8(leaf.keys), chain: List<java.security.cert.X509Certificate> = emptyList()) {
        val dir = path("/etc/gateway/tls")
        dir.toFile().deleteRecursively()
        Certs.writeTls(dir, leaf, key, chain)
    }

    private val names = listOf("gateway.internal", "api.example.com")

    @Test
    fun validKeyPairsInEveryKeyFormat(@TempDir root: Path) {
        val w = World(root)
        val rsa = Certs.issue(Certs.rsa(), names)
        w.tls(rsa, Certs.traditional(rsa.keys)) // PKCS#1
        assertEquals(emptyList(), w.codes())
        w.tls(rsa) // PKCS#8
        assertEquals(emptyList(), w.codes())
        val ec = Certs.issue(Certs.ec(), names)
        w.tls(ec, Certs.traditional(ec.keys)) // SEC 1 without curve parameters
        assertEquals(emptyList(), w.codes())
        w.tls(ec, Certs.sec1WithCurve(ec.keys)) // SEC 1 as openssl writes it
        assertEquals(emptyList(), w.codes())
    }

    @Test
    fun expiringCertificate(@TempDir root: Path) {
        val w = World(root)
        w.tls(Certs.issue(Certs.ec(), names, notAfter = Instant.now().plus(10, ChronoUnit.DAYS)))
        val v = w.violations().single()
        assertEquals(Codes.CERTIFICATE_EXPIRING, v.code)
        assertContains(v.message, "720h")
    }

    @Test
    fun expiredAndNotYetValid(@TempDir root: Path) {
        val w = World(root)
        w.tls(Certs.issue(Certs.ec(), names, notBefore = Instant.now().minus(30, ChronoUnit.DAYS), notAfter = Instant.now().minus(1, ChronoUnit.DAYS)))
        assertEquals(listOf(Codes.CERTIFICATE_INVALID), w.codes())
        w.tls(Certs.issue(Certs.ec(), names, notBefore = Instant.now().plus(1, ChronoUnit.DAYS), notAfter = Instant.now().plus(100, ChronoUnit.DAYS)))
        assertEquals(listOf(Codes.CERTIFICATE_INVALID), w.codes())
    }

    @Test
    fun clockIsInjectable(@TempDir root: Path) {
        val w = World(root)
        val later = Clock.fixed(Instant.now().plus(80, ChronoUnit.DAYS), ZoneOffset.UTC)
        val r = Docuconf.check(GatewayConfig::class, DocuconfOptions().apply(w.options).apply { clock = later })
        assertIs<LoadResult.Failure>(r)
        assertEquals(listOf(Codes.CERTIFICATE_EXPIRING), r.violations.map { it.code })
    }

    @Test
    fun dnsNameMismatch(@TempDir root: Path) {
        val w = World(root)
        w.tls(Certs.issue(Certs.ec(), listOf("gateway.internal", "other.example.com")))
        val v = w.violations().single()
        assertEquals(Codes.CERTIFICATE_NAME_MISMATCH, v.code)
        assertContains(v.message, "api.example.com")
    }

    @Test
    fun wildcardCoversOneLabel(@TempDir root: Path) {
        val w = World(root)
        w.tls(Certs.issue(Certs.ec(), listOf("gateway.internal", "*.example.com")))
        assertEquals(emptyList(), w.codes())
        assertTrue(FileLoader.covers("*.example.com", "api.example.com"))
        assertFalse(FileLoader.covers("*.example.com", "a.b.example.com"))
        assertFalse(FileLoader.covers("*.example.com", "example.com"))
    }

    @Test
    fun keyMismatch(@TempDir root: Path) {
        val w = World(root)
        val leaf = Certs.issue(Certs.ec(), names)
        w.tls(leaf, Certs.pkcs8(Certs.ec()))
        assertEquals(listOf(Codes.KEY_MISMATCH), w.codes())
        // A key of another algorithm altogether.
        w.tls(leaf, Certs.pkcs8(Certs.rsa()))
        assertEquals(listOf(Codes.KEY_MISMATCH), w.codes())
        // No PEM key at all is file_malformed (SPEC §11.2 item 5).
        w.tls(leaf, "not a key\n")
        assertEquals(listOf(Codes.FILE_MALFORMED), w.codes())
    }

    @Test
    fun disallowedKeyAlgorithm(@TempDir root: Path) {
        val w = World(root)
        w.tls(Certs.issue(Certs.ed25519(), names))
        val v = w.violations().single()
        assertEquals(Codes.CERTIFICATE_INVALID, v.code)
        assertContains(v.message, "Ed25519")
    }

    @Test
    fun malformedCertificateAndMissingKey(@TempDir root: Path) {
        val w = World(root)
        // No PEM certificate at all is file_malformed; a PEM block that does not parse is
        // certificate_invalid (SPEC §11.2 item 5).
        Files.writeString(w.path("/etc/gateway/tls/tls.crt"), "garbage")
        assertEquals(listOf(Codes.FILE_MALFORMED), w.codes())
        Files.writeString(w.path("/etc/gateway/tls/tls.crt"), "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n")
        assertEquals(listOf(Codes.CERTIFICATE_INVALID), w.codes())
        Files.delete(w.path("/etc/gateway/tls/tls.key"))
        assertEquals(listOf(Codes.FILE_MISSING), w.codes())
        w.path("/etc/gateway/tls").toFile().deleteRecursively()
        val v = w.violations().single()
        assertEquals(Codes.FILE_MISSING, v.code)
        assertEquals("serving-tls", v.input)
    }

    data class WithCa(
        @Doc("Certificate for mutual TLS")
        @FileInput(name = "mtls", path = "/etc/mtls")
        @Tls(dnsNames = ["svc.internal"], keyAlgorithms = [KeyAlgorithm.RSA, KeyAlgorithm.ECDSA], requireCA = true)
        val mtls: TlsKeyPair,
    )

    @Test
    fun chainsToCa(@TempDir root: Path) {
        val ca = Certs.issue(Certs.ec(), emptyList(), ca = true, cn = "Test Root")
        val intermediate = Certs.issue(Certs.ec(), emptyList(), issuer = ca, ca = true, cn = "Test Intermediate")
        val leaf = Certs.issue(Certs.rsa(), listOf("svc.internal"), issuer = intermediate)
        val dir = Path.of("$root/etc/mtls")
        Certs.writeTls(dir, leaf, chain = listOf(intermediate.cert), ca = ca.cert)
        fun check() = Docuconf.check(WithCa::class, DocuconfOptions().apply { env = mapOf("DOCUCONF_FILE_ROOT" to root.toString()); warn = {} })
        val ok = check()
        assertIs<LoadResult.Success<WithCa>>(ok)
        assertEquals(1, ok.value.mtls.ca.size)
        assertEquals(2, ok.value.mtls.certificateChain.size)

        // Leaf only: the chain cannot be built.
        Certs.writeTls(dir, leaf, ca = ca.cert)
        val broken = check()
        assertIs<LoadResult.Failure>(broken)
        assertEquals(listOf(Codes.CERTIFICATE_INVALID), broken.violations.map { it.code })
        assertContains(broken.violations.single().message, "chain")

        // Signed by another CA.
        val other = Certs.issue(Certs.ec(), emptyList(), ca = true, cn = "Other Root")
        Certs.writeTls(dir, leaf, chain = listOf(intermediate.cert), ca = other.cert)
        assertEquals(listOf(Codes.CERTIFICATE_INVALID), (check() as LoadResult.Failure).violations.map { it.code })

        // ca.crt missing.
        Files.delete(dir.resolve("ca.crt"))
        assertEquals(listOf(Codes.FILE_MISSING), (check() as LoadResult.Failure).violations.map { it.code })
    }
}

class OtherFilesTest {
    @Test
    fun malformedConfigFile(@TempDir root: Path) {
        val w = World(root)
        w.write("/etc/gateway/routes/routes.yaml", "routes: [\n  - match: /a\n")
        assertEquals(listOf(Codes.FILE_MALFORMED), w.codes())
    }

    @Test
    fun configSchemaViolations(@TempDir root: Path) {
        val w = World(root)
        w.write("/etc/gateway/routes/routes.yaml", "routes:\n  - match: api\n    upstream: ftp://x\n    extra: 1\n")
        val v = w.violations()
        assertEquals(setOf(Codes.SCHEMA_MISMATCH), v.map { it.code }.toSet())
        val text = v.joinToString("\n") { it.message }
        assertContains(text, "$.routes[0].match")
        assertContains(text, "$.routes[0].upstream")
        assertContains(text, "$.routes[0].extra")
    }

    @Test
    fun configFileMissingRequiredProperty(@TempDir root: Path) {
        val w = World(root)
        w.write("/etc/gateway/routes/routes.yaml", "other: 1\n")
        assertTrue(w.violations().any { it.code == Codes.SCHEMA_MISMATCH && "routes" in it.message })
    }

    data class JsonAndToml(
        @Doc("Limits as JSON") @FileInput(name = "limits-json", path = "/etc/a/limits.json") val json: ConfigFile<RateLimits>,
        @Doc("Limits as TOML") @FileInput(name = "limits-toml", path = "/etc/b/limits.toml") val toml: ConfigFile<RateLimits>,
    )

    @Test
    fun jsonAndTomlConfigFiles(@TempDir root: Path) {
        val w = World(root)
        w.write("/etc/a/limits.json", """{"perMinute": 10, "burst": 2}""")
        w.write("/etc/b/limits.toml", "perMinute = 20\n")
        val opts = DocuconfOptions().apply { env = mapOf("DOCUCONF_FILE_ROOT" to root.toString()); warn = {} }
        val cfg = Docuconf.load(JsonAndToml::class, opts)
        assertEquals(RateLimits(10, 2), cfg.json.value)
        assertEquals(RateLimits(20, 0), cfg.toml.value)

        // JSON is typed: a string where an integer belongs is a schema violation.
        w.write("/etc/a/limits.json", """{"perMinute": "10"}""")
        w.write("/etc/b/limits.toml", "perMinute = \n")
        val r = Docuconf.check(JsonAndToml::class, opts)
        assertIs<LoadResult.Failure>(r)
        assertEquals(setOf("limits-json:schema_mismatch", "limits-toml:file_malformed"), r.violations.map { "${it.input}:${it.code}" }.toSet())
    }

    @Test
    fun fileTooLarge(@TempDir root: Path) {
        val w = World(root)
        w.write("/etc/gateway/routes/routes.yaml", "routes:\n" + "  - match: /a\n    upstream: http://a\n".repeat(3000))
        assertEquals(listOf(Codes.FILE_TOO_LARGE), w.codes())
    }

    @Test
    fun textFileConstraintsNeverEchoSecretContent(@TempDir root: Path) {
        val w = World(root)
        w.write("/etc/gateway/license/license.key", "SECRET-LICENCE\n")
        val v = w.violations().single()
        assertEquals(Codes.PATTERN_MISMATCH, v.code)
        assertFalse("SECRET-LICENCE" in v.message)
        assertFalse("SECRET-LICENCE" in w.check().toString())
    }

    @Test
    fun caBundle(@TempDir root: Path) {
        val w = World(root)
        val ca1 = Certs.issue(Certs.ec(), emptyList(), ca = true, cn = "CA 1")
        val ca2 = Certs.issue(Certs.ec(), emptyList(), ca = true, cn = "CA 2")
        w.write("/etc/gateway/ca/bundle.pem", Certs.pem(ca1.cert) + Certs.pem(ca2.cert))
        val cfg = Docuconf.load<GatewayConfig>(w.options)
        assertEquals(2, cfg.upstreamCa!!.certificates.size)
        cfg.upstreamCa!!.trustManagerFactory()

        w.write("/etc/gateway/ca/bundle.pem", "no certificates here\n")
        assertEquals(listOf(Codes.FILE_MALFORMED), w.codes())
        // A PEM certificate that does not parse is certificate_invalid (SPEC §11.2 item 5).
        w.write("/etc/gateway/ca/bundle.pem", "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n")
        assertEquals(listOf(Codes.CERTIFICATE_INVALID), w.codes())

        // SSL_CERT_FILE points somewhere else, under the same file root.
        w.write("/opt/certs/other.pem", Certs.pem(ca1.cert))
        w.env["SSL_CERT_FILE"] = "/opt/certs/other.pem"
        assertEquals(1, Docuconf.load<GatewayConfig>(w.options).upstreamCa!!.certificates.size)
    }

    @Test
    fun keystore(@TempDir root: Path) {
        val w = World(root)
        val client = Certs.issue(Certs.rsa(), listOf("client"))
        Certs.writePkcs12(w.path("/etc/gateway/partner/keystore.p12"), client, "s3cret-pass")
        w.env["PARTNER_PASSWORD"] = "s3cret-pass"
        val cfg = Docuconf.load<GatewayConfig>(w.options)
        assertTrue(cfg.partner.keystore!!.keyStore.isKeyEntry("client"))

        w.env["PARTNER_PASSWORD"] = "wrong-pass"
        val v = w.violations().single()
        assertEquals(Codes.KEYSTORE_UNREADABLE, v.code)
        assertFalse("wrong-pass" in v.message)
        assertContains(v.message, "PARTNER_PASSWORD")
    }

    @Test
    fun binaryFile(@TempDir root: Path) {
        val w = World(root)
        Files.createDirectories(w.path("/data/geoip"))
        Files.write(w.path("/data/geoip/GeoLite2-City.mmdb"), byteArrayOf(1, 2, 3))
        assertEquals(3, Docuconf.load<GatewayConfig>(w.options).geoip!!.readBytes().size)
    }

    @Test
    fun unreadableFile(@TempDir root: Path) {
        assumeFalse(System.getProperty("user.name") == "root", "root can read any file")
        val w = World(root)
        val license = w.path("/etc/gateway/license/license.key")
        Files.setPosixFilePermissions(license, PosixFilePermissions.fromString("---------"))
        val v = w.violations().single()
        assertEquals(Codes.FILE_UNREADABLE, v.code)
        assertContains(v.message, "fsGroup")
    }
}
