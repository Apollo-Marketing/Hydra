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
 *  2. [levelEvents] turns the sip history into drinks, refills and manual sips using the
 *     official app's detection rules; [dailyIntakeMl], [intakeOnDateMl], [hourlyIntakeMl],
 *     [refillsOnDate], [perSipDrinks], [streakDays] and [lastNDayIntakes] are all summaries
 *     of that one event list, so every screen agrees on the numbers.
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
     * The user's typical day: when they get up and when they go to bed, as seconds since
     * local midnight. Bedtime may be "after midnight" (e.g. wake 9 am, bed 2 am).
     */
    data class DaySchedule(val wakeSec: Int = 7 * 3600, val bedSec: Int = 23 * 3600) {
        /** Seconds awake, wake → bed, wrapping past midnight. A zero-length day counts as 24 h. */
        /** Whether [secOfDay] (local seconds since midnight) falls in the sleep hours, bed → wake. */
        fun isAsleep(secOfDay: Int): Boolean {
            if (bedSec == wakeSec) return false
            return Math.floorMod(secOfDay - bedSec, DAY_SEC) < Math.floorMod(wakeSec - bedSec, DAY_SEC)
        }

        val awakeSec: Int get() = Math.floorMod(bedSec - wakeSec, DAY_SEC).let { if (it == 0) DAY_SEC else it }

        /**
         * When one day ends and the next begins: the middle of the night's sleep, rounded
         * down to the hour. A sip before this belongs to the day that is ending, so a 1 am
         * glass before a 2 am bedtime still counts toward "today".
         */
        val resetSec: Int
            get() {
                val asleep = DAY_SEC - awakeSec
                return Math.floorMod(bedSec + asleep / 2, DAY_SEC) / 3600 * 3600
            }

        /** Pace expects the goal this long before bed, so the last hours aren't catch-up drinking. */
        val paceSec: Int get() = (awakeSec - PACE_BUFFER_SEC).coerceAtLeast(3600)
    }

    private const val DAY_SEC = 86_400
    private const val PACE_BUFFER_SEC = 2 * 3600

    /**
     * Seconds after local midnight at which the logical day rolls over. Process-wide because
     * every aggregator below buckets by it; `MainScreen` sets it from the user's
     * [DaySchedule] before any screen draws. Defaults to 3 am.
     */
    @Volatile var dayResetSec: Int = 3 * 3600

    /**
     * Convert an [Instant] to the local logical date: instants before [dayResetSec] belong
     * to the *previous* calendar date.
     */
    fun logicalDateFor(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        instant.atZone(zone).toLocalDateTime().minusSeconds(dayResetSec.toLong()).toLocalDate()

    /** Today's logical date — [LocalDate.now], or yesterday if the clock is before [dayResetSec]. */
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

    // ── Drink / refill detection ──────────────────────────────────────────────────────────

    /** A level drop only counts as a drink once it exceeds this (`cap_drink_threshold_in_milliliter`). */
    const val DRINK_THRESHOLD_ML = 25.0

    /** A level rise only counts as a refill once it exceeds this (`cap_fill_threshold_in_milliliter`). */
    const val REFILL_THRESHOLD_ML = 200.0

    /** Floor applied to the raw polynomial output (`cap_min_volume_limit_in_milliliter`). */
    private const val MIN_VOLUME_LIMIT_ML = -50.0

    /**
     * Readings at or beyond this distance are out of the sensor's usable range for the bottle
     * size (`cap_size_680ml_tof_max` / `cap_size_1000ml_tof_max`) and are ignored.
     */
    private val tofMaxMm: Map<Int, Int> = mapOf(680 to 240, 1000 to 270)

    /** Polynomial volume with only the official lower floor applied — the detector's input. */
    private fun rawVolumeMl(distanceMm: Int, bottleSizeMl: Int): Double? {
        val coeffs = polynomials[bottleSizeMl] ?: return null
        val d = distanceMm.toDouble()
        val raw = coeffs[0] * d * d * d * d + coeffs[1] * d * d * d + coeffs[2] * d * d + coeffs[3] * d + coeffs[4]
        return raw.coerceAtLeast(MIN_VOLUME_LIMIT_ML)
    }

    enum class LevelEventKind { Drink, Refill, Manual }

    /**
     * One thing that happened to the water: a drink or refill detected from bottle readings,
     * or a hand-logged sip. [timestampSec] is the row's own timestamp (unique per row);
     * [prevTimestampSec] is the previous bottle reading, i.e. when the drink could have begun.
     */
    data class LevelEvent(
        val kind: LevelEventKind,
        val timestampSec: Long,
        val prevTimestampSec: Long,
        val volumeMl: Double,
    )

    /**
     * Classify the whole sip history into drinks, refills and manual sips, oldest first.
     *
     * Bottle readings follow the official app's `BottleVolumeAlgo` (decompiled `nc.a`), using
     * its bundled default thresholds. It keeps a *reference level* — the water level at the
     * last drink or refill — and for each new reading:
     *  - **Refill** if the level rose more than [REFILL_THRESHOLD_ML] since the previous
     *    reading. The reference moves up to the new level.
     *  - **Drink** if the level is more than [DRINK_THRESHOLD_ML] below the reference. The
     *    amount is reference − level, and the reference moves down.
     *  - **Skip** otherwise. The reference stays put, so several small sips add up and are
     *    counted together once they pass the threshold, while sensor jitter is ignored.
     *
     * Simplification: the official app has an extra branch for out-of-range readings (cap
     * off / bottle tilted) whose decompiled logic is unclear; we simply ignore such readings.
     *
     * Manual sips carry their own volume and never touch the reference level.
     */
    fun levelEvents(entries: List<SipEntity>, bottleSizeMl: Int): List<LevelEvent> {
        val sortedAsc = entries.sortedBy { it.timestampSec }
        val out = mutableListOf<LevelEvent>()
        val tofMax = tofMaxMm[bottleSizeMl] ?: Int.MAX_VALUE

        var reference: Double? = null
        var prevVolume = 0.0
        var prevTimestamp = 0L
        for (entry in sortedAsc) {
            val manual = entry.manualVolumeMl
            if (manual != null) {
                out += LevelEvent(LevelEventKind.Manual, entry.timestampSec, entry.timestampSec, manual.toDouble())
                continue
            }
            if (entry.distanceMm >= tofMax) continue
            val volume = rawVolumeMl(entry.distanceMm, bottleSizeMl) ?: continue
            val ref = reference
            if (ref == null) {
                // First reading establishes the baseline; nothing to compare against yet.
                reference = volume
            } else if (volume - prevVolume > REFILL_THRESHOLD_ML) {
                out += LevelEvent(LevelEventKind.Refill, entry.timestampSec, prevTimestamp, volume - prevVolume)
                reference = volume
            } else if (ref - volume > DRINK_THRESHOLD_ML) {
                out += LevelEvent(LevelEventKind.Drink, entry.timestampSec, prevTimestamp, ref - volume)
                reference = volume
            }
            prevVolume = volume
            prevTimestamp = entry.timestampSec
        }
        return out
    }

    private fun LevelEvent.isIntake() = kind == LevelEventKind.Drink || kind == LevelEventKind.Manual

    private fun LevelEvent.logicalDate(zone: ZoneId) = logicalDateFor(Instant.ofEpochSecond(timestampSec), zone)

    // ── Aggregators ───────────────────────────────────────────────────────────────────────

    /**
     * Intake in mL per **logical date** (see [dayResetSec]) across the whole
     * history: detected drinks plus manual sips. Dates with no intake are absent.
     */
    fun dailyIntakeMl(entries: List<SipEntity>, bottleSizeMl: Int): Map<LocalDate, Double> {
        val zone = ZoneId.systemDefault()
        val out = HashMap<LocalDate, Double>()
        for (ev in levelEvents(entries, bottleSizeMl)) {
            if (!ev.isIntake()) continue
            out.merge(ev.logicalDate(zone), ev.volumeMl, Double::plus)
        }
        return out
    }

    /** Intake in mL on one logical date. See [dailyIntakeMl]. */
    fun intakeOnDateMl(entries: List<SipEntity>, bottleSizeMl: Int, date: LocalDate): Double =
        dailyIntakeMl(entries, bottleSizeMl)[date] ?: 0.0

    data class DrinkRecord(
        val timestampSec: Long,
        val startMs: Long,
        val endMs: Long,
        val volumeMl: Double,
        /** True for hand-logged sips — water that did not necessarily pass through the bottle. */
        val manual: Boolean = false,
    )

    /**
     * Per-sip view of [entries] — every intake event becomes one record, oldest first.
     * `timestampSec` is the entry's own timestamp (unique per row), suitable as a stable
     * external ID for the Health Connect bridge. Detected drinks span previous reading →
     * this reading; manual sips are instantaneous.
     */
    fun perSipDrinks(entries: List<SipEntity>, bottleSizeMl: Int): List<DrinkRecord> =
        levelEvents(entries, bottleSizeMl).filter { it.isIntake() }.map { ev ->
            val manual = ev.kind == LevelEventKind.Manual
            DrinkRecord(
                timestampSec = ev.timestampSec,
                startMs = (if (manual) ev.timestampSec else ev.prevTimestampSec) * 1000L,
                endMs = ev.timestampSec * 1000L,
                volumeMl = ev.volumeMl,
                manual = manual,
            )
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
     * What's in the bottle right now: the on-request reading if it is newer than the last
     * synced one, otherwise [latestVolumeMl].
     */
    fun currentVolumeMl(entries: List<SipEntity>, bottleSizeMl: Int, liveDistanceMm: Int?, liveAtMs: Long?): Double? {
        val latestSec = entries.asSequence().filter { it.manualVolumeMl == null }.maxOfOrNull { it.timestampSec } ?: 0L
        if (liveDistanceMm != null && liveAtMs != null && liveAtMs / 1000L >= latestSec) {
            volumeMl(liveDistanceMm, bottleSizeMl)?.let { return it }
        }
        return latestVolumeMl(entries, bottleSizeMl)
    }

    // ── Pace ──────────────────────────────────────────────────────────────────────────────

    /**
     * Share of the daily goal (0..1) that should be drunk by [now] to be "on pace": an even
     * spread from the user's wake-up time until two hours before their bedtime. There is no
     * evidence-based hour-by-hour schedule; the common guidance is to spread water through
     * the waking day and ease off before bed, which is what this window does.
     *
     * [logicalDate] is the day being shown; its pace window starts at that day's wake-up.
     */
    fun paceFraction(
        now: java.time.ZonedDateTime,
        schedule: DaySchedule,
        logicalDate: LocalDate = logicalDateFor(now.toInstant(), now.zone),
    ): Float {
        // Wake-up is the first wake time at or after the day's rollover.
        val wakeOffset = Math.floorMod(schedule.wakeSec - dayResetSec, DAY_SEC)
        val wake = logicalDate.atStartOfDay().plusSeconds((dayResetSec + wakeOffset).toLong())
        val elapsed = java.time.Duration.between(wake, now.toLocalDateTime()).seconds
        return (elapsed.toFloat() / schedule.paceSec).coerceIn(0f, 1f)
    }

    data class RefillSummary(val count: Int, val totalMl: Double)

    /** Refills detected on the given **logical date**: count and summed mL added. */
    fun refillsOnDate(entries: List<SipEntity>, bottleSizeMl: Int, date: LocalDate): RefillSummary {
        val zone = ZoneId.systemDefault()
        val refills = levelEvents(entries, bottleSizeMl)
            .filter { it.kind == LevelEventKind.Refill && it.logicalDate(zone) == date }
        return RefillSummary(refills.size, refills.sumOf { it.volumeMl })
    }

    /**
     * Intake binned by hour-of-day for the given **logical date**. Returns a 24-element
     * DoubleArray; index = wall-clock hour (0..23). An event's bucket is its actual local
     * hour but its date membership is the logical date — so a 1am sip on calendar day N
     * lands in bucket 1 of logical day N-1's chart.
     */
    fun hourlyIntakeMl(entries: List<SipEntity>, bottleSizeMl: Int, date: LocalDate): DoubleArray {
        val zone = ZoneId.systemDefault()
        val out = DoubleArray(24)
        for (ev in levelEvents(entries, bottleSizeMl)) {
            if (!ev.isIntake() || ev.logicalDate(zone) != date) continue
            out[Instant.ofEpochSecond(ev.timestampSec).atZone(zone).hour] += ev.volumeMl
        }
        return out
    }

    /**
     * Daily intake totals for the [n] days ending at [end] (default today), oldest first.
     * Includes days with zero intake so the chart has a consistent N-bar shape.
     */
    fun lastNDayIntakes(
        entries: List<SipEntity>,
        bottleSizeMl: Int,
        n: Int = 7,
        end: LocalDate = currentLogicalDate(),
    ): List<Pair<LocalDate, Double>> {
        val daily = dailyIntakeMl(entries, bottleSizeMl)
        return (n - 1 downTo 0).map { offset ->
            val date = end.minusDays(offset.toLong())
            date to (daily[date] ?: 0.0)
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
        val daily = dailyIntakeMl(entries, bottleSizeMl)
        var streak = 0
        for (offset in 0 until 365) {  // cap to a year
            val intake = daily[today.minusDays(offset.toLong())] ?: 0.0
            if (intake >= dailyGoalMl) streak += 1 else break
        }
        return streak
    }
}
