package dev.docuconf.kotlin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ContractFirstTest {
    private val contract = ContractFirst.parse(
        """
        {
          "apiVersion": "docuconf.dev/v1alpha1",
          "kind": "ConfigContract",
          "metadata": {"name": "svc", "generator": {"language": "cue", "sdk": "hand", "version": "1"}},
          "vars": {
            "PORT": {"type": "int", "description": "Port to listen on", "min": 1, "max": 65535, "default": 8080},
            "RATIO": {"type": "float", "description": "Sampling ratio", "min": 0, "max": 1},
            "DEBUG": {"type": "bool", "description": "Debug endpoints"},
            "TIMEOUT": {"type": "duration", "description": "Request timeout", "encoding": "timespan", "default": "30s"},
            "ISO": {"type": "duration", "description": "ISO duration", "encoding": "iso8601"},
            "SECS": {"type": "duration", "description": "Duration in seconds", "encoding": "seconds"},
            "SHARDS": {"type": "list", "description": "Shard ids", "items": "int", "encoding": "indexed", "itemMin": 0, "itemMax": 1023},
            "TAGS": {"type": "list", "description": "Tags to apply", "items": "string", "encoding": "csv", "separator": ";"},
            "IDS": {"type": "list", "description": "Ids as JSON", "items": "int", "encoding": "json"},
            "TOKEN": {"type": "string", "description": "API token", "secret": true, "minLength": 10},
            "LIMITS": {"type": "json", "description": "Rate limits", "schema": {"type": "object", "required": ["perMinute"]}}
          }
        }
        """,
    )

    @Test
    fun parsesEveryEncoding() {
        val v = ContractFirst.load(
            contract,
            mapOf(
                "RATIO" to "0.5", "DEBUG" to "TRUE", "TIMEOUT" to "1.02:03:04.5", "ISO" to "PT1.5S", "SECS" to "0.25",
                "SHARDS__0" to "3", "SHARDS__1" to "1023", "SHARDS__3" to "9",
                "TAGS" to "a,b;c", "IDS" to "[1,2]", "TOKEN" to "0123456789", "LIMITS" to """{"perMinute":60}""",
            ),
        )
        assertEquals(8080L, v.long("PORT"))
        assertEquals(0.5, v.double("RATIO"))
        assertEquals(true, v.boolean("DEBUG"))
        assertEquals("26h3m4s500ms", Durations.formatGo(v.duration("TIMEOUT")!!.inWholeNanoseconds))
        assertEquals(1500.milliseconds, v.duration("ISO"))
        assertEquals(250.milliseconds, v.duration("SECS"))
        assertEquals(listOf(3L, 1023L), v.longList("SHARDS"), "indexed items stop at the first missing index")
        assertEquals(listOf("a,b", "c"), v.stringList("TAGS"))
        assertEquals(listOf(1L, 2L), v.longList("IDS"))
        assertEquals("0123456789", v.string("TOKEN"))
        assertEquals(JsonValue.parse("""{"perMinute":60}"""), v.json("LIMITS"))
        assertTrue("0123456789" !in v.toString(), "secrets are masked in toString")
        assertEquals("""{"DEBUG":true,"IDS":[1,2],"ISO":"1s500ms","LIMITS":{"perMinute":60},"PORT":8080,"RATIO":0.5,"SECS":"250ms","SHARDS":[3,1023],"TAGS":["a,b","c"],"TIMEOUT":"26h3m4s500ms","TOKEN":"0123456789"}""", v.toJson().toString())
    }

    @Test
    fun defaultsAndAbsentValues() {
        val v = ContractFirst.load(contract, emptyMap())
        assertEquals(8080L, v.long("PORT"))
        assertEquals(30.seconds, v.duration("TIMEOUT"))
        assertNull(v.longList("SHARDS"))
        assertNull(v["TOKEN"])
        assertFailsWith<IllegalArgumentException> { v.long("NOPE") }
        assertFailsWith<IllegalArgumentException> { v.string("PORT") }
    }

    @Test
    fun reportsEveryViolation() {
        val r = ContractFirst.check(
            contract,
            mapOf(
                "PORT" to "99999999999999999999", "RATIO" to "1,5", "SHARDS__0" to "1024", "IDS" to "[\"1\"]",
                "TOKEN" to "short-tok", "LIMITS" to "{}", "SECS" to "90s",
            ),
        )
        assertIs<ContractFirst.Result.Failure>(r)
        assertEquals(
            setOf(
                "PORT:out_of_range", "RATIO:invalid_type", "SHARDS:out_of_range", "IDS:invalid_type",
                "TOKEN:out_of_range", "LIMITS:schema_mismatch", "SECS:invalid_type",
            ),
            r.violations.map { "${it.input}:${it.code}" }.toSet(),
        )
        assertTrue(r.violations.none { "short-tok" in it.message })
    }

    @Test
    fun rejectsInvalidContracts() {
        assertFailsWith<DeclarationException> { ContractFirst.parse("{") }
        val e = assertFailsWith<DeclarationException> {
            ContractFirst.parse(
                """{"metadata": {"name": "svc"}, "vars": {"NAMES": {"type": "list", "description": "Some names", "items": "string", "itemMin": 0}}}""",
            )
        }
        assertTrue(e.problems.any { "itemMin" in it }, e.problems.toString())
    }
}
