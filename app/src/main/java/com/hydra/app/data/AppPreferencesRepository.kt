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
    val showStreak: Flow<Boolean> = store.data.map { it[KEY_SHOW_STREAK] ?: true }
    val lastUpdateCheckMs: Flow<Long> = store.data.map { it[KEY_LAST_UPDATE_CHECK_MS] ?: 0L }
    val autoUpdateEnabled: Flow<Boolean> = store.data.map { it[KEY_AUTO_UPDATE_ENABLED] ?: true }

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

    enum class ThemeMode { System, Dark, Light }

    companion object {
        const val DEFAULT_DAILY_GOAL_ML = 2000

        private val KEY_DAILY_GOAL_ML: Preferences.Key<Int> = intPreferencesKey("daily_goal_ml")
        private val KEY_LAST_SYNC_MS: Preferences.Key<Long> = longPreferencesKey("last_sync_ms")
        private val KEY_THEME_MODE: Preferences.Key<String> = stringPreferencesKey("theme_mode")
        private val KEY_SHOW_STREAK: Preferences.Key<Boolean> = booleanPreferencesKey("show_streak")
        private val KEY_LAST_UPDATE_CHECK_MS: Preferences.Key<Long> = longPreferencesKey("last_update_check_ms")
        private val KEY_AUTO_UPDATE_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("auto_update_enabled")

        @Volatile
        private var instance: AppPreferencesRepository? = null

        fun get(context: Context): AppPreferencesRepository = instance ?: synchronized(this) {
            instance ?: AppPreferencesRepository(context).also { instance = it }
        }
    }
}
