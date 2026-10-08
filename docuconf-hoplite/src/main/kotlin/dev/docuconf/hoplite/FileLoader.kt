package dev.docuconf.hoplite

import com.sksamuel.hoplite.ConfigException
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ConfigResult
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.PropertySource
import com.sksamuel.hoplite.PropertySourceContext
import com.sksamuel.hoplite.Undefined
import com.sksamuel.hoplite.fp.valid
import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.ConfigFormat
import dev.docuconf.kotlin.core.Durations
import dev.docuconf.kotlin.core.FileSpec
import dev.docuconf.kotlin.core.FileType
import dev.docuconf.kotlin.core.JsonSchemaValidator
import dev.docuconf.kotlin.core.Re2
import dev.docuconf.kotlin.core.ValueChecks
import dev.docuconf.kotlin.core.Violation
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.util.Date
import kotlin.reflect.KClass

/**
 * Loads every file input and checks what the platform could not see before deploy (SPEC §11.2
 * item 7): presence, readability, size, format, schema, certificates and keystores.
 */
internal class FileLoader(
    private val env: Map<String, String>,
    private val fileRoot: String?,
    private val clock: Clock,
    private val classLoader: ClassLoader,
    private val hoplite: ConfigLoaderBuilder.() -> Unit,
) {
    val loaded = LinkedHashMap<String, Any>()
    val violations = ArrayList<Violation>()

    /** The path the app reads: `pathEnv` when set, then `DOCUCONF_FILE_ROOT` prepended to absolute paths. */
    fun resolve(spec: FileSpec): Path {
        val declared = spec.pathEnv?.let { env[it] }?.takeIf { it.isNotEmpty() } ?: spec.path
        val rooted = if (!fileRoot.isNullOrEmpty() && declared.startsWith("/")) fileRoot.trimEnd('/') + declared else declared
        return Path.of(rooted)
    }

    fun load(binding: FileBinding) {
        val spec = binding.spec
        val path = resolve(spec)
        val exists = if (spec.type == FileType.TLS) Files.isDirectory(path) else Files.isRegularFile(path)
        if (!exists) {
            if (spec.required) {
                val what = if (spec.type == FileType.TLS) "directory" else "file"
                add(Codes.FILE_MISSING, spec, "$what $path does not exist")
            }
            return
        }
        if (spec.type != FileType.TLS && !Files.isReadable(path)) {
            unreadable(spec, path)
            return
        }
        try {
            if (spec.type != FileType.TLS && spec.maxSize != null && Files.size(path) > spec.maxSize!!) {
                add(Codes.FILE_TOO_LARGE, spec, "$path is ${Files.size(path)} bytes, larger than maxSize ${spec.maxSize}")
                return
            }
            when (spec.type) {
                FileType.CONFIG -> config(binding, path)
                FileType.TLS -> tls(spec, path)
                FileType.CA_BUNDLE -> caBundle(spec, path)
                FileType.KEYSTORE -> keystore(spec, path)
                FileType.TEXT -> text(spec, path)
                FileType.BINARY -> loaded[spec.name] = BinaryFile(path)
            }
        } catch (e: AccessDeniedException) {
            unreadable(spec, Path.of(e.file ?: path.toString()))
        } catch (e: java.io.IOException) {
            add(Codes.FILE_UNREADABLE, spec, "$path could not be read: ${e.javaClass.simpleName}")
        }
    }

    private fun add(code: String, spec: FileSpec, message: String) {
        violations += Violation(code, spec.name, message)
    }

    private fun unreadable(spec: FileSpec, path: Path) = add(
        Codes.FILE_UNREADABLE, spec,
        "$path is not readable by this process. Secret volumes are owned by root; a non-root container needs the pod's fsGroup set.",
    )

    private fun config(binding: FileBinding, path: Path) {
        val spec = binding.spec
        val format = spec.format!!
        val parser = parserFor(format, classLoader) ?: error("no Hoplite parser for ${format.wire}; add ${parserModule(format)}")
        val node: Node = try {
            Files.newInputStream(path).use { parser.load(it, path.toString()) }
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: Exception) {
            add(Codes.FILE_MALFORMED, spec, "$path is not valid ${format.wire}" + if (spec.isSecret) "" else ": ${firstLine(e.message)}")
            return
        }
        if (node is Undefined) {
            add(Codes.FILE_MALFORMED, spec, "$path is empty")
            return
        }
        spec.schema?.let { schema ->
            val problems = JsonSchemaValidator.validate(schema, toJson(node), lenientScalars = format == ConfigFormat.YAML)
            if (problems.isNotEmpty()) {
                problems.forEach { add(Codes.SCHEMA_MISMATCH, spec, "$path: ${if (spec.isSecret) it.path + " does not match the schema" else it.toString()}") }
                return
            }
        }
        val kclass = binding.valueType!!.classifier as KClass<*>
        val source = object : PropertySource {
            override fun node(context: PropertySourceContext): ConfigResult<Node> = node.valid()
            override fun source(): String = path.toString()
        }
        val value = try {
            ConfigLoaderBuilder.defaultWithoutPropertySources()
                .addDecoders(valueDecoders())
                .apply { if (!hasSealedTypes(kclass)) explicitSealedTypes() }
                .apply(hoplite)
                .addPropertySource(source)
                .build()
                .loadConfigOrThrow(kclass, emptyList())
        } catch (e: ConfigException) {
            add(Codes.SCHEMA_MISMATCH, spec, "$path does not bind to ${kclass.simpleName}" + if (spec.isSecret) "" else ": ${e.message?.trim()}")
            return
        }
        loaded[spec.name] = ConfigFile(path, value)
    }

    private fun text(spec: FileSpec, path: Path) {
        val bytes = Files.readAllBytes(path)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            add(Codes.FILE_MALFORMED, spec, "$path is not UTF-8 text")
            return
        }
        val n = ValueChecks.codePointCount(text)
        var ok = true
        spec.minLength?.let { if (n < it) { ok = false; add(Codes.OUT_OF_RANGE, spec, "$path is $n characters, shorter than minLength $it") } }
        spec.maxLength?.let { if (n > it) { ok = false; add(Codes.OUT_OF_RANGE, spec, "$path is $n characters, longer than maxLength $it") } }
        spec.pattern?.let { if (!Re2.matches(it, text)) { ok = false; add(Codes.PATTERN_MISMATCH, spec, "$path does not match pattern $it") } }
        if (ok) loaded[spec.name] = TextFile(path, text, spec.isSecret)
    }

    private fun caBundle(spec: FileSpec, path: Path) {
        val certs = try {
            Pem.certificates(Files.readString(path))
        } catch (e: java.security.cert.CertificateException) {
            add(Codes.FILE_MALFORMED, spec, "$path contains a malformed certificate")
            return
        } catch (e: IllegalArgumentException) {
            add(Codes.FILE_MALFORMED, spec, "$path contains a malformed certificate")
            return
        }
        val min = spec.minCertificates ?: 1
        if (certs.size < min) {
            add(Codes.FILE_MALFORMED, spec, "$path holds ${certs.size} certificate${if (certs.size == 1) "" else "s"}; at least $min required")
            return
        }
        loaded[spec.name] = CaBundle(path, certs)
    }

    private fun keystore(spec: FileSpec, path: Path) {
        val password = spec.passwordVar?.let { env[it] }
        val ks = try {
            KeyStore.getInstance(if (spec.keystoreFormat?.wire == "jks") "JKS" else "PKCS12").apply {
                Files.newInputStream(path).use { load(it, password?.toCharArray()) }
            }
        } catch (e: java.io.IOException) {
            if (e is AccessDeniedException) throw e
            add(Codes.KEYSTORE_UNREADABLE, spec, "$path does not open as ${spec.keystoreFormat?.wire} with ${spec.passwordVar ?: "an empty password"}")
            return
        } catch (e: java.security.GeneralSecurityException) {
            add(Codes.KEYSTORE_UNREADABLE, spec, "$path does not open as ${spec.keystoreFormat?.wire} with ${spec.passwordVar ?: "an empty password"}")
            return
        }
        loaded[spec.name] = Keystore(path, ks)
    }

    private fun tls(spec: FileSpec, dir: Path) {
        val certPath = dir.resolve("tls.crt")
        val keyPath = dir.resolve("tls.key")
        val caPath = dir.resolve("ca.crt")
        val needed = if (spec.requireCA) listOf(certPath, keyPath, caPath) else listOf(certPath, keyPath)
        var missing = false
        for (p in needed) {
            if (!Files.isRegularFile(p)) {
                add(Codes.FILE_MISSING, spec, "$p does not exist")
                missing = true
            } else if (!Files.isReadable(p)) {
                unreadable(spec, p)
                missing = true
            }
        }
        if (missing) return

        val chain = try {
            Pem.certificates(Files.readString(certPath))
        } catch (e: Exception) {
            if (e is java.io.IOException) throw e
            emptyList()
        }
        if (chain.isEmpty()) {
            add(Codes.CERTIFICATE_INVALID, spec, "$certPath is not a PEM certificate")
            return
        }
        val leaf = chain.first()
        val key = try {
            Pem.privateKey(Files.readString(keyPath), leaf.publicKey)
        } catch (e: Pem.UnsupportedKey) {
            add(Codes.KEY_MISMATCH, spec, "$keyPath: ${e.message}")
            null
        }
        if (key != null && !Pem.matches(key, leaf.publicKey)) {
            add(Codes.KEY_MISMATCH, spec, "$keyPath is not the private key for the certificate in tls.crt")
        }

        val now = clock.instant()
        val notBefore = leaf.notBefore.toInstant()
        val notAfter = leaf.notAfter.toInstant()
        when {
            now.isBefore(notBefore) -> add(Codes.CERTIFICATE_INVALID, spec, "the certificate is not valid until $notBefore")
            now.isAfter(notAfter) -> add(Codes.CERTIFICATE_INVALID, spec, "the certificate expired at $notAfter")
            spec.minRemaining != null -> {
                val min = Duration.ofNanos(Durations.parseGo(spec.minRemaining!!)!!)
                if (Duration.between(now, notAfter) < min) {
                    add(Codes.CERTIFICATE_EXPIRING, spec, "the certificate expires at $notAfter, with less than the required ${spec.minRemaining} left")
                }
            }
        }

        val sans = dnsNames(leaf)
        for (name in spec.dnsNames.orEmpty()) {
            if (sans.none { covers(it, name) }) {
                add(Codes.CERTIFICATE_NAME_MISMATCH, spec, "the certificate does not cover $name (it covers ${sans.joinToString(", ").ifEmpty { "no DNS names" }})")
            }
        }

        spec.keyAlgorithms?.let { allowed ->
            val alg = Pem.algorithmName(leaf.publicKey)
            if (allowed.none { it.wire == alg }) {
                add(Codes.CERTIFICATE_INVALID, spec, "the certificate has a $alg key; allowed: ${allowed.joinToString(", ") { it.wire }}")
            }
        }

        var ca = emptyList<X509Certificate>()
        if (Files.isRegularFile(caPath)) {
            ca = try {
                Pem.certificates(Files.readString(caPath))
            } catch (e: Exception) {
                if (e is java.io.IOException) throw e
                emptyList()
            }
        }
        if (spec.requireCA) {
            if (ca.isEmpty()) {
                add(Codes.CERTIFICATE_INVALID, spec, "$caPath holds no PEM certificates")
            } else {
                chainError(chain, ca, Date.from(now))?.let { add(Codes.CERTIFICATE_INVALID, spec, "the certificate does not chain to ca.crt: $it") }
            }
        }
        if (key != null) loaded[spec.name] = TlsKeyPair(dir, chain, key, ca)
    }

    companion object {
        fun dnsNames(cert: X509Certificate): List<String> =
            cert.subjectAlternativeNames.orEmpty().filter { it[0] == 2 }.map { it[1] as String }

        /** Whether a certificate name covers [name]. A wildcard covers exactly one leftmost label. */
        fun covers(certName: String, name: String): Boolean {
            val c = certName.lowercase().trimEnd('.')
            val n = name.lowercase().trimEnd('.')
            if (c == n) return true
            if (c.startsWith("*.") && !n.startsWith("*.")) {
                val dot = n.indexOf('.')
                return dot > 0 && n.substring(dot + 1) == c.substring(2)
            }
            return false
        }

        /** Null when [chain] (leaf first) validates against [ca] with PKIX, else the reason. */
        fun chainError(chain: List<X509Certificate>, ca: List<X509Certificate>, at: Date): String? = try {
            val anchors = ca.map { TrustAnchor(it, null) }.toSet()
            val path = chain.filter { c -> ca.none { it == c } }
            val certPath = CertificateFactory.getInstance("X.509").generateCertPath(path)
            val params = PKIXParameters(anchors).apply {
                isRevocationEnabled = false
                date = at
            }
            CertPathValidator.getInstance("PKIX").validate(certPath, params)
            null
        } catch (e: Exception) {
            firstLine(e.message) ?: e.javaClass.simpleName
        }

        fun firstLine(s: String?): String? = s?.lineSequence()?.firstOrNull()?.trim()
    }
}

