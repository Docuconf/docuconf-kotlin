package dev.docuconf.kotlin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReportTest {
    @Test
    fun bootReportFormat() {
        val e = ConfigViolationException(
            listOf(
                Violation(Codes.OUT_OF_RANGE, "PORT", "\"0\" is below min 1"),
                Violation(Codes.MISSING_REQUIRED, "DATABASE_URL", "required, but not set"),
            ),
        )
        assertEquals(
            "docuconf: 2 configuration problems:\n  PORT: out_of_range: \"0\" is below min 1\n  DATABASE_URL: missing_required: required, but not set",
            e.message,
        )
        assertEquals("docuconf: 1 configuration problem:\n  PORT: out_of_range: x", ConfigViolationException(listOf(Violation(Codes.OUT_OF_RANGE, "PORT", "x"))).message)
    }

    @Test
    fun isoDurationErrorShowsTheExpectedForm() {
        val spec = VarSpec("TIMEOUT", VarType.DURATION, "Request timeout", durationEncoding = DurationEncoding.ISO8601)
        assertEquals("\"30s\" is not a duration; expected an ISO 8601 duration like PT30S", ValueChecks.check(spec, "30s").single().message)
        val go = spec.copy(durationEncoding = DurationEncoding.GO)
        assertEquals("\"PT30S\" is not a duration; expected a Go duration like 1m30s", ValueChecks.check(go, "PT30S").single().message)
    }

    @Test
    fun badDurationBoundIsANamedErrorNotACrash() {
        // Used to throw a NullPointerException from the default check.
        val spec = VarSpec("T", VarType.DURATION, "A timeout here", maxDuration = "1 minute", default = JsonValue.Str("1s"))
        val e = assertFailsWith<DeclarationException> { DeclarationChecks.require(Contract("svc", Generator("kotlin", "t", "0"), listOf(spec))) }
        assertEquals(listOf("T: \"1 minute\" is not a Go duration such as 30s or 1h30m"), e.problems)
        assertEquals(emptyList(), ValueChecks.check(spec, "1s"))
    }

    @Test
    fun secretDefaultIsOneError() {
        val spec = VarSpec("TOKEN", VarType.STRING, "A secret token", secret = true, default = JsonValue.Int(1))
        val e = assertFailsWith<DeclarationException> { DeclarationChecks.require(Contract("svc", Generator("kotlin", "t", "0"), listOf(spec))) }
        assertEquals(listOf("TOKEN: a secret cannot have a default"), e.problems)
    }
}
