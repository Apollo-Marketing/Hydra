package com.hydra.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TofLogParsingTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Value bytes of a real ResponseGetCapTofLog captured from a PureVis 2 at MTU 247. */
    private val sevenEntriesWithPaddedTail = hex(
        "0a17 08c2edcbd406 1004 1d35000000 250e090000 2d9ea84346" +
            "0a17 08caedcbd406 1004 1d33000000 25b6080000 2d568f4546" +
            "0a17 08d0edcbd406 1004 1d35000000 25180a0000 2dc8894446" +
            "0a17 08d7edcbd406 1004 1d36000000 2524090000 2d02414246" +
            "0a17 08e6edcbd406 1004 1d35000000 25ec080000 2d6e424346" +
            "0a17 08ecedcbd406 1004 1d33000000 25c8080000 2db86d4446" +
            "0a17 08faedcbd406 1004 1d35000000 25350a0000 2d76cd4446" +
            "0a17 0a17 0a17", // bottle pads a full notification with truncated entry headers
    )

    @Test
    fun parsesEveryEntryInAPackedResponse() {
        val entries = Responses.parseTofLogEntries(sevenEntriesWithPaddedTail)
        assertEquals(
            listOf(1788016322L, 1788016330L, 1788016336L, 1788016343L, 1788016358L, 1788016364L, 1788016378L),
            entries.map { it.timestampSec },
        )
        assertEquals(listOf(53, 51, 53, 54, 53, 51, 53), entries.map { it.distanceMm })
        assertEquals(4, entries.first().triggerType)
        assertEquals(2318, entries.first().kcps)
    }

    @Test
    fun parsesASingleEntryResponse() {
        // The 100 mL sip from the original snoop capture (one entry per notification).
        val entries = Responses.parseTofLogEntries(hex("0a17 089acfe0cf06 1004 1d46000000 252e000000 2d56ca2446"))
        assertEquals(1, entries.size)
        assertEquals(SipEntry(1777870746L, 4, 70, 46, 10546.584f), entries.single())
    }

    @Test
    fun emptyAndGarbageInputYieldNoEntries() {
        assertTrue(Responses.parseTofLogEntries(ByteArray(0)).isEmpty())
        assertTrue(Responses.parseTofLogEntries(hex("0a17 0a17 0a17")).isEmpty())
        assertTrue(Responses.parseTofLogEntries(hex("ff ff ff ff")).isEmpty())
        assertTrue(Responses.parseTofLogEntries(hex("0a ff ff ff ff ff ff ff ff ff ff ff")).isEmpty())
    }
}
