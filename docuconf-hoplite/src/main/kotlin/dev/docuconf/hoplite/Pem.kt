package dev.docuconf.hoplite

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** PEM and private-key parsing with nothing but `java.security`. */
internal object Pem {
    data class Block(val type: String, val der: ByteArray)

    private val blockRegex = Regex("-----BEGIN ([A-Z0-9 ]+)-----([\\s\\S]*?)-----END \\1-----")

    fun blocks(text: String): List<Block> = blockRegex.findAll(text).map { m ->
        Block(m.groupValues[1], Base64.getMimeDecoder().decode(m.groupValues[2].trim()))
    }.toList()

    /** Every certificate in a PEM file. Throws on a malformed certificate. */
    fun certificates(text: String): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        return blocks(text).filter { it.type == "CERTIFICATE" }.map {
            cf.generateCertificate(ByteArrayInputStream(it.der)) as X509Certificate
        }
    }

    class UnsupportedKey(message: String) : Exception(message)

    /**
     * Reads the private key in a PEM file: PKCS#8 (`PRIVATE KEY`), PKCS#1 (`RSA PRIVATE KEY`) or
     * SEC 1 (`EC PRIVATE KEY`), the forms cert-manager and openssl write.
     */
    fun privateKey(text: String, certificateKey: PublicKey? = null): PrivateKey {
        val block = blocks(text).firstOrNull { it.type.endsWith("PRIVATE KEY") } ?: throw UnsupportedKey("no PEM private key found")
        return when (block.type) {
            "PRIVATE KEY" -> pkcs8(block.der)
            "RSA PRIVATE KEY" -> pkcs8(wrapPkcs8(RSA_OID, Der.nullValue(), block.der))
            "EC PRIVATE KEY" -> {
                // openssl writes the curve into the key; some writers leave it to the certificate.
                val curve = Der.sec1CurveOid(block.der)
                    ?: certificateKey?.takeIf { it.algorithm == "EC" }?.let { Der.spkiCurveOid(it.encoded) }
                    ?: throw UnsupportedKey("EC private key has no curve parameters")
                pkcs8(wrapPkcs8(EC_OID, curve, block.der))
            }
            "ENCRYPTED PRIVATE KEY" -> throw UnsupportedKey("encrypted private keys are not supported")
            else -> throw UnsupportedKey("unsupported key type ${block.type}")
        }
    }

    private fun pkcs8(der: ByteArray): PrivateKey {
        for (alg in listOf("RSA", "EC", "Ed25519", "EdDSA")) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(PKCS8EncodedKeySpec(der))
            } catch (_: Exception) {
                // try the next algorithm
            }
        }
        throw UnsupportedKey("the private key could not be parsed")
    }

    /** "RSA", "ECDSA" or "Ed25519": the contract's name for a key's algorithm. */
    fun algorithmName(key: PublicKey): String = when (key.algorithm) {
        "RSA" -> "RSA"
        "EC" -> "ECDSA"
        "Ed25519", "EdDSA" -> "Ed25519"
        else -> key.algorithm
    }

    /** Whether [key] is the private half of [publicKey]: sign random bytes and verify. */
    fun matches(key: PrivateKey, publicKey: PublicKey): Boolean {
        val keyAlg = when (key.algorithm) {
            "EdDSA" -> "Ed25519"
            else -> key.algorithm
        }
        if (algorithmName(publicKey) != (if (keyAlg == "EC") "ECDSA" else keyAlg)) return false
        val sigAlg = when (keyAlg) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            else -> "Ed25519"
        }
        return try {
            val data = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val sig = Signature.getInstance(sigAlg).run {
                initSign(key)
                update(data)
                sign()
            }
            Signature.getInstance(sigAlg).run {
                initVerify(publicKey)
                update(data)
                verify(sig)
            }
        } catch (_: Exception) {
            false
        }
    }

    private val RSA_OID = Der.oid("1.2.840.113549.1.1.1")
    private val EC_OID = Der.oid("1.2.840.10045.2.1")

    private fun wrapPkcs8(algOid: ByteArray, params: ByteArray, key: ByteArray): ByteArray =
        Der.seq(Der.tlv(0x02, byteArrayOf(0)), Der.seq(algOid, params), Der.tlv(0x04, key))
}

/** Just enough DER to wrap PKCS#1 and SEC 1 keys in PKCS#8. */
internal object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xff); out.write(n and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val bytes = ArrayList<Int>()
            var v = arc
            bytes += (v and 0x7f).toInt()
            v = v shr 7
            while (v > 0) {
                bytes += ((v and 0x7f) or 0x80).toInt()
                v = v shr 7
            }
            bytes.reversed().forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    /** The full TLV of the named-curve OID in an EC SubjectPublicKeyInfo, or null. */
    fun spkiCurveOid(der: ByteArray): ByteArray? {
        val (tag, start, _) = header(der, 0) ?: return null
        if (tag != 0x30) return null
        val (algTag, algStart, algLen) = header(der, start) ?: return null
        if (algTag != 0x30) return null
        val (oidTag, oidStart, oidLen) = header(der, algStart) ?: return null
        if (oidTag != 0x06) return null
        val next = oidStart + oidLen
        if (next >= algStart + algLen) return null
        val (pTag, pStart, pLen) = header(der, next) ?: return null
        return if (pTag == 0x06) der.copyOfRange(next, pStart + pLen) else null
    }

    /** The full TLV of the named-curve OID in a SEC 1 ECPrivateKey's `[0]` parameters, or null. */
    fun sec1CurveOid(der: ByteArray): ByteArray? {
        val (tag, start, len) = header(der, 0) ?: return null
        if (tag != 0x30) return null
        var i = start
        val end = start + len
        while (i < end) {
            val (t, s, l) = header(der, i) ?: return null
            if (t == 0xa0) {
                val (it2, is2, il2) = header(der, s) ?: return null
                if (it2 != 0x06) return null
                return der.copyOfRange(s, is2 + il2)
            }
            i = s + l
        }
        return null
    }

    private fun header(der: ByteArray, at: Int): Triple<Int, Int, Int>? {
        if (at + 2 > der.size) return null
        val tag = der[at].toInt() and 0xff
        var len = der[at + 1].toInt() and 0xff
        var i = at + 2
        if (len and 0x80 != 0) {
            val count = len and 0x7f
            if (count > 3 || i + count > der.size) return null
            len = 0
            repeat(count) { len = (len shl 8) or (der[i++].toInt() and 0xff) }
        }
        if (i + len > der.size) return null
        return Triple(tag, i, len)
    }
}
