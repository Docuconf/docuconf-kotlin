package dev.docuconf.hoplite

import dev.docuconf.kotlin.core.Codes
import dev.docuconf.kotlin.core.ConfigViolationException
import dev.docuconf.kotlin.core.DeclarationException
import dev.docuconf.kotlin.core.ListEncoding
import dev.docuconf.kotlin.core.VarType
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** keySet, deprecated rules, strict parsing and config keys in declaration mode. */
class BetaTest {
    data class KeyConfig(
        @Doc("Keys that verify webhook signatures") @KeyLength(min = 8, max = 64) val webhookKeys: KeySet,
        @Doc("Keys callers present") @Keys(min = 1, max = 3) @Separator(";") val apiKeys: KeySet? = null,
    )

    @Test
    fun keySetsLoadInOrderAndStaySecret() {
        val cfg = Docuconf.load<KeyConfig> { env = mapOf("WEBHOOK_KEYS" to "old-key-123, new-key-456", "API_KEYS" to "a;b;c") }
        assertEquals(listOf("old-key-123", " new-key-456"), cfg.webhookKeys.keys)
        assertEquals(listOf("a", "b", "c"), cfg.apiKeys!!.keys)
        assertTrue(cfg.webhookKeys.contains(" new-key-456"))
        assertFalse(cfg.toString().contains("old-key"))

        val c = Docuconf.contract(KeyConfig::class, "svc")
        val spec = c.variable("WEBHOOK_KEYS")!!
        assertEquals(VarType.KEY_SET, spec.type)
        assertTrue(spec.secret)
        assertEquals(ListEncoding.CSV, spec.listEncoding)
        assertEquals(8, spec.keyMinLength)
        assertEquals(";", c.variable("API_KEYS")!!.separator)
        assertEquals(3, c.variable("API_KEYS")!!.maxKeys)
    }

    @Test
    fun keySetErrorsNeverShowAKey() {
        val e = assertFailsWith<ConfigViolationException> {
            Docuconf.load<KeyConfig> { env = mapOf("WEBHOOK_KEYS" to "old-key-123,", "API_KEYS" to "a;b;c;d"); terminationLog = "/dev/null" }
        }
        assertEquals(
            listOf("API_KEYS" to Codes.TOO_MANY_ITEMS, "WEBHOOK_KEYS" to Codes.OUT_OF_RANGE),
            e.violations.map { it.input to it.code }.sortedBy { it.first },
        )
        assertContains(e.message!!, "WEBHOOK_KEYS: out_of_range: key 2 is empty")
        assertFalse(e.message!!.contains("old-key-123"))
    }

    data class WrongAnnotations(@Doc("Keys that verify webhook signatures") @Items(max = 2) val keys: KeySet)

    @Test
    fun listAnnotationsOnAKeySetPointAtTheKeySetOnes() {
        val e = assertFailsWith<DeclarationException> { Docuconf.contract(WrongAnnotations::class, "svc") }
        assertContains(e.message!!, "@Keys(min, max)")
    }

    data class RequiredDeprecated(@Doc("Old listen port") @DeprecatedInput("Use PORT instead") val oldPort: Int)

    data class BlankDeprecated(@Doc("Old listen port") @DeprecatedInput(" ") val oldPort: Int? = null)

    @Test
    fun deprecationRulesAreCheckedWhenDeclared() {
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(RequiredDeprecated::class, "svc") }.message!!, "required input cannot be deprecated")
        assertContains(assertFailsWith<DeclarationException> { Docuconf.contract(BlankDeprecated::class, "svc") }.message!!, "deprecated needs a message")
    }

    data class Deprecated(
        @Doc("Old listen port") @DeprecatedInput("Use PORT instead", replacedBy = "PORT") val oldPort: Int? = null,
        @Doc("Token of the old API") @DeprecatedInput("The API no longer takes a token") val oldToken: com.sksamuel.hoplite.Secret? = null,
    )

    @Test
    fun aDeprecatedInputThatIsSetWarnsWithoutItsValue() {
        val warnings = ArrayList<String>()
        val cfg = Docuconf.load<Deprecated> { env = mapOf("OLD_PORT" to "9090", "OLD_TOKEN" to "tok-0123456789"); warn = { warnings += it } }
        assertEquals(9090, cfg.oldPort)
        assertEquals(
            listOf("OLD_PORT is deprecated: Use PORT instead (replaced by PORT)", "OLD_TOKEN is deprecated: The API no longer takes a token"),
            warnings,
        )
    }

    data class Strict(
        @Doc("Serve the debug endpoints") val debug: Boolean = false,
        @Doc("Worker count") val workers: Int = 1,
        @Doc("Names to greet") val names: List<String> = emptyList(),
    )

    @Test
    fun parsingIsExact() {
        val cfg = Docuconf.load<Strict> { env = mapOf("DEBUG" to "TRUE", "WORKERS" to "+007", "NAMES" to "a, b") }
        assertEquals(true, cfg.debug)
        assertEquals(7, cfg.workers)
        assertEquals(listOf("a", " b"), cfg.names)
        for ((name, raw) in listOf("DEBUG" to "yes", "DEBUG" to "1", "WORKERS" to "0x10", "WORKERS" to " 5")) {
            val e = assertFailsWith<ConfigViolationException>("$name=$raw") { Docuconf.load<Strict> { env = mapOf(name to raw); terminationLog = "/dev/null" } }
            assertEquals(listOf(name to Codes.INVALID_TYPE), e.violations.map { it.input to it.code })
        }
    }
}
