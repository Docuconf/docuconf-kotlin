package dev.docuconf.examples.orders

import com.sksamuel.hoplite.Secret
import dev.docuconf.hoplite.Docuconf
import dev.docuconf.hoplite.DocuconfOptions
import dev.docuconf.hoplite.LoadResult
import dev.docuconf.kotlin.core.Codes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class WebhooksTest {
    private val oldKey = "o".repeat(32)
    private val newKey = "n".repeat(32)
    private val body = """{"order":"42","status":"paid"}""".toByteArray()

    private fun sign(key: String) = hmacSha256(key, body).joinToString("") { "%02x".format(it) }

    private fun check(keys: String) = Docuconf.check(
        OrdersConfig::class,
        DocuconfOptions().apply {
            env = mapOf("DATABASE_URL" to "postgres://u:p@db/orders", "WEBHOOK_KEYS" to keys)
            terminationLog = "/dev/null"
        },
    )

    /** Loads WEBHOOK_KEYS as the service does at boot. */
    private fun keys(value: String): List<Secret>? {
        val result = check(value)
        assertIs<LoadResult.Success<OrdersConfig>>(result, result.toString())
        return result.value.webhookKeys
    }

    // A key rotation: each step is a rollout with a new WEBHOOK_KEYS, and a webhook signed with the
    // key in use always verifies.
    @Test
    fun aRotationNeverTurnsAwayAWebhook() {
        for ((step, value, accepts) in listOf(
            Triple("before", oldKey, mapOf(oldKey to true, newKey to false)),
            Triple("overlap", "$oldKey,$newKey", mapOf(oldKey to true, newKey to true)),
            Triple("after", newKey, mapOf(oldKey to false, newKey to true)),
        )) {
            val set = keys(value)
            for ((key, want) in accepts) assertEquals(want, verifyWebhook(set, body, sign(key)), "$step: key ${key.take(1)}...")
            assertFalse(verifyWebhook(set, body, sign("x".repeat(32))), "$step: another key")
        }
    }

    @Test
    fun aBadOrMissingSignatureIsRejected() {
        val set = keys(oldKey)
        assertFalse(verifyWebhook(set, body, "not hex"))
        assertFalse(verifyWebhook(set, body, null))
        assertFalse(verifyWebhook(null, body, sign(oldKey))) // no keys configured
    }

    // An empty or truncated key, or a third key, fails at boot without printing any key.
    @Test
    fun aBadKeySetFailsAtBoot() {
        for ((value, code) in listOf(
            "$oldKey," to Codes.OUT_OF_RANGE,
            "$oldKey,${newKey.take(10)}" to Codes.OUT_OF_RANGE,
            "$oldKey,$newKey,${"x".repeat(32)}" to Codes.TOO_MANY_ITEMS,
        )) {
            val result = check(value)
            assertIs<LoadResult.Failure>(result)
            assertEquals(listOf(code to "WEBHOOK_KEYS"), result.violations.map { it.code to it.input })
            assertFalse(result.violations.toString().contains(oldKey) || result.violations.toString().contains(newKey.take(10)))
        }
    }
}
