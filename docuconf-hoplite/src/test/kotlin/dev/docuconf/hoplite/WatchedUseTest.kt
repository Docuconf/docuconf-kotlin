package dev.docuconf.hoplite

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.Principal
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The README's "Using a watched value" snippets, compiled and run here (ReadmeTest checks they match).

data class ServingConfig(
    @Doc("Certificate served on HTTPS")
    @FileInput(name = "serving-tls", path = "/etc/app/tls")
    @Tls(dnsNames = ["localhost"])
    val tls: Watched<TlsKeyPair>,
    @Doc("CA bundle the partner API's certificate chains to")
    @FileInput(name = "partner-ca", path = "/etc/app/partner-ca/ca.crt")
    val partnerCa: Watched<CaBundle>,
)

/** Serves the current key pair: the hook swaps in a renewed one, and each handshake reads it. */
class ReloadingKeyManager(tls: Watched<TlsKeyPair>) : X509ExtendedKeyManager() {
    @Volatile private var current = keyManager(tls.current())
    private val subscription = tls.onChange { current = keyManager(it) }

    private fun keyManager(pair: TlsKeyPair) = pair.keyManagerFactory().keyManagers.single() as X509ExtendedKeyManager

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<Principal>?, engine: SSLEngine?) =
        current.chooseEngineServerAlias(keyType, issuers, engine)
    override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?) =
        current.chooseServerAlias(keyType, issuers, socket)
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = current.getCertificateChain(alias)
    override fun getPrivateKey(alias: String?) = current.getPrivateKey(alias)
    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?) = current.getServerAliases(keyType, issuers)
    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?) = null
    override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?) = null

    fun close() = subscription.close()
}

/** Calls the partner API trusting the current CA bundle: the hook rebuilds the client. */
class PartnerClient(ca: Watched<CaBundle>) {
    private val client = AtomicReference(build(ca.current()))
    private val subscription = ca.onChange { client.set(build(it)) }

    private fun build(bundle: CaBundle): HttpClient = HttpClient.newBuilder()
        .sslContext(SSLContext.getInstance("TLS").apply { init(null, bundle.trustManagerFactory().trustManagers, null) })
        .build()

    fun get(uri: URI): HttpResponse<String> =
        client.get().send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString())

    fun close() = subscription.close()
}

/** A health check's view of a watched input. */
fun reloadHealth(name: String, input: Watched<*>): Map<String, Any?> {
    val status = input.status
    return mapOf(
        "$name.generation" to status.generation,
        "$name.lastReload" to status.lastReload,
        "$name.rejectedCodes" to status.lastRejected?.codes,
    )
}

class WatchedUseTest {
    @Test
    fun aRenewedCertificateIsServedAndTrusted(@TempDir root: Path) {
        val tlsDir = Projected(root.resolve("etc/app/tls"))
        val caDir = Projected(root.resolve("etc/app/partner-ca"))
        fun rotate(): Certs.Issued {
            val ca = Certs.issue(Certs.ec(), listOf("ca.test"), ca = true)
            val leaf = Certs.issue(Certs.ec(), listOf("localhost"), issuer = ca)
            tlsDir.update(mapOf("tls.crt" to Certs.pem(leaf.cert), "tls.key" to Certs.pkcs8(leaf.keys)))
            caDir.update(mapOf("ca.crt" to Certs.pem(ca.cert)))
            return leaf
        }
        val first = rotate()
        val config = Docuconf.load<ServingConfig> {
            env = mapOf("DOCUCONF_FILE_ROOT" to root.toString())
            reloadInterval = Duration.ofHours(1)
        }

        val keyManager = ReloadingKeyManager(config.tls)
        val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), null, null) }
        val server = HttpsServer.create(InetSocketAddress("localhost", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(ssl)
            createContext("/") { it.sendResponseHeaders(200, 2); it.responseBody.use { b -> b.write("ok".toByteArray()) } }
            start()
        }
        val partner = PartnerClient(config.partnerCa)
        try {
            val uri = URI("https://localhost:${server.address.port}/")
            fun served(): X509Certificate {
                val r = partner.get(uri)
                assertEquals(200, r.statusCode())
                return r.sslSession().get().peerCertificates.first() as X509Certificate
            }
            assertEquals(first.cert, served())

            // A new CA and a leaf it signed: the server serves the leaf, the client trusts the CA.
            val second = rotate()
            assertTrue(config.tls.refresh())
            assertTrue(config.partnerCa.refresh())
            assertEquals(second.cert, served())

            val health = reloadHealth("serving-tls", config.tls)
            assertEquals(2L, health["serving-tls.generation"])
            assertNull(health["serving-tls.rejectedCodes"])
        } finally {
            server.stop(0)
            keyManager.close()
            partner.close()
        }
    }
}
