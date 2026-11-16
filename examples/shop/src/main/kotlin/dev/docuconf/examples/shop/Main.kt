package dev.docuconf.examples.shop

import dev.docuconf.hoplite.Docuconf
import dev.docuconf.hoplite.checkContract
import dev.docuconf.kotlin.core.ConfigViolationException
import dev.docuconf.kotlin.core.ContractFirst
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

fun main() {
    exitProcess(run(System.getenv(), System.out, System.err))
}

/**
 * Checks [env] against the contract (with file inputs under `DOCUCONF_FILE_ROOT`) and prints the
 * role's one-line summary to [out]: 0. On a bad environment it prints every problem to [err] (never
 * a secret value), writes them to the termination log, as `Docuconf.loadOrExit` does: 1.
 */
fun run(env: Map<String, String>, out: PrintStream, err: PrintStream): Int =
    when (val r = Docuconf.checkContract(ShopConfig.contract, env, warn = { err.println("docuconf: $it") })) {
        is ContractFirst.Result.Success -> {
            r.warnings.forEach { err.println("docuconf: $it") }
            out.println(ShopConfig.from(r.values).summary())
            0
        }
        is ContractFirst.Result.Failure -> {
            r.warnings.forEach { err.println("docuconf: $it") }
            val message = ConfigViolationException.format(r.violations)
            writeTerminationLog(env, message)
            err.println(message)
            1
        }
    }

/** `DOCUCONF_TERMINATION_LOG`, else `/dev/termination-log` when it exists, so `kubectl describe pod` shows the problems. */
private fun writeTerminationLog(env: Map<String, String>, message: String) {
    val file = env["DOCUCONF_TERMINATION_LOG"]?.takeIf { it.isNotEmpty() }?.let(::File)
        ?: File("/dev/termination-log").takeIf { it.exists() } ?: return
    runCatching { file.writeText(message + "\n") }
}
