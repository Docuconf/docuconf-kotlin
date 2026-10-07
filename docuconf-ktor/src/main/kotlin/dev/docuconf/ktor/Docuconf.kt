package dev.docuconf.ktor

import dev.docuconf.hoplite.Docuconf
import dev.docuconf.hoplite.DocuconfOptions
import io.ktor.server.application.Application
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.util.AttributeKey

/** Where the loaded config is kept on the [Application]. */
@PublishedApi
internal val DocuconfConfigKey: AttributeKey<Any> = AttributeKey("dev.docuconf.config")

/**
 * Loads and checks the config before anything starts, then starts [factory] on the port the config
 * declares. On a bad environment it prints every problem, writes the termination log and exits with
 * status 1 ([Docuconf.loadOrExit]), so the server never half-starts.
 *
 * ```
 * fun main() {
 *     docuconfServer(Netty, port = AppConfig::port) { config -> routes(config) }.start(wait = true)
 * }
 * ```
 *
 * Inside the module, the config is also available as [docuconfConfig].
 */
public inline fun <reified T : Any, TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> docuconfServer(
    factory: ApplicationEngineFactory<TEngine, TConfiguration>,
    noinline port: (T) -> Int,
    host: String = "0.0.0.0",
    noinline options: DocuconfOptions.() -> Unit = {},
    noinline module: Application.(T) -> Unit,
): EmbeddedServer<TEngine, TConfiguration> {
    val config = Docuconf.loadOrExit<T>(options)
    return docuconfServer(factory, config, port(config), host, module)
}

/** Starts [factory] with a config already loaded (for example with `Docuconf.load { env = ... }` in a test). */
public fun <T : Any, TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> docuconfServer(
    factory: ApplicationEngineFactory<TEngine, TConfiguration>,
    config: T,
    port: Int,
    host: String = "0.0.0.0",
    module: Application.(T) -> Unit,
): EmbeddedServer<TEngine, TConfiguration> = embeddedServer(factory, port = port, host = host) {
    attributes.put(DocuconfConfigKey, config)
    module(config)
}

/**
 * The application's config. In a module started by [docuconfServer] it is the config loaded at
 * startup. Otherwise (`EngineMain`, `testApplication`) it is loaded here once, with [options], and
 * kept for the next call: in `main` a bad environment exits with status 1, as [Docuconf.loadOrExit].
 *
 * ```
 * fun Application.module() {
 *     val config = docuconfConfig<AppConfig>()
 * }
 * ```
 */
public inline fun <reified T : Any> Application.docuconfConfig(noinline options: DocuconfOptions.() -> Unit = {}): T {
    attributes.getOrNull(DocuconfConfigKey)?.let { return it as T }
    return Docuconf.loadOrExit<T>(options).also { attributes.put(DocuconfConfigKey, it) }
}

/** Puts an already loaded config on the application, for `testApplication { application { ... } }`. */
public fun <T : Any> Application.provideDocuconfConfig(config: T) {
    attributes.put(DocuconfConfigKey, config)
}
