package dev.docuconf.core

/**
 * java.util.regex differs from RE2 in three ways that matter for contracts:
 *
 * - `$` also matches before a final line terminator; in RE2 (without `m`) it matches only at the end.
 *   It is rewritten to `\z` outside character classes unless the pattern turns on multi-line mode.
 * - `.`, `^` and `$` treat `\r`, `\u0085`, ` ` and ` ` as line terminators; UNIX_LINES limits that to `\n`.
 * - Named groups are written `(?P<name>...)` in RE2; Java only accepts `(?<name>...)`.
 */
internal actual fun compileRe2(pattern: String): Regex {
    val multiline = Regex("\\(\\?[a-zA-Z]*m[a-zA-Z]*[:)]").containsMatchIn(pattern)
    val sb = StringBuilder()
    var i = 0
    var inClass = false
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '\\' && i + 1 < pattern.length -> {
                sb.append(c).append(pattern[i + 1])
                i += 2
                continue
            }
            inClass -> if (c == ']') inClass = false
            c == '[' -> {
                inClass = true
                sb.append(c)
                i++
                if (pattern.getOrNull(i) == '^') sb.append(pattern[i++])
                if (pattern.getOrNull(i) == ']') sb.append(pattern[i++])
                continue
            }
            c == '$' && !multiline -> {
                sb.append("\\z")
                i++
                continue
            }
            c == '(' && pattern.startsWith("(?P<", i) -> {
                sb.append("(?<")
                i += 4
                continue
            }
        }
        sb.append(c)
        i++
    }
    return Regex(sb.toString(), RegexOption.UNIX_LINES)
}
