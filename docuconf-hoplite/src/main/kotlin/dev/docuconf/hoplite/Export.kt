@file:JvmName("Export")

package dev.docuconf.hoplite

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Command-line export, for a Gradle `JavaExec` task or CI:
 *
 * ```
 * java -cp <app classpath> dev.docuconf.hoplite.Export \
 *   --class com.example.GatewayConfig --service gateway [--app-version 1.4.0] \
 *   [--prefix APP_] [--base /application.yaml] [--out contract.cue] [--markdown CONFIG.md]
 * ```
 */
public fun main(args: Array<String>) {
    val opts = HashMap<String, MutableList<String>>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (!key.startsWith("--") || i + 1 >= args.size) usage("unexpected argument $key")
        opts.getOrPut(key.removePrefix("--")) { ArrayList() } += args[i + 1]
        i += 2
    }
    val className = opts["class"]?.single() ?: usage("--class is required")
    val service = opts["service"]?.single() ?: usage("--service is required")
    val type = try {
        Class.forName(className).kotlin
    } catch (_: ClassNotFoundException) {
        usage("class $className is not on the classpath")
    }
    val configure: DocuconfOptions.() -> Unit = {
        opts["prefix"]?.single()?.let { prefix = it }
        baseSources = opts["base"].orEmpty()
    }
    val appVersion = opts["app-version"]?.single()
    val cue = Docuconf.exportCue(type, service, appVersion, configure)
    val out = opts["out"]?.single()
    if (out == null) print(cue) else Files.writeString(Path.of(out), cue)
    opts["markdown"]?.single()?.let { Files.writeString(Path.of(it), Docuconf.exportMarkdown(type, service, appVersion, configure)) }
}

private fun usage(message: String): Nothing {
    System.err.println("docuconf export: $message")
    System.err.println("usage: --class <config class> --service <name> [--app-version v] [--prefix P] [--base /application.yaml] [--out contract.cue] [--markdown CONFIG.md]")
    exitProcess(2)
}
