package com.hydra.app.sync

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hydra.app.ble.BottleConfig
import com.hydra.app.ble.BottleConnection
import com.hydra.app.ble.BottleEvent
import com.hydra.app.ble.ConnectionState
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.HydraDatabase
import com.hydra.app.data.SavedBottlesRepository
import com.hydra.app.data.SipRepository
import com.hydra.app.filter.FilterReminder
import com.hydra.app.health.HealthConnectController
import com.hydra.app.notify.LowBatteryAlert
import com.hydra.app.notify.PaceNudge
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether the app's screen is in front. While it is, the screen owns the bottle connection
 * and background sync stays out of the way — only one connection to the bottle can exist.
 */
object AppForeground {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()
    fun set(value: Boolean) { _visible.value = value }
}

/** Schedules (or cancels) the hourly background check-in with the bottle. */
object BackgroundSync {
    private const val WORK_NAME = "bottle-background-sync"
    private const val INTERVAL_MINUTES = 60L

    fun apply(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<BottleSyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES).build()
        // KEEP: re-applying on every app start must not reset the hourly timer.
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Dev aid: run a single check-in after [delaySeconds], without waiting for the hourly
     * slot (Android will not run periodic work early on request). Launch a debug build with
     * `adb shell am start -n com.hydra.app.dev/com.hydra.app.MainActivity --ez bgsync_test true`,
     * leave the app, and watch `adb logcat -s HydraBgSync`.
     */
    fun runOnceForTesting(context: Context, delaySeconds: Long = 45L) {
        val request = OneTimeWorkRequestBuilder<BottleSyncWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }
}

/**
 * One background check-in: find the bottle, connect, let the normal sync run, disconnect.
 * Every way this can't proceed (app open, no bottle paired, Bluetooth off, bottle out of
 * range) is a quiet success — the next hourly run simply tries again.
 */
class BottleSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (AppForeground.visible.value) return done("app is open")
        if (!hasBlePermissions(context)) return done("no Bluetooth permission")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return done("Bluetooth is off")

        val db = HydraDatabase.get(context)
        val name = SavedBottlesRepository(db.savedBottleDao()).observeAll().first().firstOrNull()?.name
            ?: return done("no bottle paired")
        val sipRepo = SipRepository(db.sipDao())
        val prefs = AppPreferencesRepository.get(context)

        // BottleConnection expects to be driven from the main thread, like the UI does.
        val outcome = withContext(Dispatchers.Main) {
            val connection = BottleConnection(context, sipRepo)
            try {
                // Subscribe before connecting so the finish event can't be missed.
                val finished = async(start = CoroutineStart.UNDISPATCHED) {
                    connection.events.first { it is BottleEvent.SyncFinished }
                }
                connection.findAndConnect(name)
                withTimeoutOrNull(RUN_TIMEOUT_MS) {
                    while (!finished.isCompleted) {
                        // Hand the bottle over the moment the user opens the app.
                        if (AppForeground.visible.value) break
                        if (connection.state.value == ConnectionState.Error) break
                        delay(250L)
                    }
                }
                val ok = finished.isCompleted
                finished.cancel()
                if (ok) {
                    // Battery level, for the "last known" display and the low-battery alert.
                    connection.refreshBatteryAndInfo()
                    withTimeoutOrNull(BATTERY_TIMEOUT_MS) { connection.batteryReadAtMs.first { it > 0L } }
                }
                Outcome(ok, connection.battery.value.takeIf { connection.batteryReadAtMs.value > 0L })
            } finally {
                connection.stop()
                delay(DISCONNECT_GRACE_MS)
                connection.dispose()
            }
        }
        if (!outcome.synced) return done("bottle not reached")

        val now = System.currentTimeMillis()
        prefs.setLastSync(now)
        prefs.setLastBackgroundSync(now)
        // Same follow-ups the app does after a sync: Health Connect and the filter reminder.
        runCatching {
            HealthConnectController.get(context)
                .onSipsChanged(sipRepo.observeAll().first(), BottleConfig.BOTTLE_SIZE_ML)
        }
        runCatching { FilterReminder.check(context) }
        runCatching {
            outcome.batteryPercent?.let {
                prefs.setLastBattery(it, now)
                LowBatteryAlert.onReading(context, it)
            }
        }
        runCatching { PaceNudge.check(context) }
        return done("synced")
    }

    private fun done(why: String): Result {
        Log.i(TAG, "background sync: $why")
        return Result.success()
    }

    private fun hasBlePermissions(context: Context): Boolean =
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private data class Outcome(val synced: Boolean, val batteryPercent: Int?)

    private companion object {
        const val BATTERY_TIMEOUT_MS = 4_000L
        const val TAG = "HydraBgSync"
        const val RUN_TIMEOUT_MS = 45_000L
        const val DISCONNECT_GRACE_MS = 600L
    }
}
