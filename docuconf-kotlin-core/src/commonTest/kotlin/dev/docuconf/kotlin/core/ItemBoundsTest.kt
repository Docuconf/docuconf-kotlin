package dev.docuconf.kotlin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ItemBoundsTest {
    private val spec = VarSpec("SHARDS", VarType.LIST, "Shard ids", items = ListItems.INT, itemMin = 0, itemMax = 1023)

    @Test
    fun checksEveryItem() {
        assertEquals(emptyList(), ValueChecks.check(spec, "0,1023"))
        assertEquals(listOf(Codes.OUT_OF_RANGE, Codes.OUT_OF_RANGE), ValueChecks.check(spec, "-1,5,1024").map { it.code })
        assertEquals(listOf(Codes.INVALID_TYPE), ValueChecks.check(spec, "1,x").map { it.code })
        // An integer beyond 64 bits is out_of_range, not invalid_type (SPEC §5).
        assertEquals(listOf(Codes.OUT_OF_RANGE), ValueChecks.check(spec.copy(itemMin = null, itemMax = null), "99999999999999999999").map { it.code })
        val json = spec.copy(listEncoding = ListEncoding.JSON)
        assertEquals(emptyList(), ValueChecks.check(json, "[0,1023]"))
        assertEquals(listOf(Codes.INVALID_TYPE), ValueChecks.check(json, "[\"1\"]").map { it.code }, "a JSON string is not an int item")
        assertEquals(listOf(Codes.OUT_OF_RANGE), ValueChecks.check(json, "[99999999999999999999]").map { it.code })
        assertEquals(listOf(Codes.INVALID_TYPE), ValueChecks.check(spec.copy(items = ListItems.STRING, itemMin = null, itemMax = null, listEncoding = ListEncoding.JSON), "[1]").map { it.code })
    }

    @Test
    fun exportsThem() {
        val cue = CueWriter.write(Contract("svc", Generator("kotlin", "t", "0"), listOf(spec)))
        assertTrue("itemMin: 0" in cue && "itemMax: 1023" in cue, cue)
    }
}
