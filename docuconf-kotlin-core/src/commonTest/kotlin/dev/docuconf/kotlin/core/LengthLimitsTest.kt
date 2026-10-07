package dev.docuconf.kotlin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** maxLength on url and json, itemMinLength/itemMaxLength on string lists (SPEC §4.3), in code points. */
class LengthLimitsTest {
    private val url = VarSpec("CALLBACK", VarType.URL, "Where to report each run", schemes = listOf("https"), maxLength = 24)
    private val json = VarSpec("LIMITS", VarType.JSON, "Run limits", maxLength = 16)
    private val branches = VarSpec("BRANCHES", VarType.LIST, "Branch codes", items = ListItems.STRING, itemMinLength = 2, itemMaxLength = 4)

    // One code point, two UTF-16 units.
    private val rocket = "🚀"

    @Test
    fun urlMaxLengthCountsCodePoints() {
        assertEquals(emptyList(), ValueChecks.check(url, "https://a.example/runs/4"))
        assertEquals(listOf(Codes.OUT_OF_RANGE), ValueChecks.check(url, "https://a.example/runs/42").map { it.code })
        assertEquals(emptyList(), ValueChecks.check(url, "https://例え.jp/日本語の道/一二三四"))
        assertEquals(listOf(Codes.OUT_OF_RANGE), ValueChecks.check(url, "https://例え.jp/日本語の道/一二三四五").map { it.code })
        // A secret reports its length, never its value.
        val secret = VarSpec("DB_URL", VarType.URL, "Database URL", secret = true, maxLength = 30)
        val v = ValueChecks.check(secret, "postgres://app:s3cr3t@db:5432/app").single()
        assertEquals(Codes.OUT_OF_RANGE, v.code)
        assertEquals("the value is 33 characters, above maxLength 30", v.message)
    }

    @Test
    fun jsonMaxLengthMeasuresTheValueAsReceived() {
        assertEquals(emptyList(), ValueChecks.check(json, "{\"max\":12345678}"))
        assertEquals(listOf(Codes.OUT_OF_RANGE), ValueChecks.check(json, "{\"max\":123456789}").map { it.code })
        assertEquals(emptyList(), ValueChecks.check(json, "{\"n\":\"日本語の道路xy\"}"))
        val ws = ValueChecks.check(json, "{ \"max\": 123456 }").single()
        assertEquals("is 17 characters of JSON, above maxLength 16", ws.message)
    }

    @Test
    fun itemLengthsApplyToEachItemAfterSplitting() {
        assertEquals(emptyList(), ValueChecks.check(branches, "BE,ZÜ01,日本,$rocket$rocket"))
        assertEquals(listOf(Codes.OUT_OF_RANGE), ValueChecks.check(branches, "BE,ZÜRICH").map { it.code })
        assertEquals(listOf(Codes.OUT_OF_RANGE), ValueChecks.check(branches, "BE,B").map { it.code })
        assertEquals(listOf(Codes.OUT_OF_RANGE), ValueChecks.check(branches, rocket).map { it.code })
        val jsonList = branches.copy(listEncoding = ListEncoding.JSON)
        assertEquals(listOf(Codes.OUT_OF_RANGE), ValueChecks.check(jsonList, "[\"BE\",\"GENEVA\"]").map { it.code })
        assertEquals(listOf(Codes.OUT_OF_RANGE), ValueChecks.parse(branches.copy(listEncoding = ListEncoding.INDEXED), null, items = listOf("BE", "GENEVA")).violations.map { it.code })
    }

    @Test
    fun exportsThem() {
        val cue = CueWriter.write(Contract("svc", Generator("kotlin", "t", "0"), listOf(url, json, branches)))
        assertTrue("maxLength: 24" in cue && "maxLength: 16" in cue, cue)
        assertTrue("itemMinLength: 2" in cue && "itemMaxLength: 4" in cue, cue)
    }

    @Test
    fun declarationErrors() {
        val c = Contract(
            "svc", Generator("kotlin", "t", "0"),
            listOf(
                VarSpec("PORTS", VarType.LIST, "Worker ports", items = ListItems.INT, itemMaxLength = 4),
                branches.copy(name = "CODES", itemMinLength = 5, itemMaxLength = 1),
                VarSpec("PORT", VarType.INT, "Listen port", maxLength = 5),
                url.copy(name = "HOOK", minLength = 1),
                url.copy(name = "DEF", default = JsonValue.Str("https://a.example/long/path")),
                json.copy(name = "JDEF", default = JsonValue.parse("{\"max\":123456789}")),
                branches.copy(name = "TDEF", default = JsonValue.parse("[\"BE\",\"ZÜRICH\"]")),
            ),
        )
        val errors = DeclarationChecks.check(c).errors.joinToString("\n")
        assertTrue("PORTS: itemMinLength and itemMaxLength only apply to lists of string" in errors, errors)
        assertTrue("CODES: itemMinLength is greater than itemMaxLength" in errors, errors)
        assertTrue("PORT: maxLength only applies to strings, urls and json" in errors, errors)
        assertTrue("HOOK: minLength only applies to strings" in errors, errors)
        assertTrue("DEF: default violates its own constraints" in errors && "above maxLength 24" in errors, errors)
        assertTrue("JDEF: default violates its own constraints: is 17 characters of JSON" in errors, errors)
        assertTrue("TDEF: default violates its own constraints" in errors && "above itemMaxLength 4" in errors, errors)
    }

    @Test
    fun contractFirst() {
        val contract = ContractFirst.parse(
            """
            {"apiVersion": "docuconf.dev/v1alpha1", "kind": "ConfigContract", "metadata": {"name": "svc"},
             "vars": {
               "CALLBACK": {"type": "url", "description": "Where to report each run", "maxLength": 24},
               "LIMITS": {"type": "json", "description": "Run limits", "maxLength": 16},
               "BRANCHES": {"type": "list", "description": "Branch codes", "items": "string", "encoding": "csv",
                            "itemMinLength": 2, "itemMaxLength": 4}}}
            """,
        )
        val ok = ContractFirst.check(contract, mapOf("BRANCHES" to "ZÜ01,日本", "LIMITS" to "{\"max\":1}"))
        assertIs<ContractFirst.Result.Success>(ok)
        val bad = ContractFirst.check(contract, mapOf("CALLBACK" to "https://a.example/runs/42", "LIMITS" to "{ \"max\": 123456 }", "BRANCHES" to "BE,B"))
        assertIs<ContractFirst.Result.Failure>(bad)
        assertEquals(listOf("BRANCHES", "CALLBACK", "LIMITS"), bad.violations.map { it.input }.sorted())
        assertTrue(bad.violations.all { it.code == Codes.OUT_OF_RANGE })

        val ints = assertFailsWith<DeclarationException> {
            ContractFirst.parse(
                """
                {"apiVersion": "docuconf.dev/v1alpha1", "kind": "ConfigContract", "metadata": {"name": "svc"},
                 "vars": {"PORTS": {"type": "list", "description": "Worker ports", "items": "int", "itemMaxLength": 4}}}
                """,
            )
        }
        assertFalse(ints.message.isNullOrEmpty())
        assertTrue("itemMinLength and itemMaxLength only apply to lists of string" in ints.message!!, ints.message)
    }
}
