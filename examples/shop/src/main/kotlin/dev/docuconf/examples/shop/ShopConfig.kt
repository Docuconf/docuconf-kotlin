package dev.docuconf.examples.shop

import dev.docuconf.kotlin.core.Contract
import dev.docuconf.kotlin.core.ContractFirst
import dev.docuconf.kotlin.core.ContractValues

/**
 * The shop's typed configuration, read from the values `ContractFirst` checked against the
 * checked-in `contract.json`. `DOCUCONF_ROLE` picks the role, and each role gets only its own
 * inputs: the contract makes the other role's inputs optional.
 *
 * Not data classes, so `toString` never shows [databaseUrl] or [Worker.webhookSigningKey].
 */
sealed class ShopConfig(
    /** `DATABASE_URL`: secret. */
    val databaseUrl: String,
    /** `LOG_FORMAT`: `text` or `json`. */
    val logFormat: String,
    /** `LOG_SCHEMA_URL`: set when [logFormat] is `json` (`requiredIf`). */
    val logSchemaUrl: String?,
) {
    class Web(
        databaseUrl: String,
        logFormat: String,
        logSchemaUrl: String?,
        /** `PORT`. */
        val port: Int,
        /** `ALLOWED_ORIGINS`: a csv list, at least one item. */
        val allowedOrigins: List<String>,
    ) : ShopConfig(databaseUrl, logFormat, logSchemaUrl)

    class Worker(
        databaseUrl: String,
        logFormat: String,
        logSchemaUrl: String?,
        /** `QUEUES`: a csv list, in priority order. */
        val queues: List<String>,
        /** `WEBHOOKS_ENABLED`. */
        val webhooksEnabled: Boolean,
        /** The `webhook-signing-key` file's text (secret): set when [webhooksEnabled] (`requiredIf`). */
        val webhookSigningKey: String?,
    ) : ShopConfig(databaseUrl, logFormat, logSchemaUrl)

    /** The one line the app prints on a good start. */
    fun summary(): String = when (this) {
        is Web -> "shop web: port $port, origins ${allowedOrigins.joinToString(",")}"
        is Worker -> "shop worker: queues ${queues.joinToString(",")}, webhooks ${if (webhooksEnabled) "on" else "off"}"
    }

    companion object {
        /** The checked-in contract.json, packaged on the classpath by the build. */
        val contract: Contract by lazy {
            val text = ShopConfig::class.java.getResource("/contract.json")?.readText()
                ?: error("contract.json is not on the classpath")
            ContractFirst.parse(text)
        }

        /** Builds the typed config from values that passed the contract's checks. */
        fun from(values: ContractValues): ShopConfig {
            val databaseUrl = values.string("DATABASE_URL")!!
            val logFormat = values.string("LOG_FORMAT")!!
            val logSchemaUrl = values.string("LOG_SCHEMA_URL")
            return when (val role = values.string("DOCUCONF_ROLE")) {
                "web" -> Web(
                    databaseUrl, logFormat, logSchemaUrl,
                    port = values.long("PORT")!!.toInt(),
                    allowedOrigins = values.stringList("ALLOWED_ORIGINS")!!,
                )
                "worker" -> Worker(
                    databaseUrl, logFormat, logSchemaUrl,
                    queues = values.stringList("QUEUES")!!,
                    webhooksEnabled = values.boolean("WEBHOOKS_ENABLED")!!,
                    webhookSigningKey = values.file("webhook-signing-key") as String?,
                )
                else -> error("unknown role $role") // the contract's enum allows only web and worker
            }
        }
    }
}
