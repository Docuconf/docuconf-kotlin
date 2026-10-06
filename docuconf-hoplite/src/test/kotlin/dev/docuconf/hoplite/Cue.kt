package dev.docuconf.hoplite

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.assertTrue

/** Runs the `cue` binary against the meta-schema in `DOCUCONF_SPEC_DIR`; skips when either is missing. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
object Cue {
    /**
     * A CUE module in [dir] holding the meta-schema and [contract] as package `docuconf.dev/svc`.
     * Returns the cue binary. Skips the test (or fails, with `DOCUCONF_REQUIRE_CUE=1`) when cue or
     * the meta-schema is missing.
     */
    fun module(dir: Path, contract: String): String {
        val cue = find()
        val spec = System.getenv("DOCUCONF_SPEC_DIR")?.let { Path.of(it) }
        val available = cue != null && spec != null && spec.resolve("contract/contract.cue").exists()
        if (System.getenv("DOCUCONF_REQUIRE_CUE") == "1") {
            assertTrue(available, "DOCUCONF_REQUIRE_CUE=1 but cue ($cue) or the meta-schema in DOCUCONF_SPEC_DIR ($spec) is missing")
        }
        assumeTrue(cue != null, "cue is not installed; skipping")
        assumeTrue(available, "DOCUCONF_SPEC_DIR does not hold the meta-schema; skipping")
        spec!!.resolve("cue.mod").copyToRecursively(dir.resolve("cue.mod"), followLinks = false, overwrite = true)
        spec.resolve("contract").copyToRecursively(dir.resolve("contract"), followLinks = false, overwrite = true)
        dir.resolve("svc").createDirectories()
        Files.writeString(dir.resolve("svc/contract.cue"), contract)
        return cue!!
    }

    /** Runs cue in [dir]; returns the exit code and combined output. */
    fun run(cue: String, dir: Path, vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(listOf(cue) + args).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS))
        return p.exitValue() to out
    }

    private fun find(): String? {
        val home = System.getProperty("user.home")
        val candidates = listOf("$home/go/bin/cue") + (System.getenv("PATH") ?: "").split(':').map { "$it/cue" }
        return candidates.firstOrNull { Files.isExecutable(Path.of(it)) }
    }
}
