package com.hydra.app.filter

import com.hydra.app.ble.BottleMath
import com.hydra.app.data.ActiveFilter
import com.hydra.app.data.FilterEntity
import com.hydra.app.data.FilterPauseEntity
import com.hydra.app.data.SipEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class FilterMathTest {

    private val zone = ZoneId.of("America/Chicago")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12): Long =
        LocalDateTime.of(y, m, d, h, 0).atZone(zone).toEpochSecond()

    private fun filter(installed: Long, type: FilterType = FilterType.Essential, pauses: List<FilterPauseEntity> = emptyList()) =
        ActiveFilter(FilterEntity(id = 1, type = type.key, installedAtSec = installed), pauses)

    /** A bottle reading at [distanceMm]; consecutive readings with rising distance are drinks. */
    private fun reading(ts: Long, distanceMm: Int) = SipEntity(ts, 4, distanceMm, 0, 0f, 0L)

    private fun drinkMl(fromMm: Int, toMm: Int) =
        BottleMath.volumeMl(fromMm, 1000)!! - BottleMath.volumeMl(toMm, 1000)!!

    @Test
    fun oldFilterIsDepletedByAgeEvenWithLittleWaterThroughIt() {
        // The official app's screenshot: Essential, installed Apr 25 2026, viewed Nov 25 2026,
        // 15.7 L filtered → 0 %, 214 days, depleted.
        val h = FilterMath.health(filter(at(2026, 4, 25)), emptyList(), 1000, nowSec = at(2026, 11, 25), zone = zone)
        assertEquals(0, h.percent)
        assertEquals(FilterStatus.ReplaceNow, h.status)
        assertEquals(214, h.ageDays)
        assertEquals(83, FilterMath.volumeGauge(15.7, 94.0).first)
    }

    @Test
    fun ageGaugeStages() {
        val installed = at(2026, 1, 1)
        val day = 86_400L
        assertEquals(100 to FilterStatus.Good, FilterMath.ageGauge(installed, installed, zone))
        assertEquals(50 to FilterStatus.Good, FilterMath.ageGauge(installed, installed + 30 * day, zone))
        assertEquals(FilterStatus.Good, FilterMath.ageGauge(installed, installed + 48 * day - 1, zone).second)
        assertEquals(20 to FilterStatus.ReplaceSoon, FilterMath.ageGauge(installed, installed + 48 * day, zone))
        assertEquals(0 to FilterStatus.ReplaceNow, FilterMath.ageGauge(installed, installed + 60 * day, zone))
    }

    @Test
    fun volumeGaugeStages() {
        assertEquals(100 to FilterStatus.Good, FilterMath.volumeGauge(0.0, 94.0))
        assertEquals(21 to FilterStatus.Good, FilterMath.volumeGauge(94.0 * 0.79, 94.0))
        assertEquals(20 to FilterStatus.ReplaceSoon, FilterMath.volumeGauge(94.0 * 0.80, 94.0))
        assertEquals(1 to FilterStatus.ReplaceSoon, FilterMath.volumeGauge(93.99, 94.0))
        assertEquals(0 to FilterStatus.ReplaceNow, FilterMath.volumeGauge(94.0, 94.0))
        assertEquals(0 to FilterStatus.ReplaceNow, FilterMath.volumeGauge(151.0, 151.0))
    }

    @Test
    fun volumeSkipsWaterDrunkBeforeInstallWhilePausedAndManualSips() {
        val installed = at(2026, 6, 1)
        val t = installed
        val sips = listOf(
            reading(t - 200, 50),
            reading(t - 100, 60),                    // drink before install — not counted
            reading(t + 100, 70),                    // counted
            reading(t + 200, 80),                    // inside the pause — not counted
            reading(t + 300, 90),                    // after put-back — counted
            SipEntity(t + 150, SipEntity.TRIGGER_MANUAL, 0, 0, 0f, 0L, manualVolumeMl = 500f), // manual — not counted
        )
        val paused = filter(installed, pauses = listOf(FilterPauseEntity(1, 1, startSec = t + 150, endSec = t + 250)))
        val expectedMl = drinkMl(60, 70) + drinkMl(80, 90)
        assertEquals(expectedMl / 1000.0, FilterMath.volumeFilteredL(paused, sips, 1000, nowSec = t + 1000), 1e-9)

        // An open pause (filter still out) excludes everything from its start onward.
        val stillOut = filter(installed, pauses = listOf(FilterPauseEntity(1, 1, startSec = t + 150, endSec = null)))
        assertEquals(drinkMl(60, 70) / 1000.0, FilterMath.volumeFilteredL(stillOut, sips, 1000, nowSec = t + 1000), 1e-9)
    }
}
