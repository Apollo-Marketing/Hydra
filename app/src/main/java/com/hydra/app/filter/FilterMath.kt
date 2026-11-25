package com.hydra.app.filter

import com.hydra.app.ble.BottleMath
import com.hydra.app.data.ActiveFilter
import com.hydra.app.data.SipEntity
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Filter cartridge types and their rated capacity. Values are the official Larq app's
 * bundled defaults (`cap_max_essential_filter_volume_in_liter` = 94,
 * `cap_max_advanced_filter_volume_in_liter` = 151).
 */
enum class FilterType(val key: String, val label: String, val capacityL: Double) {
    Essential("essential", "Essential", 94.0),
    Advanced("advanced", "Advanced", 151.0);

    companion object {
        fun fromKey(key: String): FilterType = entries.firstOrNull { it.key == key } ?: Essential
    }
}

enum class FilterStatus { Good, ReplaceSoon, ReplaceNow }

data class FilterHealth(
    /** 0–100. The lower of [volumePercent] and [agePercent]. */
    val percent: Int,
    val status: FilterStatus,
    val volumeFilteredL: Double,
    val ageDays: Int,
    val volumePercent: Int,
    val agePercent: Int,
)

/**
 * Filter health, mirroring the official Larq app (`FilterUtils.kt` in the decompiled APK).
 *
 * Two independent gauges run side by side and the one with the lower percentage wins:
 *  - **Volume:** 100 × (1 − filtered ÷ capacity), floored at [REPLACE_SOON_PERCENT] until
 *    capacity is reached. Good at ≥ [GOOD_PERCENT], replace-soon below, replace-now at capacity.
 *  - **Age:** 100 × (1 − age ÷ [LIFETIME_SEC]), rounded up. Good for [GOOD_AGE_SEC],
 *    replace-soon after, replace-now from the calendar day the lifetime ends.
 *
 * Age always runs from the install date, including while the filter is temporarily removed.
 * Volume only counts water drunk from the bottle while the filter was in.
 */
object FilterMath {

    const val REPLACE_SOON_PERCENT = 1          // filter_replace_soon_threshold_percentage
    const val GOOD_PERCENT = 21                 // filter_brilliant_threshold_percentage
    const val GOOD_AGE_SEC = 4_147_200L         // cap_brilliant_threshold_interval_in_sec (48 days)
    const val LIFETIME_SEC = 5_184_000L         // cap_filter_replace_soon_threshold_interval_in_sec (60 days)

    fun health(
        active: ActiveFilter,
        sips: List<SipEntity>,
        bottleSizeMl: Int,
        nowSec: Long = System.currentTimeMillis() / 1000L,
        zone: ZoneId = ZoneId.systemDefault(),
    ): FilterHealth {
        val type = FilterType.fromKey(active.filter.type)
        val installed = active.filter.installedAtSec
        val volumeL = volumeFilteredL(active, sips, bottleSizeMl, nowSec)

        val (volumePercent, volumeStatus) = volumeGauge(volumeL, type.capacityL)
        val (agePercent, ageStatus) = ageGauge(installed, nowSec, zone)

        val installDate = Instant.ofEpochSecond(installed).atZone(zone).toLocalDate()
        val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
        val ageDays = ChronoUnit.DAYS.between(installDate, today).toInt().coerceAtLeast(0)

        val ageWins = agePercent < volumePercent
        return FilterHealth(
            percent = if (ageWins) agePercent else volumePercent,
            status = if (ageWins) ageStatus else volumeStatus,
            volumeFilteredL = volumeL,
            ageDays = ageDays,
            volumePercent = volumePercent,
            agePercent = agePercent,
        )
    }

    /**
     * Litres drunk from the bottle since the filter went in, skipping anything drunk while it
     * was temporarily removed. Hand-logged sips are excluded — they aren't bottle water.
     */
    fun volumeFilteredL(
        active: ActiveFilter,
        sips: List<SipEntity>,
        bottleSizeMl: Int,
        nowSec: Long,
    ): Double {
        val from = active.filter.installedAtSec
        val to = active.filter.removedAtSec ?: nowSec
        val totalMl = BottleMath.perSipDrinks(sips, bottleSizeMl)
            .asSequence()
            .filter { !it.manual }
            .filter { it.timestampSec in from..to }
            .filter { drink ->
                active.pauses.none { p -> drink.timestampSec >= p.startSec && drink.timestampSec <= (p.endSec ?: nowSec) }
            }
            .sumOf { it.volumeMl }
        return totalMl / 1000.0
    }

    internal fun volumeGauge(volumeL: Double, capacityL: Double): Pair<Int, FilterStatus> {
        if (volumeL >= capacityL) return 0 to FilterStatus.ReplaceNow
        val raw = ((1.0 - volumeL / capacityL) * 100.0).coerceAtLeast(0.0).roundToInt()
        val percent = max(raw, REPLACE_SOON_PERCENT)
        return percent to if (percent >= GOOD_PERCENT) FilterStatus.Good else FilterStatus.ReplaceSoon
    }

    internal fun ageGauge(installedAtSec: Long, nowSec: Long, zone: ZoneId): Pair<Int, FilterStatus> {
        val elapsed = (nowSec - installedAtSec).toDouble()
        val percent = ceil(((1.0 - elapsed / LIFETIME_SEC) * 100.0).coerceIn(0.0, 100.0)).toInt()
        val endOfLifeDate = Instant.ofEpochSecond(installedAtSec + LIFETIME_SEC).atZone(zone).toLocalDate()
        val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
        val status = when {
            nowSec < installedAtSec + GOOD_AGE_SEC -> FilterStatus.Good
            !today.isBefore(endOfLifeDate) -> FilterStatus.ReplaceNow
            else -> FilterStatus.ReplaceSoon
        }
        return percent to status
    }
}
