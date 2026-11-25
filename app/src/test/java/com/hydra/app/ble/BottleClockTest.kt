package com.hydra.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BottleClockTest {
    private val mst = -7 * 3600

    @Test
    fun localTimesConvertToUtcAndBack() {
        // 10:00 pm Mountain Standard Time is 05:00 UTC — the value read off the real bottle.
        assertEquals(18_000, BottleClock.localToBottleSec(22 * 3600, mst))
        assertEquals(22 * 3600, BottleClock.bottleToLocalSec(18_000, mst))
        assertEquals(6 * 3600, BottleClock.bottleToLocalSec(46_800, mst))
    }

    @Test
    fun utcMidnightIsSentAs86400() {
        assertEquals(86_400, BottleClock.localToBottleSec(17 * 3600, mst))
        assertEquals(17 * 3600, BottleClock.bottleToLocalSec(86_400, mst))
    }

    @Test
    fun quietHoursPayloadMatchesWhatTheBottleReported() {
        // The bottle answered GetCapDoNotDisturbSettings with state=2, 18000 → 46800.
        val payload = RequestPayloads.setDoNotDisturb(DoNotDisturbSettings(DoNotDisturbState.AUTO, 2, 18_000, 46_800, 0))
        val expected = byteArrayOf(0x0a, 0x0c, 0x08, 0x02, 0x15, 0x50, 0x46, 0, 0, 0x1d, 0xd0.toByte(), 0xb6.toByte(), 0, 0)
        assertArrayEquals(expected, payload)
    }

    @Test
    fun activationLogStopsAtTruncatedTail() {
        val bytes = byteArrayOf(0x0a, 0x09, 0x08, 0x05, 0x10, 0x01, 0x1d, 0x34, 0, 0, 0, 0x0a, 0x09, 0x0a, 0x09)
        assertEquals(listOf(UvActivation(5, 1, 52)), Responses.parseActivationLog(bytes))
    }
}
