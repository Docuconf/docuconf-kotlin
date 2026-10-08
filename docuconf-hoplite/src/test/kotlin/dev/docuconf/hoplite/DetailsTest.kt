package dev.docuconf.hoplite

import dev.docuconf.kotlin.core.DeclarationException
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shop settings, documented with KDoc (indexed in src/test/resources/META-INF/docuconf/kdoc.properties) and @Doc. */
data class ShopConfig(
    @Min(1) val workers: Int = 4,
    @Doc("Cloud region", details = "Set by the **platform**.") val region: String = "eu",
    @Doc("Availability zone") val zone: String = "a",
    val limits: Limits = Limits(),
) {
    data class Limits(@Min(1) val maxItems: Int = 10)
}

data class NoDescriptionConfig(val workers: Int = 4)

data class LongDetailsConfig(val workers: Int = 4)

class DetailsTest {
    private fun read(type: kotlin.reflect.KClass<*>) = DeclarationReader.read(type).vars.associate { it.spec.name to it.spec }

    @Test
    fun descriptionIsTheFirstSentenceOfTheKDocAndDetailsTheRest() {
        val vars = read(ShopConfig::class)
        assertEquals("Background workers that process orders.", vars.getValue("WORKERS").description)
        assertEquals(
            "Each holds one connection to `region`'s database.\n\n- Raise it when the queue backs up.\n- Lower it when the database is busy.\n\n```sh\nWORKERS=8 [not a link]\n```",
            vars.getValue("WORKERS").details,
        )
        assertEquals("Most items in one order", vars.getValue("LIMITS_MAX_ITEMS").description)
        assertEquals("Orders above it are split.", vars.getValue("LIMITS_MAX_ITEMS").details)
    }

    @Test
    fun docWinsOverTheKDoc() {
        val vars = read(ShopConfig::class)
        assertEquals("Cloud region", vars.getValue("REGION").description)
        assertEquals("Set by the **platform**.", vars.getValue("REGION").details)
        assertEquals("Availability zone", vars.getValue("ZONE").description)
        assertEquals("One of the region's zones.", vars.getValue("ZONE").details)
    }

    @Test
    fun detailsAreExportedAfterTheDescription(@TempDir dir: Path) {
        val cue = Docuconf.exportCue(ShopConfig::class, "shop") { warn = {} }
        assertContains(cue, "description: \"Background workers that process orders.\"\n\t\t\tdetails: \"Each holds one connection")
        assertContains(cue, "description: \"Cloud region\"\n\t\t\tdetails: \"Set by the **platform**.\"")
        val (code, output) = Cue.run(Cue.module(dir, cue), dir, "vet", "-c", "./svc")
        assertEquals(0, code, output)
    }

    @Test
    fun aMissingDescriptionFails() {
        val e = assertFailsWith<DeclarationException> { DeclarationReader.read(NoDescriptionConfig::class) }
        assertTrue(e.problems.any { it.startsWith("NoDescriptionConfig.workers: add @Doc(\"...\") with a description of at least 5 characters, or a KDoc comment") }, e.problems.toString())
    }

    @Test
    fun detailsOver4000CodePointsFail() {
        val e = assertFailsWith<DeclarationException> { Docuconf.exportCue(LongDetailsConfig::class, "long") { warn = {} } }
        assertTrue(e.problems.any { it.endsWith("details are 4002 characters (Unicode code points); the most is 4000") }, e.problems.toString())
    }

    @Test
    fun kdocSplitAndConversion() {
        assertEquals("First." to null, KDocs.split("First.\n\n@param x ignored"))
        assertEquals("Uses Name" to "Then `Other.thing`, a [link](https://example.com) and `[kept]`.", KDocs.split("Uses [Name]\n\nThen [Other.thing], a [link](https://example.com) and `[kept]`."))
        assertNull(KDocs.split("@property x only tags").first)
    }
}
