package dev.docuconf.core

/**
 * Durations as nanoseconds, in the forms SPEC §5 names. Contracts always hold the Go form;
 * the app receives whatever its encoding says.
 */
public object Durations {
    private val goSyntax = Regex("^([0-9]+(ns|us|ms|s|m|h))+$")
    private val goPart = Regex("([0-9]+)(ns|us|ms|s|m|h)")
    private val iso = Regex("^P(?:([0-9]+)D)?(?:T(?:([0-9]+)H)?(?:([0-9]+)M)?(?:([0-9]+)(?:\\.([0-9]{1,9}))?S)?)?$")

    public const val NANOS_PER_MILLI: Long = 1_000_000L
    public const val NANOS_PER_SECOND: Long = 1_000_000_000L
    public const val NANOS_PER_MINUTE: Long = 60 * NANOS_PER_SECOND
    public const val NANOS_PER_HOUR: Long = 60 * NANOS_PER_MINUTE

    /** Whether [s] is a duration in Go syntax as the meta-schema accepts it (`1m30s`, `720h`). */
    public fun isGo(s: String): Boolean = goSyntax.matches(s)

    /** Parses Go syntax (`1h30m`, `500ms`); null when [s] is not in that form or overflows. */
    public fun parseGo(s: String): Long? {
        if (!goSyntax.matches(s)) return null
        var total = 0L
        for (m in goPart.findAll(s)) {
            val n = m.groupValues[1].toLongOrNull() ?: return null
            val unit = when (m.groupValues[2]) {
                "h" -> NANOS_PER_HOUR
                "m" -> NANOS_PER_MINUTE
                "s" -> NANOS_PER_SECOND
                "ms" -> NANOS_PER_MILLI
                "us" -> 1_000L
                else -> 1L
            }
            total = addExact(total, mulExact(n, unit) ?: return null) ?: return null
        }
        return total
    }

    /**
     * Formats in canonical Go form with zero units omitted: 90 s is `1m30s`, 1.5 h is `1h30m`,
     * zero is `0s` (SPEC §11.2 item 3).
     */
    public fun formatGo(nanos: Long): String {
        require(nanos >= 0) { "durations in a contract cannot be negative" }
        if (nanos == 0L) return "0s"
        var rest = nanos
        val sb = StringBuilder()
        for ((unit, suffix) in listOf(
            NANOS_PER_HOUR to "h", NANOS_PER_MINUTE to "m", NANOS_PER_SECOND to "s",
            NANOS_PER_MILLI to "ms", 1_000L to "us", 1L to "ns",
        )) {
            if (rest >= unit) {
                sb.append(rest / unit).append(suffix)
                rest %= unit
            }
        }
        return sb.toString()
    }

    /** Parses the ISO-8601 form the platform renders for `iso8601` (`PT90S`, `PT0.5S`), plus days and hours. */
    public fun parseIso(s: String): Long? {
        val m = iso.matchEntire(s) ?: return null
        if (s == "P" || s.endsWith("T")) return null
        val (d, h, min, sec, frac) = m.destructured
        var total = 0L
        for ((text, unit) in listOf(d to 24 * NANOS_PER_HOUR, h to NANOS_PER_HOUR, min to NANOS_PER_MINUTE, sec to NANOS_PER_SECOND)) {
            if (text.isEmpty()) continue
            total = addExact(total, mulExact(text.toLongOrNull() ?: return null, unit) ?: return null) ?: return null
        }
        if (frac.isNotEmpty()) total = addExact(total, frac.padEnd(9, '0').toLong()) ?: return null
        return total
    }

    /** Formats as the ISO-8601 string the platform renders for the `iso8601` encoding (`PT90S`). */
    public fun formatIso(nanos: Long): String {
        val secs = nanos / NANOS_PER_SECOND
        val frac = nanos % NANOS_PER_SECOND
        val fracStr = if (frac == 0L) "" else "." + frac.toString().padStart(9, '0').trimEnd('0')
        return "PT$secs${fracStr}S"
    }

    private fun mulExact(a: Long, b: Long): Long? {
        if (a == 0L || b == 0L) return 0L
        val r = a * b
        return if (r / b != a) null else r
    }

    private fun addExact(a: Long, b: Long): Long? {
        val r = a + b
        return if (((a xor r) and (b xor r)) < 0) null else r
    }
}
