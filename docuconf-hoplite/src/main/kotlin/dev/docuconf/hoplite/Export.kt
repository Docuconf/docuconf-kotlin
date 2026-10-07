@file:JvmName("Export")

package dev.docuconf.hoplite

import dev.docuconf.kotlin.core.DeclarationException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """usage: dev.docuconf.hoplite.Export --class <config class> [options]

Writes the contract of a config class (and optional Markdown docs). The service name, env prefix
and base config files come from the class's @DocuconfService annotation.

  --class <name>        the root config class, such as com.example.AppConfig (required)
  --service <name>      the service name, when the class has no @DocuconfService(name = ...)
  --app-version <v>     recorded as metadata.appVersion
  --out <file>          where to write contract.cue (default: stdout)
  --markdown <file>     also write Markdown docs of every input
  --check               do not write: exit 1 with a diff when --out (and --markdown) differ
                        from a fresh export, for CI
  --help                print this help"""

/**
 * Command-line export, for a Gradle `JavaExec` task, the `dev.docuconf` Gradle plugin or CI:
 *
 * ```
 * java -cp <app classpath> dev.docuconf.hoplite.Export \
 *   --class com.example.GatewayConfig [--service gateway] [--app-version 1.4.0] \
 *   [--out contract.cue] [--markdown CONFIG.md] [--check]
 * ```
 */
public fun main(args: Array<String>) {
    val status = export(args, System.out, System.err)
    if (status != 0) exitProcess(status)
}

/** Runs the export command; returns the exit status. */
internal fun export(args: Array<String>, out: PrintStream, err: PrintStream): Int {
    fun usage(message: String): Int {
        err.println("docuconf export: $message")
        err.println(USAGE)
        return 2
    }
    val opts = HashMap<String, MutableList<String>>()
    var check = false
    var i = 0
    while (i < args.size) {
        val key = args[i]
        when {
            key == "--help" || key == "-h" -> {
                out.println(USAGE)
                return 0
            }
            key == "--check" -> {
                check = true
                i++
            }
            key == "--prefix" || key == "--base" ->
                return usage("$key is no longer an option: set it once on the class, @DocuconfService(${if (key == "--prefix") "prefix" else "baseSources"} = ...), so boot and export read the same value")
            key.startsWith("--") && i + 1 < args.size -> {
                opts.getOrPut(key.removePrefix("--")) { ArrayList() } += args[i + 1]
                i += 2
            }
            else -> return usage("unexpected argument $key")
        }
    }
    val unknown = opts.keys - setOf("class", "service", "app-version", "out", "markdown")
    if (unknown.isNotEmpty()) return usage("unknown option --${unknown.first()}")
    val className = opts["class"]?.single() ?: return usage("--class is required")
    val service = opts["service"]?.single()
    val type = try {
        Class.forName(className).kotlin
    } catch (_: ClassNotFoundException) {
        return usage("class $className is not on the classpath")
    }
    val appVersion = opts["app-version"]?.single()
    val outFile = opts["out"]?.single()
    val markdownFile = opts["markdown"]?.single()
    if (check && outFile == null) return usage("--check needs --out, the committed contract to compare with")
    val outputs = try {
        listOfNotNull(
            (outFile ?: "-") to Docuconf.exportCue(type, service, appVersion) { warn = { err.println("docuconf: warning: $it") } },
            markdownFile?.let { it to Docuconf.exportMarkdown(type, service, appVersion) { warn = {} } },
        )
    } catch (e: DeclarationException) {
        err.println("docuconf: " + e.message)
        return 1
    }
    if (!check) {
        for ((file, text) in outputs) if (file == "-") out.print(text) else Files.writeString(Path.of(file), text)
        return 0
    }
    var stale = false
    for ((file, text) in outputs) {
        val path = Path.of(file)
        val current = if (Files.exists(path)) Files.readString(path) else null
        if (current == text) continue
        stale = true
        err.println("docuconf: $file is out of date with ${type.simpleName}; re-run the export and commit the result")
        err.print(LineDiff.unified(current ?: "", text, file))
    }
    return if (stale) 1 else 0
}

/** A minimal line diff for `--check` output: the changed lines, with a little context. */
internal object LineDiff {
    fun unified(old: String, new: String, name: String): String {
        val a = old.lines()
        val b = new.lines()
        // Longest common subsequence over lines; contracts are small.
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val sb = StringBuilder("--- $name (committed)\n+++ $name (fresh export)\n")
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { i++; j++ }
                i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> sb.append("-").append(a[i++]).append('\n')
                else -> sb.append("+").append(b[j++]).append('\n')
            }
        }
        return sb.toString()
    }
}
