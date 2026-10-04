package dev.docuconf.hoplite

import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory

/**
 * A structured config file (JSON, YAML or TOML) bound to [T] by Hoplite. Its JSON Schema in the
 * contract is generated from [T], so the platform checks the file against the same type.
 */
public class ConfigFile<out T : Any> internal constructor(
    /** Where the file was read from, after `pathEnv` and `DOCUCONF_FILE_ROOT`. */
    public val path: Path,
    /** The bound value. */
    public val value: T,
) {
    override fun toString(): String = "ConfigFile($path)"
}

/**
 * A TLS key pair in the `kubernetes.io/tls` layout: a directory with `tls.crt`, `tls.key` and,
 * when required, `ca.crt`. Checked at boot: parse, key match, validity, names, algorithm, chain.
 */
public class TlsKeyPair internal constructor(
    public val directory: Path,
    /** The leaf certificate first, then any intermediates from `tls.crt`. */
    public val certificateChain: List<X509Certificate>,
    public val privateKey: PrivateKey,
    /** Certificates from `ca.crt`, or empty when it is absent. */
    public val ca: List<X509Certificate>,
) {
    public val certificate: X509Certificate get() = certificateChain.first()

    /** An in-memory PKCS#12 key store holding this key pair under [alias], for Ktor's `sslConnector` and friends. */
    public fun toKeyStore(alias: String = "tls", password: CharArray = CharArray(0)): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(alias, privateKey, password, certificateChain.toTypedArray())
        }

    /** A [KeyManagerFactory] for this key pair. */
    public fun keyManagerFactory(): KeyManagerFactory {
        val password = CharArray(0)
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(toKeyStore(password = password), password) }
    }

    override fun toString(): String = "TlsKeyPair($directory)"
}

/** One or more PEM CA certificates. */
public class CaBundle internal constructor(
    public val path: Path,
    public val certificates: List<X509Certificate>,
) {
    /** A key store holding the certificates as trusted entries. */
    public fun toTrustStore(): KeyStore = KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        certificates.forEachIndexed { i, c -> setCertificateEntry("ca-$i", c) }
    }

    /** A [TrustManagerFactory] trusting exactly these certificates. */
    public fun trustManagerFactory(): TrustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(toTrustStore()) }

    override fun toString(): String = "CaBundle($path, ${certificates.size} certificates)"
}

/** A PKCS#12 or JKS key store, opened with its password variable. */
public class Keystore internal constructor(
    public val path: Path,
    public val keyStore: KeyStore,
) {
    override fun toString(): String = "Keystore($path)"
}

/** A text file, such as a licence key. Read as UTF-8 and never trimmed. */
public class TextFile internal constructor(
    public val path: Path,
    private val content: String,
    private val secret: Boolean,
) {
    public val text: String get() = content

    override fun toString(): String = if (secret) "TextFile($path, ****)" else "TextFile($path)"
}

/** Opaque bytes, such as a GeoIP database. Only its size is checked; it is not read into memory. */
public class BinaryFile internal constructor(public val path: Path) {
    public fun readBytes(): ByteArray = path.toFile().readBytes()

    override fun toString(): String = "BinaryFile($path)"
}

/**
 * A structured value in one environment variable, sent as compact JSON (contract type `json`).
 * Its JSON Schema is generated from [T]. Hoplite binds the parsed JSON to [T].
 */
public data class Json<out T : Any>(val value: T)
