package com.hydra.app.ui

import com.hydra.app.sync.AppForeground
import com.hydra.app.sync.BackgroundSync
import com.hydra.app.ble.DoNotDisturbState
import com.hydra.app.ble.BottleClock
import com.hydra.app.ble.BottleMath
import androidx.compose.runtime.key
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.hydra.app.ble.BottleConfig
import com.hydra.app.ble.BottleConnection
import com.hydra.app.ble.BottleEvent
import com.hydra.app.ble.ConnectionState
import com.hydra.app.ble.SyncState
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.FilterRepository
import com.hydra.app.data.HydraDatabase
import com.hydra.app.filter.FilterReminder
import com.hydra.app.data.SavedBottlesRepository
import com.hydra.app.data.SipRepository
import com.hydra.app.health.HealthConnectController
import com.hydra.app.ui.components.AuroraGlow
import com.hydra.app.ui.components.GlassNavItem
import com.hydra.app.ui.components.GlassNavigationBar
import com.hydra.app.ui.components.GradientBackground
import com.hydra.app.ui.components.HydraIconName
import com.hydra.app.ui.components.UpdateDialog
import com.hydra.app.ui.screens.BottleScreen
import com.hydra.app.ui.screens.HistoryScreen
import com.hydra.app.ui.screens.HydrationScreen
import com.hydra.app.ui.screens.SettingsScreen
import com.hydra.app.update.UpdateController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

private const val TAB_HYDRATION = 0
private const val TAB_BOTTLE = 1
private const val TAB_HISTORY = 2
private const val TAB_SETTINGS = 3

/** How long after leaving the app the bottle connection is kept, to ride out quick app switches. */
private const val BACKGROUND_DISCONNECT_MS = 30_000L

@Composable
fun MainScreen() {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_HYDRATION) }

    val context = LocalContext.current
    val sipRepo = remember(context) { SipRepository(HydraDatabase.get(context).sipDao()) }
    val savedRepo = remember(context) { SavedBottlesRepository(HydraDatabase.get(context).savedBottleDao()) }
    val prefs = remember(context) { AppPreferencesRepository.get(context) }
    val filterRepo = remember(context) { FilterRepository(HydraDatabase.get(context)) }
    val connection = remember(context) { BottleConnection(context, sipRepo) }
    val updateController = remember(context) { UpdateController.get(context) }
    val healthConnect = remember(context) { HealthConnectController.get(context) }

    val savedBottles by savedRepo.observeAll().collectAsState(initial = emptyList())
    val firstSavedName = savedBottles.firstOrNull()?.name
    val connState by connection.state.collectAsState()
    val updateState by updateController.state.collectAsState()

    // The screen holds the bottle connection only while the app is in front. Shortly after it
    // goes to the background the link is dropped, so the bottle isn't kept connected (and the
    // phone isn't left retrying scans) for as long as Android keeps the app alive. From then
    // on the hourly background sync owns the bottle.
    val inForeground by AppForeground.visible.collectAsState()
    LaunchedEffect(connection, inForeground) {
        if (!inForeground) {
            delay(BACKGROUND_DISCONNECT_MS)
            connection.stop()
        }
    }
    val backgroundSyncEnabled by prefs.backgroundSyncEnabled.collectAsState(initial = true)
    LaunchedEffect(backgroundSyncEnabled) { BackgroundSync.apply(context, backgroundSyncEnabled) }

    LaunchedEffect(firstSavedName, connState, inForeground) {
        val name = firstSavedName ?: return@LaunchedEffect
        if (!inForeground) return@LaunchedEffect
        when (connState) {
            ConnectionState.Disconnected -> {
                delay(500L)
                connection.findAndConnect(name)
            }
            ConnectionState.Error -> {
                delay(15_000L)
                connection.findAndConnect(name)
            }
            else -> Unit
        }
    }

    LaunchedEffect(connection) {
        connection.events.collect { ev ->
            if (ev is BottleEvent.SyncFinished) {
                prefs.setLastSync(ev.timestamp)
            }
        }
    }

    LaunchedEffect(healthConnect) { healthConnect.refreshAvailabilityAndPermission() }

    // Push new sips into Health Connect whenever the local DB changes — and re-run after the
    // user toggles HC on (status emits) so the existing history backfills without waiting
    // for the next bottle sync. Controller short-circuits internally when disabled / not
    // permitted, so the gate logic doesn't have to live here too.
    LaunchedEffect(connection, healthConnect) {
        combine(connection.sipLog, healthConnect.status) { entries, _ -> entries }
            .collect { entries ->
                healthConnect.onSipsChanged(entries, BottleConfig.BOTTLE_SIZE_ML)
            }
    }

    // The user's wake/bed times decide when a day rolls over. Set before any tab draws, and
    // rebuild the tabs when it changes so every "today" and daily total is re-bucketed.
    val wakeSec by prefs.wakeSec.collectAsState(initial = AppPreferencesRepository.DEFAULT_WAKE_SEC)
    val bedSec by prefs.bedSec.collectAsState(initial = AppPreferencesRepository.DEFAULT_BED_SEC)
    val dayResetSec = BottleMath.DaySchedule(wakeSec, bedSec).resetSec
    BottleMath.dayResetSec = dayResetSec

    // Power saving: like the official app, switch it off whenever the cap reports it on.
    // The state is asked for once the bottle is connected and its sync has finished (the two
    // would otherwise compete for the BLE channel); at most one wake is sent per connection.
    val syncing = connection.syncState.collectAsState().value is SyncState.Syncing
    LaunchedEffect(connection, connState, syncing) {
        if (connState != ConnectionState.Ready || syncing) return@LaunchedEffect
        delay(800L)
        connection.requestUiState()
        delay(500L)
        connection.requestCleanLog()
        delay(500L)
        connection.requestQuietHours()
        delay(500L)
        connection.refreshBatteryAndInfo()
    }
    LaunchedEffect(connection, connState) {
        if (connState != ConnectionState.Ready) return@LaunchedEffect
        connection.uiState.first { it?.isPowerSaving == true }
        connection.wake()
        delay(600L)
        connection.requestUiState()
    }

    // Quiet hours follow the sleep hours from Settings → Your day. Whenever the bottle's
    // do-not-disturb window is on but doesn't match bedtime → wake-up (the schedule changed,
    // or the clocks moved for daylight saving — the bottle stores UTC), write the right times.
    // A write is followed by one read-back; an unchanged answer does not trigger another write.
    val bottleQuietHours by connection.quietHours.collectAsState()
    LaunchedEffect(connection, bottleQuietHours, wakeSec, bedSec, connState, syncing) {
        val current = bottleQuietHours ?: return@LaunchedEffect
        if (connState != ConnectionState.Ready || syncing) return@LaunchedEffect
        if (current.stateRaw == DoNotDisturbState.OFF.code) return@LaunchedEffect
        val offsetSec = java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds
        val start = BottleClock.localToBottleSec(bedSec, offsetSec)
        val stop = BottleClock.localToBottleSec(wakeSec, offsetSec)
        if (current.startTimeInSec == start && current.stopTimeInSec == stop) return@LaunchedEffect
        delay(1500L)  // let the rest of the post-sync requests finish first
        connection.setQuietHours(current.copy(startTimeInSec = start, stopTimeInSec = stop))
        delay(500L)
        connection.requestQuietHours()
    }

    // Filter replacement reminder: re-evaluate whenever the filter, the sip history, or the
    // reminder switch changes, and keep the age-milestone alarms armed for the active filter.
    LaunchedEffect(connection, filterRepo) {
        combine(filterRepo.observeActive(), connection.sipLog, prefs.filterReminderEnabled) { _, _, _ -> }
            .collectLatest {
                FilterReminder.schedule(context)
                FilterReminder.check(context)
            }
    }

    DisposableEffect(connection) {
        onDispose { connection.dispose() }
    }

    val navItems = remember {
        listOf(
            GlassNavItem(label = "Today", icon = HydraIconName.Drop),
            GlassNavItem(label = "Bottle", icon = HydraIconName.Bottle),
            GlassNavItem(label = "History", icon = HydraIconName.Chart),
            GlassNavItem(label = "Settings", icon = HydraIconName.Gear),
        )
    }

    GradientBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            AuroraGlow()
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                bottomBar = {
                    GlassNavigationBar(
                        items = navItems,
                        selectedIndex = selectedTab,
                        onSelect = { selectedTab = it },
                    )
                },
            ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    key(dayResetSec) { when (selectedTab) {
                        TAB_HYDRATION -> HydrationScreen(
                            connection = connection,
                            onReconnect = {
                                firstSavedName?.let { connection.findAndConnect(it) }
                            },
                        )
                        TAB_BOTTLE -> BottleScreen(connection = connection)
                        TAB_HISTORY -> HistoryScreen()
                        TAB_SETTINGS -> SettingsScreen(connection = connection)
                    } }
                }
            }
        }
    }

    UpdateDialog(
        state = updateState,
        onUpdate = { updateController.startDownload() },
        onLater = { updateController.dismiss() },
        onOpenSettings = {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        },
        onRetry = { updateController.retry() },
        onInstall = { updateController.installNow() },
    )
}
