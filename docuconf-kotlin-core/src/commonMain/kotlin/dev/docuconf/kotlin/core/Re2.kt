package dev.docuconf.kotlin.core

/**
 * RE2 is the pattern dialect of the contract (SPEC §4.3): CUE, Go and the platform match with it.
 * [unsupportedFeature] rejects the constructs other engines accept but RE2 does not, so a pattern
 * that passes here means the same thing in the app and on the platform. Matching is partial
 * (`containsMatchIn`), as in CUE's `=~`.
 */
public object Re2 {
    /** Returns a description of the first non-RE2 construct in [pattern], or null when it is RE2-compatible. */
    public fun unsupportedFeature(pattern: String): String? {
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\') {
                val n = pattern.getOrNull(i + 1) ?: return "a trailing backslash"
                if (n in '1'..'9') return "a backreference (\\$n)"
                if (!inClass) {
                    when (n) {
                        'k' -> return "a named backreference (\\k)"
                        'Z' -> return "\\Z (use \\z or $)"
                        'G' -> return "\\G"
                        'R' -> return "\\R"
                        'X' -> return "\\X"
                        'h', 'H' -> return "\\$n (use [ \\t])"
                        'V', 'v' -> return "\\$n"
                    }
                }
                i += 2
                continue
            }
            if (inClass) {
                if (c == '[' && pattern.getOrNull(i + 1) == ':') {
                    val end = pattern.indexOf(":]", i + 2)
                    if (end > 0) {
                        i = end + 2
                        continue
                    }
                }
                if (c == '[') return "a nested character class"
                if (c == '&' && pattern.getOrNull(i + 1) == '&') return "character class intersection (&&)"
                if (c == ']') inClass = false
                i++
                continue
            }
            when (c) {
                '[' -> {
                    inClass = true
                    i++
                    if (pattern.getOrNull(i) == '^') i++
                    if (pattern.getOrNull(i) == ']') i++ // a literal ] first in the class
                    continue
                }
                '(' -> if (pattern.getOrNull(i + 1) == '?') {
                    val rest = pattern.substring(i + 2)
                    when {
                        rest.startsWith("=") || rest.startsWith("!") -> return "a lookahead"
                        rest.startsWith("<=") || rest.startsWith("<!") -> return "a lookbehind"
                        rest.startsWith(">") -> return "an atomic group"
                        rest.startsWith("#") -> return "a comment group"
                        rest.startsWith("|") -> return "a branch-reset group"
                        rest.startsWith("P=") || rest.startsWith("P>") -> return "a named backreference"
                    }
                }
                '*', '+', '?', '}' -> if (pattern.getOrNull(i + 1) == '+') return "a possessive quantifier"
            }
            i++
        }
        if (inClass) return "an unterminated character class"
        return null
    }

    /** Compiles [pattern] with RE2 semantics on this platform. Throws [IllegalArgumentException] for non-RE2 patterns. */
    public fun compile(pattern: String): Regex {
        unsupportedFeature(pattern)?.let { throw IllegalArgumentException("pattern uses $it, which RE2 does not support") }
        return compileRe2(pattern)
    }

    /** RE2 partial match: true when [pattern] matches anywhere in [value]. */
    public fun matches(pattern: String, value: String): Boolean = compile(pattern).containsMatchIn(value)
}

/** Compiles an RE2-checked pattern so that it behaves as RE2 does on this platform. */
internal expect fun compileRe2(pattern: String): Regex
