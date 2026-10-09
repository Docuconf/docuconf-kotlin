package dev.docuconf.hoplite

import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.Violation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration

/**
 * A file input declared `reload: watch` (SPEC §4.6.2): the app rereads the file itself when its
 * source changes, so the platform does not roll the pods. Declare the parameter as `Watched<X>`,
 * where `X` is any file input type ([ConfigFile], [TlsKeyPair], [CaBundle], [Keystore], [TextFile],
 * [BinaryFile]), and read it with [current]:
 *
 * ```
 * @FileInput(name = "serving-tls", path = "/etc/app/tls")
 * val tls: Watched<TlsKeyPair>,
 * ```
 *
 * [current] checks the file at most once per [DocuconfOptions.reloadInterval] (one second by
 * default). It compares the identity (device and inode), modification time and size of the file each
 * path resolves to, following symlinks, so the symlink swap Kubernetes makes when it updates a
 * Secret or ConfigMap volume counts as a change. A changed file goes through the same checks as at
 * boot. When it passes, [current] returns the new value from then on; when it fails, [current] keeps
 * returning the previous value and each violation is reported once, through [DocuconfOptions.warn],
 * with the code and message a boot failure would have, which never quote a secret file's content.
 *
 * Polling on access needs no thread and nothing to close, costs one `stat` per path per interval at
 * most, and sees a change exactly when the app next uses the value.
 *
 * An optional input that is absent at boot is `null` (declare `Watched<X>?`); it is not watched for
 * appearing later. A file that disappears after boot keeps its last value. Kubernetes never updates a
 * volume mounted with `subPath`, so a watched input needs its directory mounted.
 */
public class Watched<out T : Any> internal constructor(
    initial: T,
    private val source: Reloader?,
) {
    @Volatile
    private var value: Any = initial

    /** The current value, reread first when the interval has passed and the file changed. */
    @Suppress("UNCHECKED_CAST")
    public fun current(): T {
        source?.let { r -> r.poll(force = false)?.let { value = it } }
        return value as T
    }

    /**
     * Checks the file now, whatever the interval, and returns whether a changed file was taken. A
     * changed file that fails its checks is reported and returns false.
     */
    public fun refresh(): Boolean {
        val r = source ?: return false
        val next = r.poll(force = true) ?: return false
        value = next
        return true
    }

    /** The current value's `toString`, which never shows a secret file's content. */
    override fun toString(): String = "Watched(${current()})"

    public companion object {
        /** A value that never reloads, for tests: build an `AppConfig` without files on disk. */
        public fun <T : Any> of(value: T): Watched<T> = Watched(value, null)
    }
}

/**
 * Rereads one watched file input when the files it reads change. [load] runs the boot checks and
 * returns the value, or null with the violations.
 */
internal class Reloader(
    private val name: String,
    private val paths: List<Path>,
    private val interval: Duration,
    private val warn: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val load: () -> Pair<Any?, List<Violation>>,
) {
    private var seen: List<Stamp?> = stamps()
    private var due: Long = nanoTime() + interval.toNanos()

    /** The new value when the files changed and pass their checks; null when unchanged or rejected. */
    @Synchronized
    fun poll(force: Boolean): Any? {
        val now = nanoTime()
        if (!force && now - due < 0) return null
        due = now + interval.toNanos()
        // Stamped before reading: a change made while it is read is seen at the next poll.
        val stamps = stamps()
        if (stamps == seen) return null
        // Each change is tried once, so a bad file is reported once, not at every poll.
        seen = stamps
        val (value, violations) = try {
            load()
        } catch (e: Exception) {
            null to listOf(Violation(Codes.FILE_UNREADABLE, name, "could not be reread: ${e.javaClass.simpleName}"))
        }
        if (violations.isNotEmpty()) {
            violations.forEach { warn("file $name changed, but the change was rejected; keeping the previous content: ${it.code}: ${it.message}") }
            return null
        }
        if (value == null) {
            warn("file $name changed, but it is no longer there; keeping the previous content")
            return null
        }
        return value
    }

    private fun stamps(): List<Stamp?> = paths.map { stamp(it) }

    /** What identifies a file's content without reading it. */
    private data class Stamp(val key: Any?, val modified: Long, val size: Long)

    private fun stamp(p: Path): Stamp? = try {
        // Follows symlinks: Kubernetes swaps `..data`, so the resolved file is a new one.
        val a = Files.readAttributes(p, BasicFileAttributes::class.java)
        Stamp(a.fileKey(), a.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS), a.size())
    } catch (_: java.io.IOException) {
        null
    }
}
