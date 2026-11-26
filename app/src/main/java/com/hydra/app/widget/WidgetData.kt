package com.hydra.app.widget

import android.content.Context
import com.hydra.app.ble.BottleConfig
import com.hydra.app.ble.BottleMath
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.FilterRepository
import com.hydra.app.data.HydraDatabase
import com.hydra.app.filter.FilterMath
import com.hydra.app.filter.FilterStatus
import com.hydra.app.notify.LowBatteryAlert
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/** Short pace wording for the widget; [behind] picks the warning colours. */
data class PaceStatus(val text: String, val behind: Boolean)

/** Everything the home-screen widget shows, read from what the app has stored. */
data class WidgetData(
    val intakeMl: Int,
    val goalMl: Int,
    /** Share of the goal that should be done by now; 0 before wake-up. */
    val paceFraction: Float,
    val pace: PaceStatus?,
    val batteryPercent: Int?,
    val batteryLow: Boolean,
    val filterPercent: Int?,
    val filterLow: Boolean,
    val lastSyncMs: Long,
    /** The app's theme setting: null = follow the system, else forced dark / light. */
    val forceDark: Boolean? = null,
) {
    val progress: Float get() = if (goalMl > 0) (intakeMl.toFloat() / goalMl).coerceIn(0f, 1f) else 0f
    val percent: Int get() = if (goalMl > 0) (intakeMl * 100f / goalMl).roundToInt() else 0

    companion object {
        /**
         * Same rules as the Today screen: within 5% of the goal either way counts as on pace.
         * Null when there is no goal or the day hasn't started.
         */
        fun paceStatus(intakeMl: Int, goalMl: Int, paceFraction: Float): PaceStatus? {
            if (goalMl <= 0) return null
            if (intakeMl >= goalMl) return PaceStatus("Goal reached", behind = false)
            if (paceFraction <= 0f) return null
            val diff = intakeMl - goalMl * paceFraction
            val band = goalMl * 0.05f
            return when {
                diff >= band -> PaceStatus("${"%,d".format(diff.roundToInt())} ml ahead", behind = false)
                diff <= -band -> PaceStatus("${"%,d".format((-diff).roundToInt())} ml behind", behind = true)
                else -> PaceStatus("On pace", behind = false)
            }
        }

        /** "just now", "20 min ago", "3 h ago", "2 d ago"; null if there has been no sync. */
        fun ageText(lastSyncMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
            if (lastSyncMs <= 0L) return null
            val min = ((nowMs - lastSyncMs).coerceAtLeast(0L)) / 60_000L
            return when {
                min < 1 -> "just now"
                min < 60 -> "$min min ago"
                min < 24 * 60 -> "${min / 60} h ago"
                else -> "${min / (24 * 60)} d ago"
            }
        }

        suspend fun load(context: Context): WidgetData {
            val app = context.applicationContext
            val prefs = AppPreferencesRepository.get(app)
            val schedule = BottleMath.DaySchedule(prefs.wakeSec.first(), prefs.bedSec.first())
            // The widget can be drawn in a process the screen never set this in.
            BottleMath.dayResetSec = schedule.resetSec
            val db = HydraDatabase.get(app)
            val sips = db.sipDao().observeAll().first()
            val goal = prefs.dailyGoalMl.first()
            val intake = BottleMath
                .intakeOnDateMl(sips, BottleConfig.BOTTLE_SIZE_ML, BottleMath.currentLogicalDate())
                .roundToInt()
            val fraction = BottleMath.paceFraction(ZonedDateTime.now(), schedule)
            val battery = prefs.lastBatteryPercent.first()
            val filter = FilterRepository(db).getActive()
                ?.let { FilterMath.health(it, sips, BottleConfig.BOTTLE_SIZE_ML) }
            return WidgetData(
                intakeMl = intake,
                goalMl = goal,
                paceFraction = fraction,
                pace = paceStatus(intake, goal, fraction),
                batteryPercent = battery,
                batteryLow = battery != null && battery <= LowBatteryAlert.ALERT_PERCENT,
                filterPercent = filter?.percent,
                filterLow = filter != null && filter.status != FilterStatus.Good,
                lastSyncMs = prefs.lastSyncMs.first(),
                forceDark = when (prefs.themeMode.first()) {
                    AppPreferencesRepository.ThemeMode.Dark -> true
                    AppPreferencesRepository.ThemeMode.Light -> false
                    AppPreferencesRepository.ThemeMode.System -> null
                },
            )
        }
    }
}
