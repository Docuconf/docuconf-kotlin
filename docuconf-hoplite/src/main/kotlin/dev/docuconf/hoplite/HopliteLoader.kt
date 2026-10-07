package dev.docuconf.hoplite

import com.sksamuel.hoplite.ClasspathResourceLoader
import com.sksamuel.hoplite.ConfigFailure
import com.sksamuel.hoplite.ConfigLoader
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.PropertySource
import com.sksamuel.hoplite.Undefined
import com.sksamuel.hoplite.decoder.Decoder
import com.sksamuel.hoplite.decoder.DecoderRegistry
import com.sksamuel.hoplite.fp.Validated
import com.sksamuel.hoplite.fp.valid
import com.sksamuel.hoplite.sources.ConfigFilePropertySource
import com.sksamuel.hoplite.sources.EnvironmentVariablesPropertySource
import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.Violation
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType

/** The Hoplite loader docuconf binds with, and the one it was made from. */
internal class BuiltLoader(val loader: ConfigLoader, val user: ConfigLoader)

/**
 * Builds the Hoplite loader that binds a config class: the user's own builder (from `withDocuconf()`)
 * or docuconf's default, with docuconf's sources in front and its decoders consulted first.
 */
internal object HopliteLoader {
    fun build(
        type: KClass<*>,
        userBuilder: ConfigLoaderBuilder?,
        hopliteConfig: ConfigLoaderBuilder.() -> Unit,
        front: List<PropertySource>,
        files: ConfigLoaderBuilder.() -> Unit,
        decoders: List<Decoder<*>>,
    ): BuiltLoader {
        val builder = userBuilder ?: ConfigLoaderBuilder.defaultWithoutPropertySources()
        // Before build(): Hoplite prints its sealed-type notice when the loader is constructed.
        if (!hasSealedTypes(type)) builder.explicitSealedTypes()
        builder.apply(hopliteConfig)
        val user = builder.build()
        val fileSources = ConfigLoaderBuilder.empty().explicitSealedTypes().apply(files).build().propertySources
        val sources = front + fileSources +
            // docuconf reads the environment itself, by declared name; Hoplite's environment source would
            // read the same variables under its own naming (`_` as nesting) and parse "" as a value.
            user.propertySources.filterNot { it is EnvironmentVariablesPropertySource }
        // An empty tree is fine: every variable may be unset and take its default.
        val loader = copy(user, mapOf("propertySources" to sources, "decoderRegistry" to Registry(decoders, user.decoderRegistry), "allowEmptyTree" to true))
        return BuiltLoader(loader, user)
    }

    /**
     * Rejects a value that the user's Hoplite loader reads from a config file of its own, when the
     * exported contract does not know it: export only reads [DocuconfService.baseSources], so the
     * platform would require a variable the app boots fine without, or document the wrong default.
     */
    fun checkUserFiles(user: ConfigLoader, vars: List<VarBinding>, settings: ServiceSettings) {
        val fileSources = user.propertySources.filterIsInstance<ConfigFilePropertySource>()
        if (fileSources.isEmpty()) return
        val loaded = copy(user, mapOf("propertySources" to fileSources, "allowEmptyTree" to true)).loadNode()
        // A file Hoplite cannot read is reported when it binds.
        val root: Node = if (loaded.isValid()) loaded.getUnsafe() else return
        val errors = vars.filter { !it.spec.secret }.mapNotNull { v ->
            val node = v.path.fold(root) { n, seg -> Docuconf.lookup(n, seg) }
            if (node is Undefined) return@mapNotNull null
            val value = try {
                Docuconf.baseValue(v.spec, node)
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null // Hoplite reports it when it binds.
            }
            if (v.spec.default == value) return@mapNotNull null
            val contract = v.spec.default?.let { "has default $it" } ?: "requires it"
            "${v.spec.name}: your Hoplite loader reads ${v.spec.configKey} = $value from ${fileSources.joinToString { it.source() }}, " +
                "but the exported contract $contract. Export only reads @DocuconfService(baseSources = [...]): " +
                "list ${if (fileSources.size == 1) "that file" else "those files"} there${if (settings.baseSources.isEmpty()) "" else " (now ${settings.baseSources})"}, so load and export agree."
        }
        if (errors.isNotEmpty()) throw DeclarationException(errors)
    }

    /**
     * Binds [type] and returns Hoplite's result, failures included, so they can be reported per
     * variable. Hoplite's `loadConfig(KClass, ...)` is `@PublishedApi internal` (its inline functions
     * call it), so it is part of Hoplite's binary API but not callable from Kotlin source.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> bind(loader: ConfigLoader, type: KClass<T>, classLoader: ClassLoader): Validated<ConfigFailure, T> {
        val m = ConfigLoader::class.java.getMethod(
            "loadConfig", KClass::class.java, List::class.java, List::class.java, ClasspathResourceLoader::class.java,
        )
        return try {
            m.invoke(loader, type, emptyList<Any>(), emptyList<String>(), with(ClasspathResourceLoader) { classLoader.toClasspathResourceLoader() }) as Validated<ConfigFailure, T>
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** A copy of [loader] with some constructor arguments replaced. */
    fun copy(loader: ConfigLoader, overrides: Map<String, Any?>): ConfigLoader {
        val ctor = ConfigLoader::class.primaryConstructor ?: error("Hoplite's ConfigLoader has no primary constructor")
        val props = ConfigLoader::class.memberProperties.associateBy { it.name }
        val args = ctor.parameters.mapNotNull { p ->
            when {
                p.name in overrides -> p to overrides[p.name]
                props[p.name] != null -> p to props.getValue(p.name!!).get(loader)
                p.isOptional -> null
                else -> error("cannot copy Hoplite's ConfigLoader: no property for ${p.name}; this Hoplite version is not supported")
            }
        }.toMap()
        return ctor.callBy(args)
    }

    /** docuconf's decoders first, then the user's. */
    private class Registry(private val first: List<Decoder<*>>, private val rest: DecoderRegistry) : DecoderRegistry {
        override val size: Int get() = first.size + rest.size

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> decoder(t: KClass<T>): Validated<ConfigFailure, Decoder<T>> =
            first.firstOrNull { it.supports(t.starProjectedType) }?.let { (it as Decoder<T>).valid() } ?: rest.decoder(t)

        override fun decoder(type: KType): Validated<ConfigFailure, Decoder<*>> =
            first.filter { it.supports(type) }.maxByOrNull { it.priority() }?.valid() ?: rest.decoder(type)
    }
}

/** Turns a Hoplite binding failure into violations keyed by variable name, with secrets removed. */
internal object HopliteFailures {
    fun violations(failure: ConfigFailure, type: KClass<*>, vars: List<VarBinding>, secrets: List<String>): List<Violation> {
        val byPath = vars.associateBy { it.path }
        val out = ArrayList<Violation>()
        fun name(path: List<String>) = byPath[path]?.spec?.name ?: path.joinToString(".").ifEmpty { type.simpleName ?: "config" }
        fun walk(f: ConfigFailure, path: List<String>) {
            when (f) {
                is ConfigFailure.DataClassFieldErrors -> f.errors.list.forEach { walk(it, path) }
                is ConfigFailure.MultipleFailures -> f.failures.list.forEach { walk(it, path) }
                is ConfigFailure.ParamFailure -> walk(f.error, path + (f.param.name ?: "?"))
                is ConfigFailure.MissingConfigValue -> out += Violation(
                    Codes.MISSING_REQUIRED, name(path),
                    if (path in byPath) "required, but not set" else "required, but no config source sets it (it is not in the contract, so the platform does not supply it)",
                )
                is ConfigFailure.InvalidConstructorParameters -> {
                    val cause = (f.e as? InvocationTargetException)?.targetException ?: f.e
                    out += Violation(Codes.INVALID_TYPE, name(path), "${(f.type.classifier as? KClass<*>)?.simpleName ?: f.type} rejected its values: ${cause.message ?: cause}")
                }
                else -> out += Violation(Codes.INVALID_TYPE, name(path), f.description().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" "))
            }
        }
        walk(failure, emptyList())
        return out.map { it.copy(message = Docuconf.redact(it.message, secrets)) }
    }
}
