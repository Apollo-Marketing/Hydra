package com.hydra.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hydra.app.MainActivity
import com.hydra.app.R
import com.hydra.app.ble.BottleConfig
import com.hydra.app.ble.BottleMath
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.HydraDatabase
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

private const val TAG = "HydraNotify"

/** Shared plumbing for the hydration nudge and the low-battery alert. */
object HydraNotifications {

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun post(context: Context, channelId: String, channelName: String, id: Int, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(id, notification) }
            .onFailure { Log.w(TAG, "notify failed: ${it.message}") }
    }
}

/**
 * "Charge your bottle" alert. Fires once when a battery reading is at or below
 * [ALERT_PERCENT] (the level at which the cap itself starts warning), and re-arms only
 * after a reading at or above [REARM_PERCENT], so a level hovering at the line can't repeat it.
 */
object LowBatteryAlert {
    const val ALERT_PERCENT = 20
    const val REARM_PERCENT = 25
    private const val CHANNEL_ID = "bottle_battery"
    private const val NOTIFICATION_ID = 4202

    suspend fun onReading(context: Context, percent: Int) {
        val app = context.applicationContext
        val prefs = AppPreferencesRepository.get(app)
        val alreadyNotified = prefs.lowBatteryNotified.first()
        if (percent >= REARM_PERCENT) {
            if (alreadyNotified) prefs.setLowBatteryNotified(false)
            return
        }
        if (percent > ALERT_PERCENT || alreadyNotified) return
        if (!prefs.lowBatteryAlertEnabled.first() || !HydraNotifications.hasPermission(app)) return
        postAlert(app, percent)
        prefs.setLowBatteryNotified(true)
        Log.d(TAG, "low battery alert posted at $percent%")
    }

    fun postAlert(context: Context, percent: Int) = HydraNotifications.post(
        context, CHANNEL_ID, "Bottle battery", NOTIFICATION_ID,
        "Bottle battery low",
        "Your bottle is at $percent%. Charge it soon so it keeps tracking and cleaning.",
    )
}

/**
 * A nudge when intake is well behind the day's pace. Evaluated after each background
 * check-in with the bottle, so it always works from fresh readings.
 */
object PaceNudge {
    /** No nudges in the first part of the day; nobody is "behind" an hour after waking. */
    const val GRACE_SEC = 2 * 3600
    /** How far behind pace, as a share of the daily goal, before it's worth a nudge. */
    const val BEHIND_SHARE = 0.15
    const val MIN_GAP_MS = 2 * 60 * 60 * 1000L
    private const val CHANNEL_ID = "hydration_nudge"
    private const val NOTIFICATION_ID = 4201

    /**
     * How many mL behind pace is worth a nudge, or null for none. [paceFraction] is the share
     * of the goal that should be done by now (see [BottleMath.paceFraction]); it reaches 1
     * two hours before bedtime, after which no nudge is sent — late drinking disturbs sleep.
     */
    fun behindMl(intakeMl: Double, goalMl: Int, paceFraction: Float, paceSec: Int): Int? {
        if (goalMl <= 0 || paceFraction >= 1f) return null
        if (paceFraction * paceSec < GRACE_SEC) return null
        val behind = paceFraction * goalMl - intakeMl
        if (behind < goalMl * BEHIND_SHARE) return null
        return (behind / 10.0).roundToInt() * 10
    }

    suspend fun check(context: Context) {
        val app = context.applicationContext
        val prefs = AppPreferencesRepository.get(app)
        if (!prefs.paceNudgeEnabled.first() || !HydraNotifications.hasPermission(app)) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - prefs.lastPaceNudgeMs.first() < MIN_GAP_MS) return

        val schedule = BottleMath.DaySchedule(prefs.wakeSec.first(), prefs.bedSec.first())
        // The background worker can run in a process the screen never set this in.
        BottleMath.dayResetSec = schedule.resetSec
        val goal = prefs.dailyGoalMl.first()
        val sips = HydraDatabase.get(app).sipDao().observeAll().first()
        val intake = BottleMath.intakeOnDateMl(sips, BottleConfig.BOTTLE_SIZE_ML, BottleMath.currentLogicalDate())
        val fraction = BottleMath.paceFraction(ZonedDateTime.now(), schedule)
        val behind = behindMl(intake, goal, fraction, schedule.paceSec) ?: return

        postNudge(app, behind, intake.roundToInt(), goal)
        prefs.setLastPaceNudge(nowMs)
        Log.d(TAG, "pace nudge posted: $behind ml behind")
    }

    fun postNudge(context: Context, behindMl: Int, intakeMl: Int, goalMl: Int) = HydraNotifications.post(
        context, CHANNEL_ID, "Hydration nudges", NOTIFICATION_ID,
        "Time for some water",
        "You're about $behindMl ml behind pace. $intakeMl of $goalMl ml so far today.",
    )
}
