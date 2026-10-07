package dev.docuconf.hoplite

import com.sksamuel.hoplite.Secret
import dev.docuconf.kotlin.core.KeyAlgorithm
import dev.docuconf.kotlin.core.KeystoreFormat
import java.net.URI
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

// The fixture declaration: every variable type, every file input type and an overlay. Exported to
// src/test/resources/golden/gateway.cue.

@Suppress("EnumEntryName")
enum class LogLevel { debug, info, warn, error }

data class RateLimits(
    @Doc("Requests per minute per client") @Min(1) val perMinute: Int,
    @Doc("Extra requests allowed in a burst") @Min(0) val burst: Int = 0,
)

data class Route(
    @Pattern("^/") val match: String,
    @Pattern("^https?://") val upstream: String,
    val timeout: Duration? = null,
)

data class Routes(@Items(min = 1) val routes: List<Route>)

data class Pod(
    @Doc("Namespace the gateway runs in, for metrics labels") val namespace: String,
)

data class Database(
    @Doc("Primary Postgres connection string") @Schemes("postgres", "postgresql") val url: Secret,
    @Doc("Connection pool size") @Min(1) @Max(100) val poolSize: Int = 10,
)

data class Partner(
    @Doc("Password for the partner mTLS keystore") val password: Secret? = null,
    @Doc("Client certificate for mTLS to the partner API")
    @FileInput(name = "partner-keystore", path = "/etc/gateway/partner/keystore.p12")
    @KeystoreSpec(format = KeystoreFormat.PKCS12, passwordVar = "PARTNER_PASSWORD")
    val keystore: Keystore? = null,
)

@ConfigOverlay(name = "platform", path = "/app/config/gateway.yaml", description = "Settings the platform supplies per environment")
data class GatewayConfig(
    @Doc("HTTP listen port") @Min(1) @Max(65535) val port: Int = 8080,
    @Doc("Minimum log level emitted") val logLevel: LogLevel = LogLevel.info,
    @Doc("Fraction of requests to trace") @DecimalMin(0.0) @DecimalMax(1.0) val traceRatio: Double = 0.1,
    @Doc("Whether to gzip responses") val compress: Boolean = true,
    @Doc("Upstream request timeout") @DurationMin("1s") @DurationMax("5m") val timeout: Duration = Duration.ofSeconds(30),
    @Doc("How long idle upstream connections are kept") val idle: kotlin.time.Duration = 90.seconds,
    @Doc("Public base URL of the gateway") @Schemes("https") val publicUrl: URI,
    @Doc("Kafka brokers to publish access logs to") @Items(min = 1) @Examples("kafka-0:9092") val brokers: List<String> = listOf("kafka-0:9092", "kafka-1:9092"),
    @Doc("Extra ports that serve the admin API") @Items(max = 4) @ItemMin(1) @ItemMax(65535) val adminPorts: List<Int> = emptyList(),
    @Doc("Default per-client rate limits") val rateLimits: Json<RateLimits>? = null,
    @Doc("Cloud region code") @Pattern("^[a-z]{2}-[a-z]+-[0-9]$") @Length(max = 20) @Group("cloud") val region: String = "eu-west-1",
    @Doc("Billing tier of this deployment") @OneOf("free", "pro") val tier: String = "free",
    @Doc("Old name of the listen port") @DeprecatedInput("Use PORT.", replacedBy = "PORT") val legacyPort: Int? = null,
    @NotInContract val vaultToken: String? = null,
    @Group("runtime") val pod: Pod,
    @Group("database") val db: Database,
    val partner: Partner = Partner(),

    @Doc("Routing table: path prefixes and their upstreams")
    @FileInput(name = "routes", path = "/etc/gateway/routes/routes.yaml", pathEnv = "ROUTES_FILE", maxSize = 65536)
    val routes: ConfigFile<Routes>,

    @Doc("Certificate the gateway serves HTTPS with")
    @FileInput(name = "serving-tls", path = "/etc/gateway/tls")
    @Tls(dnsNames = ["gateway.internal", "api.example.com"], keyAlgorithms = [KeyAlgorithm.ECDSA, KeyAlgorithm.RSA], minRemaining = "720h")
    val servingTls: TlsKeyPair,

    @Doc("Private CAs the gateway trusts for upstream TLS")
    @FileInput(name = "upstream-ca", path = "/etc/gateway/ca/bundle.pem", pathEnv = "SSL_CERT_FILE")
    val upstreamCa: CaBundle? = null,

    @Doc("Gateway licence key")
    @FileInput(name = "license", path = "/etc/gateway/license/license.key", secret = true)
    @Pattern("^[A-Z0-9]{5}(-[A-Z0-9]{5}){3}\\n?$")
    val license: TextFile,

    @Doc("GeoIP database for country-based routing")
    @FileInput(name = "geoip", path = "/data/geoip/GeoLite2-City.mmdb", maxSize = 134217728)
    val geoip: BinaryFile? = null,
)
