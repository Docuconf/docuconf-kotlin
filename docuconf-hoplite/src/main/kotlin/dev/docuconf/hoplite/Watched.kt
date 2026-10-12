package dev.docuconf.hoplite

import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.Reloadable
import dev.docuconf.kotlin.core.Violation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

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
 * Polling on access needs no thread, costs one `stat` per path per interval at most, and sees a
 * change exactly when the app next uses the value. An app that copies the value into a long-lived
 * object (an `SSLContext`, an HTTP client, a pool) registers [onChange] to rebuild it: while at
 * least one hook is registered, a daemon thread also checks the file every interval, so hooks fire
 * without a read. [status] gives the reload state for a health check or a metric.
 *
 * A [Keystore] reloads with the password variable's value read at boot: a process's environment
 * does not change while it runs, so rotating the password needs a rollout. A changed keystore that
 * does not open with it is rejected as `keystore_unreadable`, and the previous one stays current.
 *
 * An optional input that is absent at boot is `null` (declare `Watched<X>?`); it is not watched for
 * appearing later. A file that disappears after boot keeps its last value. Kubernetes never updates a
 * volume mounted with `subPath`, so a watched input needs its directory mounted.
 */
public class Watched<out T : Any> internal constructor(
    private val source: Reloader,
) : Reloadable<T> {
    /** The current value, reread first when the interval has passed and the file changed. */
    @Suppress("UNCHECKED_CAST")
    override fun current(): T = source.get() as T

    /**
     * Checks the file now, whatever the interval, and returns whether a changed file was taken. A
     * changed file that fails its checks is reported and returns false.
     */
    public fun refresh(): Boolean = source.refresh()

    /**
     * Registers [hook], called with the new value each time a changed file passes its checks and
     * replaces the current value; never for a change that is rejected. Hooks run in the order they
     * were registered, on the thread that found the change: the one that called [current] or
     * [refresh], or the daemon thread that checks the file every [DocuconfOptions.reloadInterval]
     * (at least every 100 ms) while any hook is registered. One check runs at a time, so hooks never
     * overlap; a [current] call on another thread meanwhile returns the value without waiting.
     *
     * A hook that throws is reported through [DocuconfOptions.warn] by input name and exception type
     * only; the other hooks still run, and the new value stays. Close the returned handle to
     * unregister the hook; the daemon thread stops when the last one is closed.
     *
     * ```
     * val subscription = config.tls.onChange { pair -> rebuildServerContext(pair) }
     * ```
     */
    public fun onChange(hook: (T) -> Unit): AutoCloseable {
        @Suppress("UNCHECKED_CAST")
        return source.onChange(hook as (Any) -> Unit)
    }

    /** The reload state: [ReloadStatus.generation], the last accepted reload, the last rejected change. */
    public val status: ReloadStatus get() = source.status

    /** The current value's `toString`, which never shows a secret file's content. */
    override fun toString(): String = "Watched(${current()})"

    public companion object {
        /** A value that never reloads, for tests: build an `AppConfig` without files on disk. */
        public fun <T : Any> of(value: T): Watched<T> = Watched(Reloader("", emptyList(), Duration.ZERO, {}, initial = value, load = null))
    }
}

/**
 * The reload state of one [Watched] input (SPEC §4.6.2), for a health check or a metric. It never
 * holds file content.
 *
 * @property generation 1 after boot, plus one per accepted reload
 * @property lastReload when the last accepted reload happened; null before the first
 * @property lastRejected the last change that failed its checks and was not used; null when there
 *   was none, or a later change was accepted
 */
public data class ReloadStatus(
    val generation: Long,
    val lastReload: Instant? = null,
    val lastRejected: RejectedReload? = null,
)

/**
 * A change to a watched file that failed its checks, so the previous value stayed current. It names
 * the violation codes, never the content.
 */
public data class RejectedReload(val time: Instant, val input: String, val codes: List<String>)

/**
 * The current value of one watched file input, and what rereads it when the files it reads change.
 * [load] runs the boot checks and returns the value, or null with the violations; a null [load] never
 * reloads ([Watched.of]).
 */
internal class Reloader(
    private val name: String,
    private val paths: List<Path>,
    private val interval: Duration,
    private val warn: (String) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
    private val nanoTime: () -> Long = System::nanoTime,
    initial: Any,
    private val load: (() -> Pair<Any?, List<Violation>>)?,
) {
    @Volatile
    private var value: Any = initial

    @Volatile
    var status: ReloadStatus = ReloadStatus(generation = 1)
        private set

    /** Held while a change is checked and its hooks run, so checks never overlap. */
    private val check = ReentrantLock()
    private val hooks = CopyOnWriteArrayList<Hook>()
    private var background: ScheduledExecutorService? = null
    private var seen: List<Stamp?> = stamps()
    private var due: Long = nanoTime() + interval.toNanos()

    /** One registration; a class of its own so the same function registered twice is two hooks. */
    private class Hook(val fn: (Any) -> Unit)

    fun get(): Any {
        // A check running on another thread (or this one, from a hook) keeps the current value.
        if (load != null && check.tryLock()) {
            try {
                poll(force = false)
            } finally {
                check.unlock()
            }
        }
        return value
    }

    fun refresh(): Boolean {
        if (load == null) return false
        check.lock()
        try {
            return poll(force = true)
        } finally {
            check.unlock()
        }
    }

    fun onChange(fn: (Any) -> Unit): AutoCloseable {
        val hook = Hook(fn)
        synchronized(this) {
            hooks += hook
            if (load != null && background == null) background = startBackground()
        }
        return AutoCloseable {
            synchronized(this) {
                if (hooks.remove(hook) && hooks.isEmpty()) {
                    // shutdown, not shutdownNow: a hook that is running finishes.
                    background?.shutdown()
                    background = null
                }
            }
        }
    }

    private fun startBackground(): ScheduledExecutorService {
        val period = interval.coerceAtLeast(MIN_BACKGROUND).toNanos()
        val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "docuconf-watch-$name").apply { isDaemon = true } }
        executor.scheduleWithFixedDelay({
            check.lock()
            try {
                poll(force = false)
            } catch (e: Exception) {
                warn("file $name: the background reload check failed: ${e.javaClass.name}")
            } finally {
                check.unlock()
            }
        }, period, period, TimeUnit.NANOSECONDS)
        return executor
    }

    /** Takes a changed file that passes its checks; true when it did. The caller holds [check]. */
    private fun poll(force: Boolean): Boolean {
        val load = load ?: return false
        val now = nanoTime()
        if (!force && now - due < 0) return false
        due = now + interval.toNanos()
        // Stamped before reading: a change made while it is read is seen at the next poll.
        val stamps = stamps()
        if (stamps == seen) return false
        // Each change is tried once, so a bad file is reported once, not at every poll.
        seen = stamps
        val (next, violations) = try {
            load()
        } catch (e: Exception) {
            null to listOf(Violation(Codes.FILE_UNREADABLE, name, "could not be reread: ${e.javaClass.simpleName}"))
        }
        if (violations.isNotEmpty() || next == null) {
            val codes = if (violations.isEmpty()) listOf(Codes.FILE_MISSING) else violations.map { it.code }.distinct()
            status = status.copy(lastRejected = RejectedReload(clock.instant(), name, codes))
            if (violations.isEmpty()) warn("file $name changed, but it is no longer there; keeping the previous content")
            violations.forEach { warn("file $name changed, but the change was rejected; keeping the previous content: ${it.code}: ${it.message}") }
            return false
        }
        value = next
        status = ReloadStatus(status.generation + 1, clock.instant(), null)
        for (hook in hooks) call(hook, next)
        return true
    }

    /** Runs one hook; one that throws is reported by input name and exception type only. */
    private fun call(hook: Hook, next: Any) {
        try {
            hook.fn(next)
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            if (e is InterruptedException) Thread.currentThread().interrupt()
            warn("file $name: an on-change hook failed: ${e.javaClass.name}")
        }
    }

    private fun stamps(): List<Stamp?> = paths.map { stamp(it) }

    /** What identifies a file's content without reading it. */
    private data class Stamp(val key: Any?, val modified: Long, val size: Long)

    private fun stamp(p: Path): Stamp? = try {
        // Follows symlinks: Kubernetes swaps `..data`, so the resolved file is a new one.
        val a = Files.readAttributes(p, BasicFileAttributes::class.java)
        Stamp(a.fileKey(), a.lastModifiedTime().to(TimeUnit.NANOSECONDS), a.size())
    } catch (_: java.io.IOException) {
        null
    }

    private companion object {
        /** The shortest period of the background check, whatever [interval] says. */
        val MIN_BACKGROUND: Duration = Duration.ofMillis(100)
    }
}
