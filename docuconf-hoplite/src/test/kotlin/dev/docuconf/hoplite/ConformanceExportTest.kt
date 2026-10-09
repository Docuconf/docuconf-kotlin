package dev.docuconf.hoplite

import com.sksamuel.hoplite.Secret
import dev.docuconf.kotlin.core.KeyAlgorithm
import dev.docuconf.kotlin.core.KeystoreFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shared export fixture (SPEC §11.2 item 3, §12): docuconf-go's `conformance/export/fixture.yaml`
 * declared with this SDK's annotations, exported, and compared with `conformance/export/golden.cue`
 * as data by `docuconf conformance export --golden`.
 *
 * The comparison is not clean: two file inputs in the fixture declare `reload: watch`, which this SDK
 * rejects at declaration time because it validates files once, at boot, and does not reload them
 * (SPEC §11.2 item 8). The test therefore requires exactly those two differences and no other, and
 * the SDK keeps its own golden contract too (ExportTest).
 *
 * Needs the docuconf CLI (`DOCUCONF_CLI`, else `docuconf` on PATH) and docuconf-go (`DOCUCONF_GO_DIR`,
 * else the directory above `DOCUCONF_CONFORMANCE`'s, else `../docuconf-go`). Skips without them,
 * unless `DOCUCONF_REQUIRE_CONFORMANCE=1`.
 */
class ConformanceExportTest {
    /** The settings type the fixture's config files are bound to. */
    data class FixtureSettings(
        @Length(min = 1) val name: String,
        @Min(1) val replicas: Long,
        val tags: List<String>? = null,
    )

    /** The type RATE_LIMITS is bound to. */
    data class RateLimits(
        @Min(1) val perMinute: Long,
        @Min(0) val burst: Long? = null,
    )

    enum class Level {
        @WireName("debug") DEBUG,
        @WireName("info") INFO,
        @WireName("warn") WARN,
        @WireName("error") ERROR,
    }

    @DocuconfService(name = "docuconf-fixture")
    data class Fixture(
        @Doc("Service name, used in logs and metrics", details = "Lower case, as a DNS label allows.")
        @Length(min = 2, max = 40) @Pattern("^[a-z][a-z0-9-]*$") @Group("general") @Examples("orders", "billing")
        val appName: String = "orders",
        @Doc("Primary Postgres connection string") @Schemes("postgres", "postgresql") @Length(max = 2048) @Group("database")
        val databaseUrl: Secret,
        @Doc("HTTP listen port") @Min(1) @Max(65535) val port: Int = 8080,
        @Doc("Fraction of requests traced") @DecimalMin(0.0) @DecimalMax(1.0) val traceRatio: Double = 0.25,
        @Doc("Serve the debug endpoints") val debug: Boolean = false,
        @Doc("Upstream request timeout") @DurationMin("1s") @DurationMax("5m") val requestTimeout: Duration = Duration.ofSeconds(90),
        @Doc("Minimum log level") val logLevel: Level = Level.INFO,
        @Doc("CORS origins allowed to call the API") @Items(min = 1, max = 5) @ItemLength(min = 1, max = 255) @Separator(";")
        val allowedOrigins: List<String>? = null,
        @Doc("Shards this instance owns") @ItemMin(0) @ItemMax(1023) val shards: List<Long>? = null,
        @Doc("Keys that verify webhook signatures") @KeyLength(min = 32, max = 256) val webhookKeys: KeySet? = null,
        @Doc("Per-client rate limits") @Length(max = 1024) val rateLimits: Json<RateLimits> = Json(RateLimits(perMinute = 60)),
        @Doc("Port the service used to listen on") @DeprecatedInput("Use PORT instead", replacedBy = "PORT") val oldPort: Long? = null,
        @Doc("Password of the partner keystore") val partnerPassword: Secret? = null,

        @Doc("Application settings") @Group("general")
        @FileInput(name = "settings", path = "/etc/app/settings/settings.json", pathEnv = "SETTINGS_FILE", maxSize = 65536)
        val settings: ConfigFile<FixtureSettings>,
        @Doc("Routing rules") @FileInput(name = "rules", path = "/etc/app/rules/rules.yaml")
        val rules: ConfigFile<FixtureSettings>? = null,
        @Doc("Feature defaults") @FileInput(name = "flags", path = "/etc/app/flags/flags.toml")
        val flags: ConfigFile<FixtureSettings>? = null,
        @Doc("Certificate the service serves HTTPS with") @FileInput(name = "serving-tls", path = "/etc/app/tls")
        @Tls(dnsNames = ["app.example.test", "api.example.test"], keyAlgorithms = [KeyAlgorithm.ECDSA, KeyAlgorithm.ED25519], minRemaining = "720h", requireCA = true)
        val servingTls: TlsKeyPair? = null,
        @Doc("CAs the service trusts") @FileInput(name = "trust", path = "/etc/app/trust/bundle.pem") @MinCertificates(2)
        val trust: CaBundle? = null,
        @Doc("Client certificate for the partner API") @FileInput(name = "partner", path = "/etc/app/partner/keystore.p12")
        @KeystoreSpec(format = KeystoreFormat.PKCS12, passwordVar = "PARTNER_PASSWORD")
        val partner: Keystore? = null,
        @Doc("Licence key") @FileInput(name = "licence", path = "/etc/app/licence/licence.key") @Length(min = 8, max = 64) @Pattern("^[A-Z0-9-]+\\n?$")
        val licence: TextFile? = null,
        @Doc("GeoIP database") @FileInput(name = "geoip", path = "/data/geoip/geoip.mmdb", maxSize = 134217728)
        @DeprecatedInput("Use geo-db instead", replacedBy = "geo-db")
        val geoip: BinaryFile? = null,
        @Doc("City-level location database") @FileInput(name = "geo-db", path = "/data/geo-db/geo.mmdb")
        val geoDb: BinaryFile? = null,
    )

    /**
     * What `docuconf conformance export` reports for this SDK's export, one line per difference: the
     * fixture's `reload: watch`, which this SDK rejects (it does not reload files).
     */
    private val knownGaps = listOf(
        """files.serving-tls.reload: golden "watch", exported "restart"""",
        """files.settings.reload: golden "watch", exported "restart"""",
    )

    @Test
    fun exportMatchesTheSharedGolden() {
        val goDir = goDir()
        val cli = cli()
        val required = System.getenv("DOCUCONF_REQUIRE_CONFORMANCE") == "1"
        val golden = goDir?.resolve("conformance/export/golden.cue")
        if (required) {
            assertTrue(golden != null && Files.isRegularFile(golden), "DOCUCONF_REQUIRE_CONFORMANCE=1 but golden.cue was not found (DOCUCONF_GO_DIR=$goDir)")
            assertTrue(cli != null, "DOCUCONF_REQUIRE_CONFORMANCE=1 but the docuconf CLI was not found (DOCUCONF_CLI or PATH)")
        }
        assumeTrue(golden != null && Files.isRegularFile(golden), "docuconf-go's conformance/export/golden.cue not found; skipping")
        assumeTrue(cli != null, "the docuconf CLI is not installed; skipping")

        val exported = Docuconf.exportCue(Fixture::class, appVersion = "1.0.0") { warn = {} }
        val out = File(System.getProperty("docuconf.projectDir") ?: ".", "build/conformance-export/exported.cue")
        out.parentFile.mkdirs()
        out.writeText(exported)

        val p = ProcessBuilder(cli, "conformance", "export", "--golden", golden.toString(), out.absolutePath).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "docuconf conformance export timed out")
        val diffs = output.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("docuconf:") && !it.contains(" does not match ") && !it.contains(" matches ") }
        if (diffs != knownGaps) {
            fail("docuconf conformance export (exit ${p.exitValue()}) reported:\n$output\nexpected exactly the known gaps:\n${knownGaps.joinToString("\n")}\nexported contract: $out")
        }
        assertNotEquals(0, p.exitValue(), "the known gaps should make the comparison fail")
    }

    private fun goDir(): Path? {
        System.getenv("DOCUCONF_GO_DIR")?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        System.getenv("DOCUCONF_CONFORMANCE")?.takeIf { it.isNotEmpty() }?.let { return Path.of(it).toAbsolutePath().parent?.parent }
        val root = System.getProperty("docuconf.rootDir") ?: return null
        return Path.of(root, "../docuconf-go").normalize()
    }

    private fun cli(): String? {
        System.getenv("DOCUCONF_CLI")?.takeIf { it.isNotEmpty() }?.let { return it }
        val home = System.getProperty("user.home")
        val candidates = (System.getenv("PATH") ?: "").split(':').map { "$it/docuconf" } + "$home/go/bin/docuconf"
        return candidates.firstOrNull { Files.isExecutable(Path.of(it)) }
    }
}
