package com.hydra.app.filter

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.FilterRepository
import com.hydra.app.data.HydraDatabase
import com.hydra.app.notify.HydraNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "HydraFilter"

/**
 * Posts a "replace your filter" notification the first time the active filter reaches
 * replace-soon, and again when it reaches replace-now.
 *
 * [check] runs whenever the app sees new data (launch, sync, filter change) and from two
 * alarms set at the filter's age milestones, so the age-based reminder still arrives when
 * the app isn't open. The volume-based reminder can only fire after a sync, because sips
 * reach the phone only while the app is connected to the bottle. Alarms don't survive a
 * reboot; they are re-armed the next time the app is opened.
 */
object FilterReminder {

    private const val CHANNEL_ID = "filter_replacement"
    private const val NOTIFICATION_ID = 4101
    private const val REQUEST_SOON = 4102
    private const val REQUEST_NOW = 4103
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    const val REPEAT_SOON_MS = 3 * DAY_MS
    const val REPEAT_NOW_MS = DAY_MS

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Evaluate the active filter and notify if it has newly reached a replace stage. */
    suspend fun check(context: Context) {
        val app = context.applicationContext
        val prefs = AppPreferencesRepository.get(app)
        if (!prefs.filterReminderEnabled.first()) return
        if (!hasNotificationPermission(app)) return

        val db = HydraDatabase.get(app)
        val active = FilterRepository(db).getActive() ?: return
        val sips = db.sipDao().observeAll().first()
        val health = FilterMath.health(active, sips, BottleConfig.BOTTLE_SIZE_ML)
        if (health.status == FilterStatus.Good) return

        // Each stage notifies when first reached, then repeats until the filter is replaced:
        // every few days while it is running low, daily once it is depleted. Never at night.
        val stamp = "${active.filter.id}:${health.status.name}"
        val nowMs = System.currentTimeMillis()
        val repeatMs = if (health.status == FilterStatus.ReplaceNow) REPEAT_NOW_MS else REPEAT_SOON_MS
        if (prefs.filterLastNotified.first() == stamp &&
            nowMs - prefs.filterLastNotifiedMs.first() < repeatMs
        ) return
        if (HydraNotifications.isSleepTime(prefs)) return

        val type = FilterType.fromKey(active.filter.type)
        val (title, text) = when (health.status) {
            FilterStatus.ReplaceNow ->
                "Time to replace your filter" to "Your ${type.label} filter is depleted. Replace it as soon as possible."
            else ->
                "Filter running low" to "Your ${type.label} filter is at ${health.percent}%. Plan to replace it soon."
        }
        post(app, title, text)
        prefs.setFilterLastNotified(stamp, nowMs)
        Log.d(TAG, "filter reminder posted: $stamp")
    }

    /** (Re)arm the two age-milestone alarms for the active filter; clears them if there is none. */
    suspend fun schedule(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val soon = alarmIntent(app, REQUEST_SOON)
        val now = alarmIntent(app, REQUEST_NOW)
        alarms.cancel(soon)
        alarms.cancel(now)

        if (!AppPreferencesRepository.get(app).filterReminderEnabled.first()) return
        val active = FilterRepository(HydraDatabase.get(app)).getActive() ?: return
        val installedMs = active.filter.installedAtSec * 1000L
        val nowMs = System.currentTimeMillis()
        // A minute past each milestone so the status has definitely flipped when we look.
        val soonAt = installedMs + FilterMath.GOOD_AGE_SEC * 1000L + 60_000L
        val nowAt = installedMs + FilterMath.LIFETIME_SEC * 1000L + 60_000L
        if (soonAt > nowMs) alarms.set(AlarmManager.RTC_WAKEUP, soonAt, soon)
        if (nowAt > nowMs) alarms.set(AlarmManager.RTC_WAKEUP, nowAt, now)
    }

    private fun alarmIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, FilterReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun post(context: Context, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Filter replacement", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "notify failed: ${it.message}") }
    }
}

/** Fired by the age-milestone alarms set in [FilterReminder.schedule]. */
class FilterReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                FilterReminder.check(context)
            } catch (t: Throwable) {
                Log.w(TAG, "reminder check failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
