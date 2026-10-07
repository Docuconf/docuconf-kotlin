package dev.docuconf.hoplite

import com.sksamuel.hoplite.ConfigLoaderBuilder
import kotlin.reflect.KClass

/**
 * Adds docuconf to a Hoplite loader you already have. Your sources, decoders, preprocessors and
 * settings stay; docuconf checks every declared variable and file input first, reads the environment
 * by declared name (replacing Hoplite's environment source, see below), and reports every problem at
 * once.
 *
 * ```
 * val config = ConfigLoaderBuilder.default()
 *     .addResourceSource("/application.yaml")
 *     .withDocuconf()
 *     .build()
 *     .loadConfigOrThrow<AppConfig>()
 * ```
 *
 * - Hoplite's own environment source is left out: docuconf reads each variable by its declared name
 *   (`logLevel` from `LOG_LEVEL`) and hands the value to Hoplite. Other sources (system properties,
 *   files) keep their order, below docuconf's environment values and overlays.
 * - Config files your loader reads count as files baked into the image, so the contract must know
 *   them: a value read from one that the exported contract does not have as its default is a
 *   declaration error asking you to list the file in [DocuconfService.baseSources].
 */
public fun ConfigLoaderBuilder.withDocuconf(configure: DocuconfOptions.() -> Unit = {}): DocuconfLoaderBuilder =
    DocuconfLoaderBuilder(this, DocuconfOptions().apply(configure))

/** A Hoplite [ConfigLoaderBuilder] with docuconf added. Call [build], as with Hoplite. */
public class DocuconfLoaderBuilder internal constructor(
    private val builder: ConfigLoaderBuilder,
    private val options: DocuconfOptions,
) {
    /** The loader. Building does nothing yet: the class to load is only known at `loadConfigOrThrow`. */
    public fun build(): DocuconfConfigLoader = DocuconfConfigLoader(builder, options)
}

/** Loads config classes through docuconf and the user's Hoplite builder. */
public class DocuconfConfigLoader internal constructor(
    private val builder: ConfigLoaderBuilder,
    private val options: DocuconfOptions,
) {
    /** Loads [T], or throws [dev.docuconf.kotlin.core.ConfigViolationException] listing every problem. */
    public inline fun <reified T : Any> loadConfigOrThrow(): T = loadConfigOrThrow(T::class)

    /** Loads [type], or throws [dev.docuconf.kotlin.core.ConfigViolationException] listing every problem. */
    public fun <T : Any> loadConfigOrThrow(type: KClass<T>): T = Docuconf.load(type, options, builder)

    /** Checks and loads [T] without throwing for configuration problems. */
    public inline fun <reified T : Any> loadConfig(): LoadResult<T> = loadConfig(T::class)

    /** Checks and loads [type] without throwing for configuration problems. */
    public fun <T : Any> loadConfig(type: KClass<T>): LoadResult<T> = Docuconf.check(type, options, builder)

    /** Loads [T], or prints every problem, writes the termination log and exits with status 1. */
    public inline fun <reified T : Any> loadOrExit(): T = loadOrExit(T::class)

    /** Loads [type], or prints every problem, writes the termination log and exits with status 1. */
    public fun <T : Any> loadOrExit(type: KClass<T>): T = Docuconf.loadOrExit(type, options, builder)
}
