package com.hydra.app.ui

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
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.HydraDatabase
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
import kotlinx.coroutines.flow.combine

private const val TAB_HYDRATION = 0
private const val TAB_BOTTLE = 1
private const val TAB_HISTORY = 2
private const val TAB_SETTINGS = 3

@Composable
fun MainScreen() {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_HYDRATION) }

    val context = LocalContext.current
    val sipRepo = remember(context) { SipRepository(HydraDatabase.get(context).sipDao()) }
    val savedRepo = remember(context) { SavedBottlesRepository(HydraDatabase.get(context).savedBottleDao()) }
    val prefs = remember(context) { AppPreferencesRepository.get(context) }
    val connection = remember(context) { BottleConnection(context, sipRepo) }
    val updateController = remember(context) { UpdateController.get(context) }
    val healthConnect = remember(context) { HealthConnectController.get(context) }

    val savedBottles by savedRepo.observeAll().collectAsState(initial = emptyList())
    val firstSavedName = savedBottles.firstOrNull()?.name
    val connState by connection.state.collectAsState()
    val updateState by updateController.state.collectAsState()

    LaunchedEffect(firstSavedName, connState) {
        val name = firstSavedName ?: return@LaunchedEffect
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
                    when (selectedTab) {
                        TAB_HYDRATION -> HydrationScreen(
                            connection = connection,
                            onReconnect = {
                                firstSavedName?.let { connection.findAndConnect(it) }
                            },
                        )
                        TAB_BOTTLE -> BottleScreen(connection = connection)
                        TAB_HISTORY -> HistoryScreen()
                        TAB_SETTINGS -> SettingsScreen()
                    }
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
