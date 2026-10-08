package com.example

import dev.docuconf.hoplite.Docuconf
import dev.docuconf.kotlin.core.ConfigViolationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    // `env` replaces the process environment: nothing is read from or written to the real one.
    private val good = mapOf("DATABASE_URL" to "postgres://app:pw@db/app")

    @Test
    fun loads() {
        val config = Docuconf.load<AppConfig> { env = good + ("LOG_LEVEL" to "debug") }
        assertEquals(LogLevel.DEBUG, config.logLevel)
    }

    @Test
    fun reportsEveryProblem() {
        val e = assertFailsWith<ConfigViolationException> {
            Docuconf.load<AppConfig> { env = mapOf("PORT" to "0", "REQUEST_TIMEOUT" to "5m") }
        }
        assertEquals(
            """
            docuconf: 3 configuration problems:
              PORT: out_of_range: "0" is below min 1
              DATABASE_URL: missing_required: required, but not set
              REQUEST_TIMEOUT: out_of_range: "5m" is longer than max 1m
            """.trimIndent(),
            e.message,
        )
    }

    @Test
    fun theContractIsCurrent() {
        // The same check as `./gradlew docuconfCheck`, as a unit test: it ignores only
        // metadata.generator.version, the docuconf version that wrote the file.
        val committed = java.io.File("contract.cue").readText()
        assertEquals(Docuconf.withoutGeneratorVersion(committed), Docuconf.withoutGeneratorVersion(Docuconf.exportCue(AppConfig::class)))
    }
}
