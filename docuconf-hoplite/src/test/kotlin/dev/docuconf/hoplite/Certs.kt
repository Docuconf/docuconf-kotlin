package dev.docuconf.hoplite

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.sec.ECPrivateKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.StringWriter
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/** Mints keys and certificates for tests. */
object Certs {
    private val serial = AtomicLong(1)

    fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    fun ec(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    fun ed25519(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    data class Issued(val cert: X509Certificate, val keys: KeyPair)

    fun issue(
        keys: KeyPair,
        dnsNames: List<String> = listOf("localhost"),
        notBefore: Instant = Instant.now().minus(1, ChronoUnit.DAYS),
        notAfter: Instant = Instant.now().plus(90, ChronoUnit.DAYS),
        issuer: Issued? = null,
        ca: Boolean = false,
        cn: String = dnsNames.firstOrNull() ?: "test",
    ): Issued {
        val subject = X500Name("CN=$cn")
        val b = JcaX509v3CertificateBuilder(
            issuer?.let { X500Name(it.cert.subjectX500Principal.name) } ?: subject,
            BigInteger.valueOf(serial.incrementAndGet()),
            Date.from(notBefore), Date.from(notAfter), subject, keys.public,
        )
        if (dnsNames.isNotEmpty()) {
            b.addExtension(Extension.subjectAlternativeName, false, GeneralNames(dnsNames.map { GeneralName(GeneralName.dNSName, it) }.toTypedArray()))
        }
        if (ca) b.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        val signingKey = issuer?.keys?.private ?: keys.private
        val alg = when (signingKey.algorithm) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            else -> "Ed25519"
        }
        val cert = JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder(alg).build(signingKey)))
        return Issued(cert, keys)
    }

    fun pem(cert: X509Certificate): String = pemBlock("CERTIFICATE", cert.encoded)

    /** PKCS#8 `PRIVATE KEY`. */
    fun pkcs8(keys: KeyPair): String = pemBlock("PRIVATE KEY", keys.private.encoded)

    /** The traditional OpenSSL form: PKCS#1 `RSA PRIVATE KEY` or SEC 1 `EC PRIVATE KEY`, as cert-manager writes. */
    fun traditional(keys: KeyPair): String = StringWriter().also { w -> JcaPEMWriter(w).use { it.writeObject(keys) } }.toString()

    /** SEC 1 `EC PRIVATE KEY` with the curve parameters included, as openssl writes it. */
    fun sec1WithCurve(keys: KeyPair): String {
        val info = PrivateKeyInfo.getInstance(keys.private.encoded)
        val inner = ECPrivateKey.getInstance(info.parsePrivateKey())
        val withParams = ECPrivateKey(256, inner.key, info.privateKeyAlgorithm.parameters)
        return pemBlock("EC PRIVATE KEY", withParams.encoded)
    }

    private fun pemBlock(type: String, der: ByteArray) =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

    /** Writes a kubernetes.io/tls directory. */
    fun writeTls(dir: Path, leaf: Issued, keyPem: String = pkcs8(leaf.keys), chain: List<X509Certificate> = emptyList(), ca: X509Certificate? = null): Path {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("tls.crt"), (listOf(leaf.cert) + chain).joinToString("") { pem(it) })
        Files.writeString(dir.resolve("tls.key"), keyPem)
        ca?.let { Files.writeString(dir.resolve("ca.crt"), pem(it)) }
        return dir
    }

    fun writePkcs12(file: Path, issued: Issued, password: String) {
        Files.createDirectories(file.parent)
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("client", issued.keys.private, password.toCharArray(), arrayOf(issued.cert))
        }
        Files.newOutputStream(file).use { ks.store(it, password.toCharArray()) }
    }
}
