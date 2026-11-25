package com.hydra.app.ble

import com.hydra.app.data.SipEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelEventsTest {
    private fun reading(t: Long, distanceMm: Int) = SipEntity(t, 4, distanceMm, 0, 0f, 0L)
    private fun manual(t: Long, ml: Float) = SipEntity(t, SipEntity.TRIGGER_MANUAL, 0, 0, 0f, 0L, ml)
    private fun vol(d: Int) = BottleMath.volumeMl(d, 1000)!!

    @Test
    fun smallSipsAccumulateUntilTheyPassTheDrinkThreshold() {
        // 100 → 103 mm is under 25 mL on its own; by 108 mm the total drop is over it.
        assertTrue(vol(100) - vol(103) < BottleMath.DRINK_THRESHOLD_ML)
        assertTrue(vol(100) - vol(108) > BottleMath.DRINK_THRESHOLD_ML)
        val events = BottleMath.levelEvents(listOf(reading(10, 100), reading(20, 103), reading(30, 108)), 1000)
        assertEquals(1, events.size)
        assertEquals(BottleMath.LevelEventKind.Drink, events[0].kind)
        assertEquals(30L, events[0].timestampSec)
        assertEquals(vol(100) - vol(108), events[0].volumeMl, 0.001)
    }

    @Test
    fun bigRiseIsARefillAndSmallRiseIsIgnored() {
        val events = BottleMath.levelEvents(
            listOf(reading(10, 200), reading(20, 190), reading(30, 40), reading(40, 80)),
            1000,
        )
        // 200 → 190 rises less than 200 mL: ignored. 190 → 40 is a refill. 40 → 80 is a drink.
        assertEquals(listOf(BottleMath.LevelEventKind.Refill, BottleMath.LevelEventKind.Drink), events.map { it.kind })
        assertEquals(vol(40) - vol(190), events[0].volumeMl, 0.001)
        assertEquals(vol(40) - vol(80), events[1].volumeMl, 0.001)
    }

    @Test
    fun manualSipsCountAsIsAndOutOfRangeReadingsAreIgnored() {
        val events = BottleMath.levelEvents(
            listOf(reading(10, 100), manual(15, 250f), reading(20, 275), reading(30, 101)),
            1000,
        )
        assertEquals(1, events.size)
        assertEquals(BottleMath.LevelEventKind.Manual, events[0].kind)
        assertEquals(250.0, events[0].volumeMl, 0.001)
    }
}
