package com.hydra.app.ble

import com.hydra.app.data.SipEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure-math helpers for turning raw bottle telemetry into user-facing numbers. Despite the
 * name, this layer never touches the bottle's on-device calibration — the protocol commands
 * that would do that (`RequestSetCapCalibrationSettings`, `RequestCapStartCapCalibration`)
 * are intentionally absent from our `BottleProtocol.Requests` vocabulary.
 *
 * Two concerns live here:
 *  1. [volumeMl] applies the bottle's bundled polynomial to a TofLog distance reading and
 *     returns water volume in mL. The polynomial coefficients are hardcoded factory
 *     constants per bottle size (see [POLY_1000], [POLY_680]).
 *  2. [intakeOnDateMl], [hourlyIntakeMl], [refillsOnDate], [latestVolumeMl], [streakDays],
 *     and [lastNDayIntakes] aggregate a list of [SipEntity] rows into day/hour/streak
 *     summaries. They use [volumeMl] under the hood for BLE-synced rows; manual sips
 *     (`SipEntity.manualVolumeMl != null`) are treated separately because they have no
 *     real distance reading.
 *
 * The polynomial coefficients are bundled hardcoded in the official Larq Android app at
 * `fb/f.java` line 10 (decompiled). The official app first tries to fetch them from a
 * Firebase Remote Config key `cap_bottle_size_poly_coeffs_map`; these hardcoded values are
 * the fallback used when the cloud config is empty. We use the same fallback values directly,
 * skipping the cloud round-trip.
 *
 * Verified by computing volume for the user's 5 captured sips at distances 58, 70, 88, 103,
 * 110 mm — the resulting deltas matched the official Larq app's reported sip volumes
 * (67/100/79/35 mL) to within 0.5 mL.
 */
object BottleMath {

    /**
     * The local-time hour at which the "logical day" rolls over. Sips between midnight and
     * this hour count toward the previous logical day so a late-night sip doesn't spuriously
     * roll the user's intake total to zero or break a streak. Hardcoded by request — exposing
     * this as a user preference can come later if there's demand.
     */
    const val DAY_RESET_HOUR = 3

    /**
     * Convert an [Instant] to the local logical date. If the instant's local hour is below
     * [DAY_RESET_HOUR], we attribute it to the *previous* calendar date.
     */
    fun logicalDateFor(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        val ldt = instant.atZone(zone).toLocalDateTime()
        return if (ldt.hour < DAY_RESET_HOUR) ldt.toLocalDate().minusDays(1) else ldt.toLocalDate()
    }

    /** Today's logical date — same as [LocalDate.now] outside the [0, [DAY_RESET_HOUR]) window. */
    fun currentLogicalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        logicalDateFor(Instant.now(), zone)

    /** PureVis 2 / 1000 mL bottle. */
    private val POLY_1000 = doubleArrayOf(-1.1e-6, 5.5211e-4, -0.08516349, -0.2839113, 1026.71212239)

    /** PureVis 2 / 680 mL bottle. */
    private val POLY_680 = doubleArrayOf(-1.19e-6, 5.0264e-4, -0.06522747, -0.81937241, 715.15212206)

    private val polynomials: Map<Int, DoubleArray> = mapOf(
        680 to POLY_680,
        1000 to POLY_1000,
    )

    /**
     * Returns water volume in mL for a given TOF distance + bottle size, clamped to
     * [0, bottleSizeMl]. Returns null if no calibration exists for that bottle size.
     */
    fun volumeMl(distanceMm: Int, bottleSizeMl: Int): Double? {
        val coeffs = polynomials[bottleSizeMl] ?: return null
        val d = distanceMm.toDouble()
        val raw = coeffs[0] * d * d * d * d +
            coeffs[1] * d * d * d +
            coeffs[2] * d * d +
            coeffs[3] * d +
            coeffs[4]
        return raw.coerceIn(0.0, bottleSizeMl.toDouble())
    }

    /**
     * Sum of "drink" deltas for all sips logged on the given **logical date**, plus the
     * `manualVolumeMl` total for any manually-logged sips on that date. "Logical date" is
     * 3am-anchored — see [DAY_RESET_HOUR] / [logicalDateFor] — so an entry at 1am calendar
     * day N is attributed to logical day N-1.
     *
     * Heuristic for BLE-synced sips: a delta from the previous entry is treated as a sip if
     * water level dropped. Positive deltas (water level rose) are treated as fills and
     * excluded. This is a simplification of the official app's algorithm, which also has
     * drink/fill/skip thresholds we haven't fetched yet (RequestGetCapStateThresholdSettings).
     *
     * Manual sips have no real `distanceMm` reading, so they're excluded from the delta
     * pass and added directly via their `manualVolumeMl`.
     */
    fun intakeOnDateMl(entries: List<SipEntity>, bottleSizeMl: Int, date: LocalDate): Double =
        intakeOnDateMlSorted(entries.sortedBy { it.timestampSec }, bottleSizeMl, date)

    /** Internal: same as [intakeOnDateMl] but caller has already sorted ascending. */
    private fun intakeOnDateMlSorted(sortedAsc: List<SipEntity>, bottleSizeMl: Int, date: LocalDate): Double {
        val zone = ZoneId.systemDefault()
        val bleOnly = sortedAsc.filter { it.manualVolumeMl == null }
        var totalDrunk = 0.0
        for (i in 1 until bleOnly.size) {
            val curr = bleOnly[i]
            val currDate = logicalDateFor(Instant.ofEpochSecond(curr.timestampSec), zone)
            if (currDate != date) continue
            val prevVol = volumeMl(bleOnly[i - 1].distanceMm, bottleSizeMl) ?: continue
            val currVol = volumeMl(curr.distanceMm, bottleSizeMl) ?: continue
            val delta = prevVol - currVol
            if (delta > 0) totalDrunk += delta
        }
        for (entry in sortedAsc) {
            val manual = entry.manualVolumeMl ?: continue
            val entryDate = logicalDateFor(Instant.ofEpochSecond(entry.timestampSec), zone)
            if (entryDate == date) totalDrunk += manual.toDouble()
        }
        return totalDrunk
    }

    /**
     * Most recent BLE-synced reading's volume in mL, or null if no BLE entries / no
     * polynomial. Manual sips are explicitly excluded — they don't carry a real distance
     * reading, so passing one to [volumeMl] would yield a junk number for "what's in the
     * bottle right now."
     */
    fun latestVolumeMl(entries: List<SipEntity>, bottleSizeMl: Int): Double? {
        val latest = entries.asSequence()
            .filter { it.manualVolumeMl == null }
            .maxByOrNull { it.timestampSec }
            ?: return null
        return volumeMl(latest.distanceMm, bottleSizeMl)
    }

    /**
     * Sub-threshold positive deltas are sensor noise (bottle wobble, water sloshing as it's
     * picked up, ToF distance jitter), not real refills. Empirically tuned: 30–32 mL transients
     * showed up regularly in real captures, so this floor sits comfortably above that.
     */
    const val REFILL_THRESHOLD_ML = 50.0

    data class RefillSummary(val count: Int, val totalMl: Double)

    /**
     * Refills counted on the given **logical date** (3am-anchored, see [DAY_RESET_HOUR]).
     * A "refill" is a positive volume delta (water level rose) above the noise threshold.
     * Returns count and summed mL added.
     *
     * Manual sips are excluded from the delta chain — they're an intake event, not a
     * bottle-level event, and their `distanceMm = 0` would translate to a junk polynomial
     * volume that breaks the deltas of adjacent BLE rows.
     */
    fun refillsOnDate(entries: List<SipEntity>, bottleSizeMl: Int, date: LocalDate): RefillSummary {
        val zone = ZoneId.systemDefault()
        val bleOnly = entries.asSequence()
            .filter { it.manualVolumeMl == null }
            .sortedBy { it.timestampSec }
            .toList()

        var count = 0
        var totalMl = 0.0
        for (i in 1 until bleOnly.size) {
            val curr = bleOnly[i]
            val currDate = logicalDateFor(Instant.ofEpochSecond(curr.timestampSec), zone)
            if (currDate != date) continue
            val prevVol = volumeMl(bleOnly[i - 1].distanceMm, bottleSizeMl) ?: continue
            val currVol = volumeMl(curr.distanceMm, bottleSizeMl) ?: continue
            val delta = currVol - prevVol  // positive = refill
            if (delta > REFILL_THRESHOLD_ML) {
                count += 1
                totalMl += delta
            }
        }
        return RefillSummary(count, totalMl)
    }

    /**
     * Drink-volume binned by hour-of-day for the given **logical date** (3am-anchored, see
     * [DAY_RESET_HOUR]). Returns a 24-element DoubleArray; index = wall-clock hour (0..23),
     * value = mL drunk that hour. Note: an entry's bucket is its actual local-time hour, but
     * its date membership is the logical date — so a 1am sip on calendar day N lands in
     * bucket 1 of logical day N-1's chart.
     * Manual sips are added directly into the bucket matching their local-time hour;
     * BLE-synced sips contribute via the same delta logic as [intakeOnDateMl].
     */
    fun hourlyIntakeMl(entries: List<SipEntity>, bottleSizeMl: Int, date: LocalDate): DoubleArray {
        val zone = ZoneId.systemDefault()
        val sortedAsc = entries.sortedBy { it.timestampSec }
        val bleOnly = sortedAsc.filter { it.manualVolumeMl == null }
        val out = DoubleArray(24)

        for (i in 1 until bleOnly.size) {
            val curr = bleOnly[i]
            val currInstant = Instant.ofEpochSecond(curr.timestampSec)
            val currLocal = currInstant.atZone(zone).toLocalDateTime()
            if (logicalDateFor(currInstant, zone) != date) continue
            val prevVol = volumeMl(bleOnly[i - 1].distanceMm, bottleSizeMl) ?: continue
            val currVol = volumeMl(curr.distanceMm, bottleSizeMl) ?: continue
            val delta = prevVol - currVol  // positive = drank
            if (delta > 0) out[currLocal.hour] += delta
        }
        for (entry in sortedAsc) {
            val manual = entry.manualVolumeMl ?: continue
            val instant = Instant.ofEpochSecond(entry.timestampSec)
            val local = instant.atZone(zone).toLocalDateTime()
            if (logicalDateFor(instant, zone) != date) continue
            out[local.hour] += manual.toDouble()
        }
        return out
    }

    /**
     * Daily intake totals for the last [n] days, oldest first → today last.
     * Includes days with zero intake so the chart has a consistent N-bar shape.
     */
    fun lastNDayIntakes(
        entries: List<SipEntity>,
        bottleSizeMl: Int,
        n: Int = 7,
    ): List<Pair<LocalDate, Double>> {
        val today = currentLogicalDate()
        val sortedAsc = entries.sortedBy { it.timestampSec }
        return (n - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            date to intakeOnDateMlSorted(sortedAsc, bottleSizeMl, date)
        }
    }

    /**
     * Number of consecutive days back from today (inclusive) where intake hit [dailyGoalMl].
     * Returns 0 if today's intake hasn't hit goal yet.
     */
    fun streakDays(
        entries: List<SipEntity>,
        bottleSizeMl: Int,
        dailyGoalMl: Int,
    ): Int {
        if (dailyGoalMl <= 0) return 0
        val today = currentLogicalDate()
        val sortedAsc = entries.sortedBy { it.timestampSec }
        var streak = 0
        for (offset in 0 until 365) {  // cap to a year
            val date = today.minusDays(offset.toLong())
            val intake = intakeOnDateMlSorted(sortedAsc, bottleSizeMl, date)
            if (intake >= dailyGoalMl) streak += 1 else break
        }
        return streak
    }
}
