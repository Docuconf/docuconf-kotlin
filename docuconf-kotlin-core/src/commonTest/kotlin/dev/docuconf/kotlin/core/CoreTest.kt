package dev.docuconf.kotlin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DurationsTest {
    @Test
    fun formatsCanonicalGo() {
        assertEquals("1m30s", Durations.formatGo(90 * Durations.NANOS_PER_SECOND))
        assertEquals("1h30m", Durations.formatGo(90 * Durations.NANOS_PER_MINUTE))
        assertEquals("0s", Durations.formatGo(0))
        assertEquals("1s500ms", Durations.formatGo(1_500_000_000))
        assertEquals("720h", Durations.formatGo(720 * Durations.NANOS_PER_HOUR))
    }

    @Test
    fun parsesGoAndIso() {
        assertEquals(90 * Durations.NANOS_PER_SECOND, Durations.parseGo("1m30s"))
        assertNull(Durations.parseGo("1.5h"))
        assertNull(Durations.parseGo("90"))
        assertEquals(90 * Durations.NANOS_PER_SECOND, Durations.parseIso("PT90S"))
        assertEquals(90 * Durations.NANOS_PER_SECOND, Durations.parseIso("PT1M30S"))
        assertEquals(500 * Durations.NANOS_PER_MILLI, Durations.parseIso("PT0.5S"))
        assertEquals(26 * Durations.NANOS_PER_HOUR, Durations.parseIso("P1DT2H"))
        assertNull(Durations.parseIso("PT"))
        assertNull(Durations.parseIso("90s"))
        assertEquals("PT90S", Durations.formatIso(90 * Durations.NANOS_PER_SECOND))
        assertEquals("PT0.25S", Durations.formatIso(250 * Durations.NANOS_PER_MILLI))
    }
}

class Re2Test {
    @Test
    fun rejectsNonRe2() {
        assertNotNull(Re2.unsupportedFeature("^(?=a)"))
        assertNotNull(Re2.unsupportedFeature("(?<!x)y"))
        assertNotNull(Re2.unsupportedFeature("(a)\\1"))
        assertNotNull(Re2.unsupportedFeature("a++"))
        assertNotNull(Re2.unsupportedFeature("(?>a)"))
        assertNotNull(Re2.unsupportedFeature("a\\Z"))
        assertNull(Re2.unsupportedFeature("^[A-Z0-9]{5}(-[A-Z0-9]{5}){3}\\n?$"))
        assertNull(Re2.unsupportedFeature("^(?P<name>[a-z]+)$"))
        assertNull(Re2.unsupportedFeature("[[:alpha:]]+"))
        assertNull(Re2.unsupportedFeature("[]a]"))
    }

    @Test
    fun matchesLikeRe2() {
        // Partial match, as CUE's =~.
        assertTrue(Re2.matches("b", "abc"))
        // $ is the end of the text, not "before a final newline" as in java.util.regex.
        assertFalse(Re2.matches("^[A-Z]{4}$", "ABCD\n"))
        assertTrue(Re2.matches("^[A-Z]{4}\\n?$", "ABCD\n"))
        assertTrue(Re2.matches("(?m)^b$", "a\nb\nc"))
        assertTrue(Re2.matches("^(?P<word>[a-z]+)$", "abc"))
    }
}

class JsonValueTest {
    @Test
    fun parsesAndWrites() {
        val v = JsonValue.parse("""{"a":[1,2.5,"x\n",true,null],"b":{}}""")
        assertEquals("""{"a":[1,2.5,"x\n",true,null],"b":{}}""", v.toString())
        assertFailsWith<JsonSyntaxException> { JsonValue.parse("{\"a\":}") }
        assertFailsWith<JsonSyntaxException> { JsonValue.parse("[1,]") }
        assertFailsWith<JsonSyntaxException> { JsonValue.parse("01") }
        assertEquals(JsonValue.Int(3), JsonValue.parse("﻿3"))
    }
}

class ValueChecksTest {
    private fun codes(spec: VarSpec, raw: String?) = ValueChecks.check(spec, raw).map { it.code }

    @Test
    fun emptyIsUnsetExceptForStrings() {
        val port = VarSpec("PORT", VarType.INT, "Listen port", required = true)
        assertEquals(listOf(Codes.MISSING_REQUIRED), codes(port, ""))
        assertEquals(emptyList(), codes(port.copy(required = false), ""))
        val name = VarSpec("NAME", VarType.STRING, "A name", required = true, minLength = 1)
        assertEquals(listOf(Codes.OUT_OF_RANGE), codes(name, ""))
    }

    @Test
    fun intsAndFloats() {
        val port = VarSpec("PORT", VarType.INT, "Listen port", min = JsonValue.Int(1), max = JsonValue.Int(65535))
        assertEquals(listOf(Codes.INVALID_TYPE), codes(port, "80a"))
        assertEquals(listOf(Codes.INVALID_TYPE), codes(port, "1.0"))
        assertEquals(listOf(Codes.OUT_OF_RANGE), codes(port, "99999999999999999999"), "an integer beyond 64 bits is out_of_range (SPEC §5)")
        assertEquals(listOf(Codes.OUT_OF_RANGE), codes(port, "70000"))
        assertEquals(emptyList(), codes(port, "8080"))
        val ratio = VarSpec("RATIO", VarType.FLOAT, "A ratio")
        assertEquals(listOf(Codes.INVALID_TYPE), codes(ratio, "NaN"))
        assertEquals(listOf(Codes.INVALID_TYPE), codes(ratio, "Infinity"))
        assertEquals(listOf(Codes.INVALID_TYPE), codes(ratio, "0,5"))
        assertEquals(listOf(Codes.INVALID_TYPE), codes(ratio, "1d"))
        assertEquals(emptyList(), codes(ratio, "0.5"))
    }

    @Test
    fun valuesAreNotTrimmed() {
        val port = VarSpec("PORT", VarType.INT, "Listen port")
        assertEquals(listOf(Codes.INVALID_TYPE), codes(port, "8080\n"))
    }

    @Test
    fun secretsAreNeverShown() {
        val dsn = VarSpec("DSN", VarType.URL, "Database URL", secret = true, schemes = listOf("postgres"))
        val v = ValueChecks.check(dsn, "mysql://user:hunter2@db")
        assertEquals(listOf(Codes.INVALID_SCHEME), v.map { it.code })
        assertFalse(v.single().message.contains("hunter2"))
        val token = VarSpec("TOKEN", VarType.STRING, "API token", secret = true, pattern = "^tok_")
        assertFalse(ValueChecks.check(token, "hunter2").single().message.contains("hunter2"))
    }

    @Test
    fun unresolvedInjectorReferencesInSecrets() {
        val dsn = VarSpec("DATABASE_URL", VarType.URL, "Database URL", secret = true, schemes = listOf("postgres"))
        for ((raw, scheme) in listOf("vault:secret/data/db#url" to "vault:", "op://prod/db/url" to "op://", "ref+awssm://db/url" to "ref+")) {
            val v = ValueChecks.check(dsn, raw)
            assertEquals(listOf(Codes.INVALID_TYPE), v.map { it.code }, raw)
            assertEquals("DATABASE_URL: invalid_type: holds an unresolved $scheme reference; the injector that should resolve it did not run", v.single().toString())
            assertFalse(raw.removePrefix(scheme) in v.single().message)
        }
        // A resolved value passes; a non-secret is checked as usual (a string may legitimately start with vault:).
        assertEquals(emptyList(), codes(dsn, "postgres://db/app"))
        assertEquals(emptyList(), codes(VarSpec("ADDR", VarType.STRING, "Vault address"), "vault:8200"))
    }

    @Test
    fun enumsListsDurations() {
        val level = VarSpec("LEVEL", VarType.ENUM, "Log level", values = listOf("info", "debug"))
        assertEquals(listOf(Codes.NOT_IN_ENUM), codes(level, "INFO"))
        val hosts = VarSpec("HOSTS", VarType.LIST, "Hosts", items = ListItems.INT, minItems = 2, maxItems = 3)
        assertEquals(listOf(Codes.TOO_FEW_ITEMS), codes(hosts, "1"))
        assertEquals(listOf(Codes.TOO_MANY_ITEMS), codes(hosts, "1,2,3,4"))
        assertEquals(listOf(Codes.INVALID_TYPE), codes(hosts, "1,x"))
        val timeout = VarSpec("TIMEOUT", VarType.DURATION, "Timeout", durationEncoding = DurationEncoding.ISO8601, minDuration = "1s", maxDuration = "5m")
        assertEquals(emptyList(), codes(timeout, "PT30S"))
        assertEquals(listOf(Codes.OUT_OF_RANGE), codes(timeout, "PT10M"))
        assertEquals(listOf(Codes.INVALID_TYPE), codes(timeout, "30s"))
        // Exactly the encoding's grammar (SPEC §5): no Go syntax for an iso8601 duration.
        assertEquals(listOf(Codes.INVALID_TYPE), codes(timeout, "1m30s"))
    }

    @Test
    fun jsonVars() {
        val schema = JsonValue.parse("""{"type":"object","required":["perMinute"],"additionalProperties":false,"properties":{"perMinute":{"type":"integer","minimum":1}}}""")
        val limits = VarSpec("LIMITS", VarType.JSON, "Rate limits", schema = schema)
        assertEquals(emptyList(), codes(limits, """{"perMinute":5}"""))
        assertEquals(listOf(Codes.INVALID_TYPE), codes(limits, """{"perMinute":"""))
        assertEquals(listOf(Codes.SCHEMA_MISMATCH, Codes.SCHEMA_MISMATCH), codes(limits, """{"perMinute":0,"extra":1}"""))
    }
}

class DeclarationChecksTest {
    private val gen = Generator("kotlin", "test", "0")

    @Test
    fun checksOverlays() {
        val vars = listOf(VarSpec("A_B_C_D_E_F_G_H_I", VarType.STRING, "Deeply nested", configKey = "a.b.c.d.e.f.g.h.i"))
        val c = Contract(
            "svc", gen, vars,
            overlays = listOf(
                OverlaySpec("platform", ConfigFormat.YAML, "/app/config/svc.yaml", "/", description = "abc"),
                OverlaySpec("platform", ConfigFormat.JSON, "/app/config/other.json", ":"),
                OverlaySpec("relative", ConfigFormat.JSON, "config/svc.json", "."),
            ),
        )
        val r = DeclarationChecks.check(c)
        val text = r.errors.joinToString("\n")
        for (needle in listOf("keySeparator", "at least 5", "declared more than once", "shares its mount directory /app/config", "absolute and normalised")) {
            assertTrue(needle in text, "missing \"$needle\" in:\n$text")
        }
        assertTrue(r.warnings.any { "deeper than 8" in it }, r.warnings.toString())
        val cue = CueWriter.write(c.copy(overlays = c.overlays.take(1)))
        assertTrue("overlays: {\n\t\tplatform: {\n\t\t\tdescription: \"abc\"\n\t\t\tformat: \"yaml\"" in cue, cue)
    }

    @Test
    fun reportsEveryProblem() {
        val c = Contract(
            "Bad_Name", gen,
            listOf(
                VarSpec("port", VarType.INT, "Port"),
                VarSpec("PORT2", VarType.INT, "Listen port", required = true, default = JsonValue.Int(1)),
                VarSpec("TOKEN", VarType.STRING, "API token", secret = true, default = JsonValue.Str("x")),
                VarSpec("CODE", VarType.STRING, "A code", pattern = "(?=x)"),
                VarSpec("WORKERS", VarType.INT, "Worker count", min = JsonValue.Int(1), default = JsonValue.Int(0)),
                VarSpec("ENABLE_X", VarType.BOOL, "Turns X on"),
            ),
            listOf(FileSpec("ca", FileType.CA_BUNDLE, "Trusted CAs", "/etc/ssl/certs/ca.pem")),
        )
        val r = DeclarationChecks.check(c)
        val text = r.errors.joinToString("\n")
        for (needle in listOf("service name", "port:", "description", "required variable cannot have a default", "secret cannot have a default", "lookahead", "WORKERS: default", "/etc/ssl/certs")) {
            assertTrue(needle in text, "expected '$needle' in:\n$text")
        }
        assertTrue(r.warnings.single().contains("feature flag"))
    }

    @Test
    fun keystorePasswordMustBeSecret() {
        val c = Contract(
            "svc", gen,
            listOf(VarSpec("KS_PASSWORD", VarType.STRING, "Keystore password")),
            listOf(FileSpec("ks", FileType.KEYSTORE, "Client keystore", "/etc/ks/ks.p12", keystoreFormat = KeystoreFormat.PKCS12, passwordVar = "KS_PASSWORD")),
        )
        assertTrue(DeclarationChecks.check(c).errors.single().contains("passwordVar"))
    }
}

class CueWriterTest {
    @Test
    fun writesSortedDeterministicData() {
        val c = Contract(
            "my-svc", Generator("kotlin", "docuconf-hoplite", "0.1.0"),
            listOf(
                VarSpec("Z_LAST", VarType.FLOAT, "Last one", default = JsonValue.Int(1)),
                VarSpec("A_FIRST", VarType.STRING, "First one", pattern = "^a\\d$"),
            ),
        )
        val cue = CueWriter.write(c)
        assertTrue(cue.startsWith("// Code generated by docuconf. DO NOT EDIT.\npackage my_svc\n"))
        assertTrue(cue.indexOf("A_FIRST") < cue.indexOf("Z_LAST"))
        assertTrue("pattern: \"^a\\\\d$\"" in cue)
        assertTrue("default: 1.0" in cue)
        assertEquals(cue, CueWriter.write(c.copy(vars = c.vars.reversed())))
    }
}
