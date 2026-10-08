package dev.docuconf.kotlin.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `details` (SPEC §4.2): docs only, not blank, at most 4000 characters (Unicode code points). */
class DetailsTest {
    private fun contract(details: String) = """
        {"apiVersion": "docuconf.dev/v1alpha1", "kind": "ConfigContract", "metadata": {"name": "svc"},
         "vars": {"PORT": {"type": "int", "description": "Port to listen on", "details": "$details", "default": 8080}}}
    """

    @Test
    fun countsCodePoints() {
        assertNull(DeclarationChecks.detailsProblem(null))
        assertNull(DeclarationChecks.detailsProblem("日本".repeat(2000)))
        assertEquals("details are 4001 characters (Unicode code points); the most is 4000", DeclarationChecks.detailsProblem("日本".repeat(2000) + "語"))
        assertEquals("details must not be blank", DeclarationChecks.detailsProblem(" \n "))
    }

    @Test
    fun contractFirstLoadsDetailsAndIgnoresThem() {
        val c = ContractFirst.parse(contract("# Why\\n\\nBehind the mesh, keep the default."))
        assertEquals("# Why\n\nBehind the mesh, keep the default.", c.vars.single().details)
        assertEquals(9090L, (ContractFirst.load(c, mapOf("PORT" to "9090"))["PORT"] as Number).toLong())
    }

    @Test
    fun contractFirstRejectsBlankOrLongDetails() {
        assertContains(assertFailsWith<DeclarationException> { ContractFirst.parse(contract("  ")) }.problems, "PORT: details must not be blank")
        val e = assertFailsWith<DeclarationException> { ContractFirst.parse(contract("日本".repeat(2001))) }
        assertTrue(e.problems.any { it.startsWith("PORT: details are 4002 characters") }, e.problems.toString())
    }

    @Test
    fun detailsAreWrittenAfterTheDescription() {
        val c = ContractFirst.parse(contract("Longer docs."))
        val cue = CueWriter.write(c)
        assertContains(cue, "description: \"Port to listen on\"\n\t\t\tdetails: \"Longer docs.\"\n")
    }
}
