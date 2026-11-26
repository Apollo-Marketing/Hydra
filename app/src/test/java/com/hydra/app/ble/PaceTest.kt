package com.hydra.app.ble

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PaceTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)

    @After
    fun restoreDefault() { BottleMath.dayResetSec = 3 * 3600 }

    @Test
    fun defaultDayRollsOverAtThreeAndPacesSevenToNine() {
        val day = BottleMath.DaySchedule()
        assertEquals(3 * 3600, day.resetSec)
        BottleMath.dayResetSec = day.resetSec
        assertEquals(0f, BottleMath.paceFraction(at(24, 6), day), 0.001f)
        assertEquals(0.5f, BottleMath.paceFraction(at(24, 14), day), 0.001f)
        assertEquals(1f, BottleMath.paceFraction(at(24, 21), day), 0.001f)
        assertEquals(1f, BottleMath.paceFraction(at(25, 1, 30), day), 0.001f)
    }

    @Test
    fun nightOwlDayNineToTwo() {
        val day = BottleMath.DaySchedule(wakeSec = 9 * 3600, bedSec = 2 * 3600)
        // Asleep 2 am to 9 am, so the day turns over at 5 am (5:30 rounded down).
        assertEquals(5 * 3600, day.resetSec)
        BottleMath.dayResetSec = day.resetSec
        // Pace runs 9 am to midnight: 15 hours.
        assertEquals(0f, BottleMath.paceFraction(at(24, 8), day), 0.001f)
        assertEquals(4f / 15f, BottleMath.paceFraction(at(24, 13), day), 0.001f)
        assertEquals(1f, BottleMath.paceFraction(at(25, 0), day), 0.001f)
        // 2 am on the 25th is still the 24th; 6 am is the 25th, before wake-up.
        assertEquals(LocalDate.of(2026, 11, 24), BottleMath.logicalDateFor(at(25, 2).toInstant(), zone))
        assertEquals(1f, BottleMath.paceFraction(at(25, 2), day), 0.001f)
        assertEquals(LocalDate.of(2026, 11, 25), BottleMath.logicalDateFor(at(25, 6).toInstant(), zone))
        assertEquals(0f, BottleMath.paceFraction(at(25, 6), day), 0.001f)
    }

    @Test
    fun earlyBedWithWakeAfterMidnight() {
        // Bed 8 pm, up at 2 am: the day turns over at 11 pm and wake-up falls on the next calendar day.
        val day = BottleMath.DaySchedule(wakeSec = 2 * 3600, bedSec = 20 * 3600)
        assertEquals(23 * 3600, day.resetSec)
        BottleMath.dayResetSec = day.resetSec
        val logical = BottleMath.logicalDateFor(at(25, 3).toInstant(), zone)
        assertEquals(LocalDate.of(2026, 11, 24), logical)
        assertEquals(1f / 16f, BottleMath.paceFraction(at(25, 3), day), 0.001f)
    }
}

class SleepHoursTest {
    private val schedule = BottleMath.DaySchedule(wakeSec = 9 * 3600, bedSec = 1 * 3600)

    @org.junit.Test fun `asleep between bedtime and wake-up`() {
        org.junit.Assert.assertTrue(schedule.isAsleep(1 * 3600))
        org.junit.Assert.assertTrue(schedule.isAsleep(5 * 3600))
        org.junit.Assert.assertFalse(schedule.isAsleep(9 * 3600))
        org.junit.Assert.assertFalse(schedule.isAsleep(23 * 3600))
        org.junit.Assert.assertFalse(schedule.isAsleep(0))
    }

    @org.junit.Test fun `sleep hours that cross midnight`() {
        val early = BottleMath.DaySchedule(wakeSec = 7 * 3600, bedSec = 23 * 3600)
        org.junit.Assert.assertTrue(early.isAsleep(23 * 3600 + 60))
        org.junit.Assert.assertTrue(early.isAsleep(3 * 3600))
        org.junit.Assert.assertFalse(early.isAsleep(12 * 3600))
    }
}
