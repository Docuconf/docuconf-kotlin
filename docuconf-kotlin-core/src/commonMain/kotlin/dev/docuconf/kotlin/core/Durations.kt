package dev.docuconf.kotlin.core

/**
 * Durations as nanoseconds, in the forms SPEC §5 names. Contracts always hold the Go form;
 * the app receives whatever its encoding says.
 */
public object Durations {
    private val goSyntax = Regex("^([0-9]+(ns|us|ms|s|m|h))+$")
    private val goPart = Regex("([0-9]+)(ns|us|ms|s|m|h)")

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
     * zero is `0s` (SPEC §11.2 item 3). A negative duration gets a leading `-` (`-1m30s`); contracts
     * never hold one, but a value parsed from the `go` encoding may be negative.
     */
    public fun formatGo(nanos: Long): String {
        if (nanos == 0L) return "0s"
        // The magnitude as unsigned, so Long.MIN_VALUE works too.
        var rest = if (nanos < 0) (-(nanos + 1)).toULong() + 1u else nanos.toULong()
        val sb = StringBuilder()
        if (nanos < 0) sb.append('-')
        for ((unit, suffix) in listOf(
            NANOS_PER_HOUR to "h", NANOS_PER_MINUTE to "m", NANOS_PER_SECOND to "s",
            NANOS_PER_MILLI to "ms", 1_000L to "us", 1L to "ns",
        )) {
            val u = unit.toULong()
            if (rest >= u) {
                sb.append(rest / u).append(suffix)
                rest %= u
            }
        }
        return sb.toString()
    }

    private const val ISO_NUMBER = "([0-9]+(?:[.,][0-9]+)?)"
    private val isoWire = Regex("^P(?:${ISO_NUMBER}D)?(?:T(?:${ISO_NUMBER}H)?(?:${ISO_NUMBER}M)?(?:${ISO_NUMBER}S)?)?$")
    private val secondsWire = Regex("^([0-9]+)(?:\\.([0-9]+))?$")
    private val timespanWire = Regex("^(?:([0-9]+)\\.)?([0-9]{1,2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,7}))?$")

    /**
     * Parses an ISO 8601 duration exactly as SPEC §5 reads the `iso8601` encoding:
     * `P[nD][T[nH][nM][nS]]` with at least one component and one after a `T`, where `n` is digits
     * with an optional fraction after `.` or `,` (`PT1,5S`). Upper case only, no sign, and no years,
     * months or weeks. Truncated to whole nanoseconds; null when it does not match or overflows.
     */
    public fun parseIso(s: String): Long? {
        val m = isoWire.matchEntire(s) ?: return null
        if (s == "P" || s.endsWith("T")) return null
        var total = 0L
        val units = listOf(24 * NANOS_PER_HOUR, NANOS_PER_HOUR, NANOS_PER_MINUTE, NANOS_PER_SECOND)
        for ((i, unit) in units.withIndex()) {
            val text = m.groupValues[i + 1]
            if (text.isEmpty()) continue
            val sep = text.indexOfFirst { it == '.' || it == ',' }
            val whole = if (sep < 0) text else text.substring(0, sep)
            val frac = if (sep < 0) "" else text.substring(sep + 1)
            total = addExact(total, decimalTimes(whole, frac, unit) ?: return null) ?: return null
        }
        return total
    }

    /** Parses the `seconds` encoding (SPEC §5): `^[0-9]+(\.[0-9]+)?$`, unsigned, no exponent. */
    public fun parseSeconds(s: String): Long? {
        val m = secondsWire.matchEntire(s) ?: return null
        return decimalTimes(m.groupValues[1], m.groupValues[2], NANOS_PER_SECOND)
    }

    /**
     * Parses the `timespan` encoding (SPEC §5), .NET TimeSpan's constant format
     * `[d.]hh:mm:ss[.f]`: hours below 24, minutes and seconds below 60, one to seven fraction digits.
     */
    public fun parseTimespan(s: String): Long? {
        val m = timespanWire.matchEntire(s) ?: return null
        val (d, h, min, sec, f) = m.destructured
        if (h.toInt() > 23 || min.toInt() > 59 || sec.toInt() > 59) return null
        var total = if (d.isEmpty()) 0L else decimalTimes(d, "", 24 * NANOS_PER_HOUR) ?: return null
        total = addExact(total, h.toLong() * NANOS_PER_HOUR + min.toLong() * NANOS_PER_MINUTE) ?: return null
        return addExact(total, decimalTimes(sec, f, NANOS_PER_SECOND) ?: return null)
    }

    /**
     * Parses Go's `time.ParseDuration` grammar exactly, as SPEC §5 reads the `go` encoding: an
     * optional sign, then `0` or one or more decimal numbers (`1.5`, `.5`, `1.`) each followed by a
     * unit (`ns`, `us`, `µs`, `μs`, `ms`, `s`, `m`, `h`). Fractions are computed as Go computes them,
     * so every SDK gets the same nanoseconds. Null when [s] does not parse or is beyond ±2^63-1 ns.
     */
    public fun parseGoWire(s: String): Long? {
        var rest = s
        var neg = false
        if (rest.isNotEmpty() && (rest[0] == '-' || rest[0] == '+')) {
            neg = rest[0] == '-'
            rest = rest.substring(1)
        }
        if (rest == "0") return 0L
        if (rest.isEmpty()) return null
        val limit = 1uL shl 63
        var d = 0uL
        var i = 0
        while (i < rest.length) {
            // [0-9]*
            var v = 0uL
            val intStart = i
            while (i < rest.length && rest[i] in '0'..'9') {
                if (v > limit / 10u) return null
                v = v * 10u + (rest[i] - '0').toULong()
                if (v > limit) return null
                i++
            }
            val pre = i != intStart
            // (\.[0-9]*)?
            var f = 0uL
            var scale = 1.0
            var post = false
            if (i < rest.length && rest[i] == '.') {
                i++
                val fracStart = i
                var overflow = false
                while (i < rest.length && rest[i] in '0'..'9') {
                    if (!overflow) {
                        if (f > (limit - 1u) / 10u) {
                            overflow = true
                        } else {
                            val y = f * 10u + (rest[i] - '0').toULong()
                            if (y > limit) {
                                overflow = true
                            } else {
                                f = y
                                scale *= 10.0
                            }
                        }
                    }
                    i++
                }
                post = i != fracStart
            }
            if (!pre && !post) return null
            // The unit: everything up to the next digit or point.
            val unitStart = i
            while (i < rest.length && rest[i] != '.' && rest[i] !in '0'..'9') i++
            if (i == unitStart) return null
            val unit = goUnits[rest.substring(unitStart, i)]?.toULong() ?: return null
            if (v > limit / unit) return null
            v *= unit
            if (f > 0u) {
                v += (f.toDouble() * (unit.toDouble() / scale)).toULong()
                if (v > limit) return null
            }
            d += v
            if (d > limit) return null
        }
        if (neg) return if (d == limit) Long.MIN_VALUE else -(d.toLong())
        if (d > limit - 1u) return null
        return d.toLong()
    }

    private val goUnits: Map<String, Long> = mapOf(
        "ns" to 1L, "us" to 1_000L, "µs" to 1_000L, "μs" to 1_000L,
        "ms" to NANOS_PER_MILLI, "s" to NANOS_PER_SECOND, "m" to NANOS_PER_MINUTE, "h" to NANOS_PER_HOUR,
    )

    /**
     * `whole.frac` times [unit] nanoseconds, truncated to whole nanoseconds, computed exactly in
     * decimal so no digit is lost. Null when it overflows a Long.
     */
    private fun decimalTimes(whole: String, frac: String, unit: Long): Long? {
        // The decimal digits of whole+frac times unit, least significant first; dropping the last
        // frac.length of them is an exact floor((whole.frac) * unit).
        val digits = whole + frac
        val product = ArrayList<Long>()
        var carry = 0L
        for (k in digits.indices.reversed()) {
            val x = (digits[k] - '0') * unit + carry
            product.add(x % 10)
            carry = x / 10
        }
        while (carry > 0) {
            product.add(carry % 10)
            carry /= 10
        }
        var result = 0L
        for (k in product.indices.reversed()) {
            if (k < frac.length) break
            result = addExact(mulExact(result, 10) ?: return null, product[k]) ?: return null
        }
        return result
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
