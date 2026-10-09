package dev.docuconf.examples.orders

import com.sksamuel.hoplite.Secret
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The largest body `POST /webhooks/payments` reads. */
const val MAX_WEBHOOK_BODY: Int = 1 shl 20

/**
 * Reports whether [signature], the hex-encoded HMAC-SHA256 of [body], was made with any of [keys].
 * Accepting every key in the set is what lets a key be rotated: during the overlap the old and the
 * new key both work.
 */
fun verifyWebhook(keys: List<Secret>?, body: ByteArray, signature: String?): Boolean {
    val got = signature?.let(::parseHex) ?: return false
    var ok = false
    for (key in keys.orEmpty()) {
        // Check every key, so the time taken does not say which one matched.
        ok = MessageDigest.isEqual(hmacSha256(key.value, body), got) or ok
    }
    return ok
}

internal fun hmacSha256(key: String, body: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        doFinal(body)
    }

private fun parseHex(s: String): ByteArray? =
    if (s.length % 2 != 0 || s.any { Character.digit(it, 16) < 0 }) null
    else ByteArray(s.length / 2) { ((Character.digit(s[2 * it], 16) shl 4) + Character.digit(s[2 * it + 1], 16)).toByte() }
