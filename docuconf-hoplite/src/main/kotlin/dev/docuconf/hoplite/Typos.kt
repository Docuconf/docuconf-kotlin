package dev.docuconf.hoplite

import dev.docuconf.kotlin.core.Contract

/**
 * Environment variables that are set but not declared, and are within edit distance 2 of a declared
 * name: `DATABSE_URL` for `DATABASE_URL`. A likely typo, reported as a warning (never with its value).
 */
internal object Typos {
    /** Each suspicious set name, mapped to the declared name it is closest to. */
    fun find(env: Map<String, String>, contract: Contract, prefix: String): Map<String, String> {
        val declared = (contract.vars.map { it.name } + contract.files.mapNotNull { it.pathEnv }).toSet()
        if (declared.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (set in env.keys.sorted()) {
            if (set in declared || set.startsWith("DOCUCONF_")) continue
            if (prefix.isNotEmpty() && !set.startsWith(prefix)) continue
            val short = set.removePrefix(prefix)
            val best = declared
                .map { it to distance(short, it.removePrefix(prefix), 2) }
                .filter { it.second in 1..2 }
                .minWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
                ?: continue
            out[set] = best.first
        }
        return out
    }

    /** Levenshtein distance, or [limit] + 1 once it is certainly larger than [limit]. */
    fun distance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > limit) return limit + 1
            prev = cur
        }
        return prev[b.length]
    }
}
