package com.hydra.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "hydra_prefs")

/**
 * App-level user preferences (daily goal) and runtime state (last sync timestamp).
 * Persisted via DataStore Preferences — lighter than another Room table for this small surface.
 */
class AppPreferencesRepository private constructor(context: Context) {

    private val store = context.applicationContext.dataStore

    val dailyGoalMl: Flow<Int> = store.data.map { it[KEY_DAILY_GOAL_ML] ?: DEFAULT_DAILY_GOAL_ML }
    val lastSyncMs: Flow<Long> = store.data.map { it[KEY_LAST_SYNC_MS] ?: 0L }
    val themeMode: Flow<ThemeMode> = store.data.map { prefs ->
        prefs[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.System
    }
    /** Typical wake-up and bedtime, seconds since local midnight. Drive the day rollover and pace. */
    val wakeSec: Flow<Int> = store.data.map { it[KEY_WAKE_SEC] ?: DEFAULT_WAKE_SEC }
    val bedSec: Flow<Int> = store.data.map { it[KEY_BED_SEC] ?: DEFAULT_BED_SEC }

    suspend fun setWakeSec(value: Int) { store.edit { it[KEY_WAKE_SEC] = value } }
    suspend fun setBedSec(value: Int) { store.edit { it[KEY_BED_SEC] = value } }

    /** Hourly background check-in with the bottle (see `sync/BackgroundSync.kt`). */
    val backgroundSyncEnabled: Flow<Boolean> = store.data.map { it[KEY_BG_SYNC_ENABLED] ?: true }
    val lastBackgroundSyncMs: Flow<Long> = store.data.map { it[KEY_LAST_BG_SYNC_MS] ?: 0L }

    suspend fun setBackgroundSyncEnabled(value: Boolean) { store.edit { it[KEY_BG_SYNC_ENABLED] = value } }
    suspend fun setLastBackgroundSync(ms: Long) { store.edit { it[KEY_LAST_BG_SYNC_MS] = ms } }

    /** Last battery level read from the bottle (null if never) and when, so it can be shown while disconnected. */
    val lastBatteryPercent: Flow<Int?> = store.data.map { it[KEY_LAST_BATTERY] }
    val lastBatteryAtMs: Flow<Long> = store.data.map { it[KEY_LAST_BATTERY_AT_MS] ?: 0L }

    suspend fun setLastBattery(percent: Int, atMs: Long) {
        store.edit { it[KEY_LAST_BATTERY] = percent; it[KEY_LAST_BATTERY_AT_MS] = atMs }
    }
    suspend fun clearLastBattery() {
        store.edit { it.remove(KEY_LAST_BATTERY); it.remove(KEY_LAST_BATTERY_AT_MS); it.remove(KEY_LOW_BATTERY_NOTIFIED) }
    }

    /** Notifications (see `notify/HydraNotifications.kt`). Both are opt-in. */
    val paceNudgeEnabled: Flow<Boolean> = store.data.map { it[KEY_PACE_NUDGE] ?: false }
    val lastPaceNudgeMs: Flow<Long> = store.data.map { it[KEY_LAST_PACE_NUDGE_MS] ?: 0L }
    val lowBatteryAlertEnabled: Flow<Boolean> = store.data.map { it[KEY_LOW_BATTERY_ALERT] ?: false }
    /** When the low-battery alert last fired; 0 once the bottle has been charged again. */
    val lowBatteryNotifiedMs: Flow<Long> = store.data.map { it[KEY_LOW_BATTERY_NOTIFIED] ?: 0L }

    suspend fun setPaceNudgeEnabled(value: Boolean) { store.edit { it[KEY_PACE_NUDGE] = value } }
    suspend fun setLastPaceNudge(ms: Long) { store.edit { it[KEY_LAST_PACE_NUDGE_MS] = ms } }
    suspend fun setLowBatteryAlertEnabled(value: Boolean) { store.edit { it[KEY_LOW_BATTERY_ALERT] = value } }
    suspend fun setLowBatteryNotifiedMs(value: Long) { store.edit { it[KEY_LOW_BATTERY_NOTIFIED] = value } }

    val showStreak: Flow<Boolean> = store.data.map { it[KEY_SHOW_STREAK] ?: true }
    val lastUpdateCheckMs: Flow<Long> = store.data.map { it[KEY_LAST_UPDATE_CHECK_MS] ?: 0L }
    val autoUpdateEnabled: Flow<Boolean> = store.data.map { it[KEY_AUTO_UPDATE_ENABLED] ?: true }
    val healthConnectEnabled: Flow<Boolean> = store.data.map { it[KEY_HC_ENABLED] ?: false }
    /** Largest sip `timestampSec` already pushed to Health Connect; the sync watermark. */
    val healthConnectLastSyncSec: Flow<Long> = store.data.map { it[KEY_HC_LAST_SYNC_SEC] ?: 0L }

    /** Whether to post a notification when the filter reaches "replace soon" / "replace now". */
    val filterReminderEnabled: Flow<Boolean> = store.data.map { it[KEY_FILTER_REMINDER] ?: false }
    /** "<filterId>:<status>" of the last filter reminder posted, so each stage notifies once. */
    val filterLastNotified: Flow<String> = store.data.map { it[KEY_FILTER_LAST_NOTIFIED] ?: "" }

    suspend fun setFilterReminderEnabled(value: Boolean) {
        store.edit { it[KEY_FILTER_REMINDER] = value }
    }
    /** When that reminder was posted, so it can repeat until the filter is replaced. */
    val filterLastNotifiedMs: Flow<Long> = store.data.map { it[KEY_FILTER_LAST_NOTIFIED_MS] ?: 0L }
    suspend fun setFilterLastNotified(value: String, atMs: Long) {
        store.edit { it[KEY_FILTER_LAST_NOTIFIED] = value; it[KEY_FILTER_LAST_NOTIFIED_MS] = atMs }
    }

    suspend fun setDailyGoal(mL: Int) {
        store.edit { it[KEY_DAILY_GOAL_ML] = mL.coerceAtLeast(0) }
    }

    suspend fun setLastSync(ms: Long) {
        store.edit { it[KEY_LAST_SYNC_MS] = ms }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setShowStreak(value: Boolean) {
        store.edit { it[KEY_SHOW_STREAK] = value }
    }

    suspend fun setLastUpdateCheck(ms: Long) {
        store.edit { it[KEY_LAST_UPDATE_CHECK_MS] = ms }
    }

    suspend fun setAutoUpdateEnabled(value: Boolean) {
        store.edit { it[KEY_AUTO_UPDATE_ENABLED] = value }
    }

    suspend fun setHealthConnectEnabled(value: Boolean) {
        store.edit { it[KEY_HC_ENABLED] = value }
    }

    suspend fun setHealthConnectLastSyncSec(value: Long) {
        store.edit { it[KEY_HC_LAST_SYNC_SEC] = value }
    }

    enum class ThemeMode { System, Dark, Light }

    companion object {
        const val DEFAULT_DAILY_GOAL_ML = 2000

        private val KEY_DAILY_GOAL_ML: Preferences.Key<Int> = intPreferencesKey("daily_goal_ml")
        private val KEY_LAST_SYNC_MS: Preferences.Key<Long> = longPreferencesKey("last_sync_ms")
        private val KEY_THEME_MODE: Preferences.Key<String> = stringPreferencesKey("theme_mode")
        const val DEFAULT_WAKE_SEC = 7 * 3600
        const val DEFAULT_BED_SEC = 23 * 3600
        private val KEY_WAKE_SEC: Preferences.Key<Int> = intPreferencesKey("wake_sec")
        private val KEY_BED_SEC: Preferences.Key<Int> = intPreferencesKey("bed_sec")
        private val KEY_BG_SYNC_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("bg_sync_enabled")
        private val KEY_LAST_BG_SYNC_MS: Preferences.Key<Long> = longPreferencesKey("last_bg_sync_ms")
        private val KEY_LAST_BATTERY: Preferences.Key<Int> = intPreferencesKey("last_battery_percent")
        private val KEY_LAST_BATTERY_AT_MS: Preferences.Key<Long> = longPreferencesKey("last_battery_at_ms")
        private val KEY_PACE_NUDGE: Preferences.Key<Boolean> = booleanPreferencesKey("pace_nudge_enabled")
        private val KEY_LAST_PACE_NUDGE_MS: Preferences.Key<Long> = longPreferencesKey("last_pace_nudge_ms")
        private val KEY_LOW_BATTERY_ALERT: Preferences.Key<Boolean> = booleanPreferencesKey("low_battery_alert_enabled")
        private val KEY_LOW_BATTERY_NOTIFIED: Preferences.Key<Long> = longPreferencesKey("low_battery_notified_ms")
        private val KEY_SHOW_STREAK: Preferences.Key<Boolean> = booleanPreferencesKey("show_streak")
        private val KEY_LAST_UPDATE_CHECK_MS: Preferences.Key<Long> = longPreferencesKey("last_update_check_ms")
        private val KEY_AUTO_UPDATE_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("auto_update_enabled")
        private val KEY_HC_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("hc_enabled")
        private val KEY_HC_LAST_SYNC_SEC: Preferences.Key<Long> = longPreferencesKey("hc_last_sync_sec")

        private val KEY_FILTER_REMINDER: Preferences.Key<Boolean> = booleanPreferencesKey("filter_reminder_enabled")
        private val KEY_FILTER_LAST_NOTIFIED_MS: Preferences.Key<Long> = longPreferencesKey("filter_last_notified_ms")
        private val KEY_FILTER_LAST_NOTIFIED: Preferences.Key<String> = stringPreferencesKey("filter_last_notified")

        @Volatile
        private var instance: AppPreferencesRepository? = null

        fun get(context: Context): AppPreferencesRepository = instance ?: synchronized(this) {
            instance ?: AppPreferencesRepository(context).also { instance = it }
        }
    }
}
