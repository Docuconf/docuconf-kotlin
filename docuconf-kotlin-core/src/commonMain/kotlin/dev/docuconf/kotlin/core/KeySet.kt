package dev.docuconf.kotlin.core

/**
 * A set of secret keys that are all valid at once, so a key can be rotated without an outage
 * (contract type `keySet`, SPEC §4.3 and §6.1). It is for the side that verifies: webhook
 * signatures, inbound API keys, JWT HMAC verification, cookie-signing fallbacks.
 *
 * The platform supplies it like a secret list: one Secret key holding `old,new` during a rotation.
 * A rotation takes three steps: add the new key and roll out; switch the sender to the new key;
 * remove the old key and roll out.
 *
 * A key set is always secret: [toString] prints `KeySet(****)`, and docuconf never puts a key in an
 * error message. Keys are never trimmed and keep the order the platform gave them.
 */
public class KeySet(keys: List<String>) {
    private val items: List<String> = keys.toList()

    /** The keys, in the order the platform gave them. Handle them as secrets: never log them. */
    public val keys: List<String> get() = items

    /** The number of keys. */
    public val size: Int get() = items.size

    /**
     * Whether [candidate] is one of the keys, such as an API key a caller presents. It compares
     * [candidate] with every key in constant time, so the time taken does not say which key matched,
     * or how much of one; it depends only on the number of keys and the lengths involved.
     */
    public fun contains(candidate: String): Boolean {
        val c = candidate.encodeToByteArray()
        var found = 0
        for (key in items) found = found or constantTimeEquals(key.encodeToByteArray(), c)
        return found == 1
    }

    /**
     * Calls [check] with each key, as UTF-8 bytes, and returns whether any call returned true. Use it
     * for checks that need the key itself, such as an HMAC:
     *
     * ```
     * val ok = config.webhookKeys.verify { key ->
     *     val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
     *     MessageDigest.isEqual(mac.doFinal(body), signature)
     * }
     * ```
     *
     * Every key is tried, even after one matches, so the time taken does not say which key matched.
     * [check] should compare in constant time itself, as `MessageDigest.isEqual` does.
     */
    public fun verify(check: (key: ByteArray) -> Boolean): Boolean {
        var ok = false
        for (key in items) {
            if (check(key.encodeToByteArray())) ok = true
        }
        return ok
    }

    /** `KeySet(****)`: the keys are secret. */
    override fun toString(): String = "KeySet(****)"

    override fun equals(other: Any?): Boolean = other is KeySet && other.items == items

    override fun hashCode(): Int = items.hashCode()

    private companion object {
        /** 1 when [a] and [b] are equal, else 0, in time that depends only on their lengths. */
        fun constantTimeEquals(a: ByteArray, b: ByteArray): Int {
            var diff = a.size xor b.size
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val x = if (i < a.size) a[i].toInt() else 0
                val y = if (i < b.size) b[i].toInt() else 0
                diff = diff or (x xor y)
            }
            return if (diff == 0) 1 else 0
        }
    }
}
