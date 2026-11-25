package com.hydra.app.ble

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PaceTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)

    @Test
    fun paceRisesEvenlyFromSevenToNine() {
        assertEquals(0f, BottleMath.paceFraction(at(24, 6)), 0.001f)
        assertEquals(0f, BottleMath.paceFraction(at(24, 7)), 0.001f)
        assertEquals(6f / 14f, BottleMath.paceFraction(at(24, 13)), 0.001f)
        assertEquals(0.5f, BottleMath.paceFraction(at(24, 14)), 0.001f)
        assertEquals(1f, BottleMath.paceFraction(at(24, 21)), 0.001f)
        assertEquals(1f, BottleMath.paceFraction(at(24, 23, 30)), 0.001f)
    }

    @Test
    fun afterMidnightTheDayBeingShownIsFullyDue() {
        // 1:30 am on the 25th still belongs to the 24th's logical day.
        assertEquals(1f, BottleMath.paceFraction(at(25, 1, 30)), 0.001f)
        assertEquals(1f, BottleMath.paceFraction(at(25, 1, 30), LocalDate.of(2026, 11, 24)), 0.001f)
    }
}
