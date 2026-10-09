package dev.docuconf.kotlin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Strict parsing (SPEC §5): exactly the grammars, never trimmed. */
class StrictParsingTest {
    private fun codes(spec: VarSpec, raw: String) = ValueChecks.check(spec, raw).map { it.code }

    @Test
    fun goDurationsFollowParseDuration() {
        val s = Durations.NANOS_PER_SECOND
        assertEquals(90 * s, Durations.parseGoWire("1m30s"))
        assertEquals(90 * Durations.NANOS_PER_MINUTE, Durations.parseGoWire("1.5h"))
        assertEquals(500 * Durations.NANOS_PER_MILLI, Durations.parseGoWire(".5s"))
        assertEquals(s, Durations.parseGoWire("1.s"))
        assertEquals(-90 * s, Durations.parseGoWire("-1m30s"))
        assertEquals(5 * s, Durations.parseGoWire("+5s"))
        assertEquals(0L, Durations.parseGoWire("0"))
        assertEquals(0L, Durations.parseGoWire("-0"))
        assertEquals(1_500_000L, Durations.parseGoWire("1500us"))
        assertEquals(2_000L, Durations.parseGoWire("1µs1μs"))
        assertEquals(Long.MAX_VALUE, Durations.parseGoWire("9223372036854775807ns"))
        assertEquals(Long.MIN_VALUE, Durations.parseGoWire("-9223372036854775808ns"))
        for (bad in listOf("", "5", "5S", "1d", "1m 30s", "5s\n", " 5s", "-", ".s", "9223372036854775808ns", "1x")) {
            assertNull(Durations.parseGoWire(bad), "\"$bad\" should not parse")
        }
        assertEquals("-1m30s", Durations.formatGo(-90 * s))
        assertEquals("-2562047h47m16s854ms775us808ns", Durations.formatGo(Long.MIN_VALUE))
    }

    @Test
    fun otherDurationEncodings() {
        val s = Durations.NANOS_PER_SECOND
        assertEquals(1_500_000_000L, Durations.parseIso("PT1,5S"))
        assertEquals(90 * Durations.NANOS_PER_MINUTE, Durations.parseIso("PT1.5H"))
        assertEquals(26 * Durations.NANOS_PER_HOUR, Durations.parseIso("P1DT2H"))
        for (bad in listOf("pt90s", "PT", "P", "P1W", "P1M", "-PT5S", "PT5S ")) assertNull(Durations.parseIso(bad), bad)
        assertEquals(1_500_000_000L, Durations.parseSeconds("1.5"))
        assertEquals(1L, Durations.parseSeconds("0.0000000019"))
        for (bad in listOf("-5", "+5", "5e1", ".5", "5.")) assertNull(Durations.parseSeconds(bad), bad)
        assertEquals(90 * s, Durations.parseTimespan("00:01:30"))
        assertEquals(24 * Durations.NANOS_PER_HOUR + 100, Durations.parseTimespan("1.00:00:00.0000001"))
        for (bad in listOf("01:30", "24:00:00", "-00:00:05", "00:60:00")) assertNull(Durations.parseTimespan(bad), bad)
    }

    @Test
    fun scalarsAreExact() {
        val flag = VarSpec("FLAG", VarType.BOOL, "A switch")
        assertEquals(emptyList(), codes(flag, "TRUE"))
        assertEquals(true, ValueChecks.parse(flag, "tRuE").value)
        for (bad in listOf("1", "0", "t", "f", "yes", "no", "on", "off", " true", "false ")) assertEquals(listOf(Codes.INVALID_TYPE), codes(flag, bad), bad)
        val count = VarSpec("COUNT", VarType.INT, "A count")
        assertEquals(7L, ValueChecks.parse(count, "007").value)
        assertEquals(5L, ValueChecks.parse(count, "+5").value)
        for (bad in listOf("0x10", "1_000", "1e3", "5.0", " 5", "5\n", "-", "+-5", "١٢")) assertEquals(listOf(Codes.INVALID_TYPE), codes(count, bad), bad)
        assertEquals(listOf(Codes.OUT_OF_RANGE), codes(count, "-9223372036854775809"))
        val ratio = VarSpec("RATIO", VarType.FLOAT, "A ratio")
        assertEquals(1000.0, ValueChecks.parse(ratio, "1E3").value)
        for (bad in listOf("0x1p4", "inf", "NaN", ".5", "5.", "1e", "1e400", "0,5")) assertEquals(listOf(Codes.INVALID_TYPE), codes(ratio, bad), bad)
    }

    @Test
    fun csvItemsAreNeverTrimmed() {
        val names = VarSpec("NAMES", VarType.LIST, "Names", items = ListItems.STRING)
        assertEquals(listOf("a", " b ", "c"), ValueChecks.parse(names, "a, b ,c").value)
        assertEquals(listOf("a", "", "b"), ValueChecks.parse(names, "a,,b").value)
        val ids = VarSpec("IDS", VarType.LIST, "Ids", items = ListItems.INT)
        assertEquals(listOf(Codes.INVALID_TYPE), codes(ids, "1, 2"))
    }
}

class KeySetTest {
    private val keys = VarSpec("WEBHOOK_KEYS", VarType.KEY_SET, "Webhook keys", secret = true, keyMinLength = 4, keyMaxLength = 8)

    @Test
    fun parsesKeysInOrder() {
        val set = ValueChecks.parse(keys, "old1,new2").value
        assertIs<KeySet>(set)
        assertEquals(listOf("old1", "new2"), set.keys)
        assertEquals("KeySet(****)", set.toString())
        assertEquals(listOf(" ab ", "cdef"), (ValueChecks.parse(keys, " ab ,cdef").value as KeySet).keys)
    }

    @Test
    fun reportsKeysWithoutPrintingThem() {
        val tooMany = ValueChecks.check(keys, "aaaa,bbbb,cccc")
        assertEquals(listOf(Codes.TOO_MANY_ITEMS), tooMany.map { it.code })
        val empty = ValueChecks.check(keys.copy(keyMinLength = null), "aaaa,")
        assertEquals(listOf(Codes.OUT_OF_RANGE), empty.map { it.code })
        val short = ValueChecks.check(keys, "aaaa,bb")
        assertEquals(listOf(Codes.OUT_OF_RANGE), short.map { it.code })
        assertFalse(short.single().message.contains("bb"))
        val json = keys.copy(listEncoding = ListEncoding.JSON, keyMinLength = null)
        assertEquals(listOf(Codes.TOO_FEW_ITEMS), ValueChecks.check(json, "[]").map { it.code })
        assertEquals(listOf(Codes.INVALID_TYPE), ValueChecks.check(json, """{"k":"secret-value"}""").map { it.code })
        assertFalse(ValueChecks.check(json, """["secret-value", 1]""").single().message.contains("secret-value"))
    }

    @Test
    fun containsAndVerify() {
        val set = KeySet(listOf("old-key", "new-key"))
        assertTrue(set.contains("new-key"))
        assertFalse(set.contains("new-ke"))
        assertFalse(set.contains(""))
        val tried = ArrayList<String>()
        assertTrue(set.verify { tried += it.decodeToString(); it.decodeToString() == "old-key" })
        // Every key is tried, even after a match.
        assertEquals(listOf("old-key", "new-key"), tried)
        assertFalse(set.verify { false })
    }

    @Test
    fun declarationRules() {
        val gen = Generator("kotlin", "test", "0")
        fun problems(v: VarSpec) = DeclarationChecks.check(Contract("svc", gen, listOf(v))).errors
        assertEquals(emptyList(), problems(keys))
        assertTrue(problems(keys.copy(secret = false)).any { "always secret" in it })
        assertTrue(problems(keys.copy(minKeys = 0)).any { "minKeys" in it })
        assertTrue(problems(keys.copy(minKeys = 3)).any { "maxKeys" in it })
        assertTrue(problems(keys.copy(keyMinLength = 0)).any { "keyMinLength" in it })
        assertTrue(problems(VarSpec("N", VarType.STRING, "A name", minKeys = 1)).isNotEmpty())
    }

    @Test
    fun exportsEveryField() {
        val cue = CueWriter.write(Contract("svc", Generator("kotlin", "test", "0"), listOf(keys.copy(separator = ";"))))
        for (line in listOf("type: \"keySet\"", "secret: true", "encoding: \"csv\"", "separator: \";\"", "minKeys: 1", "maxKeys: 2", "keyMinLength: 4", "keyMaxLength: 8")) {
            assertTrue(line in cue, "missing $line in\n$cue")
        }
    }
}

class DeprecatedTest {
    private val gen = Generator("kotlin", "test", "0")

    private fun problems(v: VarSpec) = DeclarationChecks.check(Contract("svc", gen, listOf(v))).errors

    @Test
    fun messageRules() {
        val old = VarSpec("OLD_PORT", VarType.INT, "Old listen port", deprecated = Deprecation("Use PORT instead", "PORT"))
        assertEquals(emptyList(), problems(old))
        assertTrue(problems(old.copy(deprecated = Deprecation("  "))).any { "message" in it })
        assertTrue(problems(old.copy(deprecated = Deprecation("x".repeat(501)))).any { "500" in it })
        assertEquals(emptyList(), problems(old.copy(deprecated = Deprecation("x".repeat(500)))))
        assertTrue(problems(old.copy(required = true)).any { "required input cannot be deprecated" in it })
    }

    @Test
    fun warnsWithoutTheValue() {
        val old = VarSpec("OLD_TOKEN", VarType.STRING, "Old token", secret = true, deprecated = Deprecation("The API no longer takes a token"))
        val r = ContractFirst.check(Contract("svc", gen, listOf(old)), mapOf("OLD_TOKEN" to "tok-0123456789"))
        assertIs<ContractFirst.Result.Success>(r)
        assertEquals(listOf("OLD_TOKEN is deprecated: The API no longer takes a token"), r.warnings)
        assertFalse(r.warnings.any { "tok-0123456789" in it })
    }
}

class LayersTest {
    private val gen = Generator("kotlin", "test", "0")
    private val contract = Contract(
        "svc", gen,
        listOf(
            VarSpec("APP_ENV", VarType.STRING, "Profile selector", default = JsonValue.Str("Production")),
            VarSpec("PAGE_SIZE", VarType.INT, "Items per page", default = JsonValue.Int(10), configKey = "Catalog:PageSize"),
            VarSpec("URL", VarType.URL, "Search URL", required = true),
        ),
        profiles = ProfilesSpec("APP_ENV", "Production", mapOf("Production" to mapOf("PAGE_SIZE" to JsonValue.Int(20), "URL" to JsonValue.Str("https://search")))),
    )

    @Test
    fun profileThenEnvironment() {
        val v = ContractFirst.load(contract, emptyMap())
        assertEquals(20L, v.long("PAGE_SIZE"))
        assertEquals("https://search", v.string("URL"))
        assertEquals(30L, ContractFirst.load(contract, mapOf("PAGE_SIZE" to "30")).long("PAGE_SIZE"))
        val other = ContractFirst.check(contract, mapOf("APP_ENV" to "production"))
        assertIs<ContractFirst.Result.Failure>(other)
        assertEquals(listOf("URL" to Codes.MISSING_REQUIRED), other.violations.map { it.input to it.code })
    }

    @Test
    fun overlayValuesAreNative() {
        val overlay = OverlaySpec("platform", ConfigFormat.JSON, "/app/config/platform.json", ":")
        val withOverlay = contract.copy(overlays = listOf(overlay))
        val inputs = object : ContractFirst.Inputs {
            override fun loadFile(spec: FileSpec, env: Map<String, String>) = ContractFirst.LoadedFile(null)
            override fun readOverlay(spec: OverlaySpec, env: Map<String, String>) =
                ContractFirst.LoadedOverlay(JsonValue.parse("""{"Catalog": {"PageSize": 25.0}}""") as JsonValue.Obj)
        }
        assertEquals(25L, ContractFirst.load(withOverlay, emptyMap(), inputs).long("PAGE_SIZE"))
        assertEquals(40L, ContractFirst.load(withOverlay, mapOf("PAGE_SIZE" to "40"), inputs).long("PAGE_SIZE"))
        assertEquals("2.5", ContractFirst.scalarWire(JsonValue.Float(2.5)))
        assertNull(ContractFirst.scalarWire(JsonValue.Arr(emptyList())))
        assertFailsWith<ConfigViolationException> {
            ContractFirst.load(withOverlay, emptyMap(), object : ContractFirst.Inputs by inputs {
                override fun readOverlay(spec: OverlaySpec, env: Map<String, String>) =
                    ContractFirst.LoadedOverlay(JsonValue.parse("""{"Catalog": {"PageSize": {"on": true}}}""") as JsonValue.Obj)
            })
        }
    }
}
