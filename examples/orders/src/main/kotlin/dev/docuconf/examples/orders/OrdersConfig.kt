package dev.docuconf.examples.orders

import com.sksamuel.hoplite.Secret
import dev.docuconf.hoplite.Doc
import dev.docuconf.hoplite.DocuconfService
import dev.docuconf.hoplite.DurationMax
import dev.docuconf.hoplite.DurationMin
import dev.docuconf.hoplite.Items
import dev.docuconf.hoplite.KeyLength
import dev.docuconf.hoplite.KeySet
import dev.docuconf.hoplite.Length
import dev.docuconf.hoplite.Max
import dev.docuconf.hoplite.Min
import dev.docuconf.hoplite.Schemes
import dev.docuconf.hoplite.WireName
import java.time.Duration

// A plain Hoplite config class. The docuconf annotations add what Hoplite cannot express
// (descriptions, bounds, the secret, URL schemes); docuconf exports them to contract.cue and checks
// them at boot. Each property reads its name in SCREAMING_SNAKE_CASE: logLevel reads LOG_LEVEL.
@DocuconfService(name = "orders")
data class OrdersConfig(
    @Doc("HTTP listen port") @Min(1) @Max(65535) val port: Int = 8080,
    @Doc("Minimum level of log messages") val logLevel: LogLevel = LogLevel.INFO,
    // A Hoplite Secret is exported with `secret: true`; its value never appears in errors or toString.
    // @Length(max) on a URL is its maxLength in characters; a longer one fails the boot with out_of_range.
    @Doc("Postgres connection URL for the orders database") @Schemes("postgres") @Length(max = 2048) val databaseUrl: Secret,
    @Doc("Origins allowed to call the API (CORS)") @Items(min = 1) val allowedOrigins: List<String> = listOf("http://localhost:3000"),
    @Doc("Time limit for handling one request") @DurationMin("1s") @DurationMax("5m") val requestTimeout: Duration = Duration.ofSeconds(30),
    /**
     * Number of background workers that process orders
     *
     * A KDoc works instead of @Doc: its first sentence is the description, and the rest is the details,
     * longer docs for `docuconf docs`. Each worker holds one connection from the pool of [databaseUrl],
     * so keep this below the database's connection limit.
     *
     * - Raise it when the order queue backs up.
     * - Lower it when the database is the bottleneck.
     */
    @Min(1) @Max(64) val workerCount: Int = 4,
    // A KeySet is a key set (contract type keySet, always secret): one comma-separated variable, so
    // one Kubernetes Secret key holds "old,new" while a key is rotated (SPEC §6.1). It holds 1 or 2
    // keys by default (@Keys changes that). No default, as for every secret.
    /**
     * Keys that verify the signature on incoming payment webhooks
     *
     * A webhook is accepted when it is signed with any key in the set, so the key can be rotated
     * without turning webhooks away; the generated docs list the rotation steps. Each key is 32 to
     * 256 characters, so an empty or truncated key fails at boot. Without this variable, the service
     * rejects every webhook.
     */
    @KeyLength(min = 32, max = 256) val webhookKeys: KeySet? = null,
)

/** Lowercase on the wire (`LOG_LEVEL=debug`), idiomatic constants in Kotlin. */
enum class LogLevel {
    @WireName("debug") DEBUG,
    @WireName("info") INFO,
    @WireName("warn") WARN,
    @WireName("error") ERROR,
}
