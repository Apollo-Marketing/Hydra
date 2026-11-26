package com.hydra.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydra.app.ble.BottleMath
import com.hydra.app.ble.BottleConfig
import com.hydra.app.ble.BottleConnection
import com.hydra.app.ble.ConnectionState
import com.hydra.app.ble.SyncState
import com.hydra.app.data.ActiveFilter
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.FilterRepository
import com.hydra.app.filter.FilterHealth
import com.hydra.app.filter.FilterType
import com.hydra.app.data.HydraDatabase
import com.hydra.app.data.SipRepository
import com.hydra.app.ui.components.GlassCard
import com.hydra.app.ui.components.HydraIcon
import com.hydra.app.ui.components.HydraIconName
import com.hydra.app.ui.components.InsetCard
import com.hydra.app.ui.components.LiquidBottle
import com.hydra.app.ui.components.rememberRelativeTime
import com.hydra.app.ui.theme.Cyan
import com.hydra.app.ui.theme.InkOnAccent
import com.hydra.app.ui.theme.LocalHydraColors
import com.hydra.app.ui.theme.Violet
import com.hydra.app.ui.theme.Warn
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** How long, and how often, to keep asking the cap for its state after a Clean tap. */
private const val STATE_WATCH_MS = 120_000L
private const val STATE_POLL_MS = 3_000L
private const val MEASURE_TIMEOUT_MS = 8_000L

@Composable
fun BottleScreen(connection: BottleConnection, modifier: Modifier = Modifier) {
    // The filter screen is a full-tab detail view layered over the Bottle tab.
    var showFilter by rememberSaveable { mutableStateOf(false) }
    if (showFilter) {
        FilterScreen(onBack = { showFilter = false }, modifier = modifier)
        return
    }

    val colors = LocalHydraColors.current
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferencesRepository.get(context) }
    val sipRepo = remember(context) { SipRepository(HydraDatabase.get(context).sipDao()) }
    val filterRepo = remember(context) { FilterRepository(HydraDatabase.get(context)) }

    val sips by sipRepo.observeAll().collectAsState(initial = emptyList())
    val activeFilter by remember(filterRepo) { filterRepo.observeActive() }.collectAsState(initial = null)
    val filterHealth = rememberFilterHealth(activeFilter)
    val lastSyncMs by prefs.lastSyncMs.collectAsState(initial = 0L)
    val connState by connection.state.collectAsState()
    val syncState by connection.syncState.collectAsState()

    // Battery and device details are read by the connection (one at a time, after each sync).
    // While disconnected, fall back to the last level that was read, and say how old it is.
    val liveBattery by connection.battery.collectAsState()
    val savedBattery by prefs.lastBatteryPercent.collectAsState(initial = null)
    val batteryAtMs by prefs.lastBatteryAtMs.collectAsState(initial = 0L)
    val battery = liveBattery ?: savedBattery
    val batteryAge = rememberRelativeTime(batteryAtMs)
    val info by connection.deviceInfo.collectAsState()
    val deviceInfo = DeviceInfo(
        manufacturer = info["Manufacturer"].orEmpty(),
        model = info["Model"].orEmpty(),
        serial = info["Serial"].orEmpty(),
        firmware = info["Firmware rev"].orEmpty(),
        hardware = info["Hardware rev"].orEmpty(),
    )

    // What the cap says it is doing (cleaning, power saving…). Asked for once the bottle is
    // ready and idle, then polled for a while after a Clean tap so the buttons follow
    // along. Never polled during a sync — the two would compete for the same BLE channel.
    val bottleState by connection.uiState.collectAsState()
    val ready = connState == ConnectionState.Ready
    val cleaning = ready && bottleState?.isCleaning == true
    var watchUntilMs by remember { mutableLongStateOf(0L) }
    var confirmClean by rememberSaveable { mutableStateOf(false) }
    val syncing = syncState is SyncState.Syncing
    LaunchedEffect(connection, ready, syncing, watchUntilMs) {
        if (!ready || syncing) return@LaunchedEffect
        delay(600L)
        connection.requestUiState()
        if (watchUntilMs > 0L) {
            // A clean was just started or stopped here — pick up its log entry.
            delay(400L)
            connection.requestCleanLog()
        }
        while (System.currentTimeMillis() < watchUntilMs) {
            delay(STATE_POLL_MS)
            connection.requestUiState()
        }
    }

    val bottleSize = BottleConfig.BOTTLE_SIZE_ML
    val today = BottleMath.currentLogicalDate()
    val liveLevel by connection.liveLevel.collectAsState()
    val volume = remember(sips, liveLevel) {
        BottleMath.currentVolumeMl(sips, bottleSize, liveLevel?.distanceMm, liveLevel?.atMs)
    }
    val levelCheckedAgo = rememberRelativeTime(epochMs = liveLevel?.atMs ?: 0L, neverLabel = "Water level")
    // "Measuring" runs from the tap until a reading newer than the tap arrives, or gives up
    // after a few seconds so the button can't get stuck.
    var measureSentAtMs by remember { mutableLongStateOf(0L) }
    var measureFailed by remember { mutableStateOf(false) }
    val measuring = measureSentAtMs > 0L && (liveLevel?.atMs ?: 0L) < measureSentAtMs
    LaunchedEffect(measureSentAtMs) {
        if (measureSentAtMs == 0L) return@LaunchedEffect
        delay(MEASURE_TIMEOUT_MS)
        if ((connection.liveLevel.value?.atMs ?: 0L) < measureSentAtMs) measureFailed = true
        measureSentAtMs = 0L
    }
    val lastCleanedSec by connection.lastCleanedSec.collectAsState()
    val cleanedAgo = rememberRelativeTime(epochMs = (lastCleanedSec ?: 0L) * 1000L, neverLabel = "—")
    val refills = remember(sips) { BottleMath.refillsOnDate(sips, bottleSize, today) }
    val ml = volume?.roundToInt() ?: 0
    val frac = volume?.let { (it / bottleSize).toFloat().coerceIn(0f, 1f) } ?: 0f
    val syncedAgo = rememberRelativeTime(epochMs = lastSyncMs, neverLabel = "never")

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("My bottle", color = colors.ink, style = MaterialTheme.typography.headlineMedium)
            Text(
                if (syncState is SyncState.Syncing) "Syncing…" else "Synced $syncedAgo",
                color = colors.inkSoft,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidBottle(fillFraction = frac, width = 94.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    deviceInfo.model.takeIf { it.isNotBlank() } ?: "PureVis 2",
                    color = colors.inkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (volume != null) ml.toString() else "—",
                        color = colors.ink,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 56.sp,
                    )
                    Text(
                        " ml left",
                        color = colors.inkSoft,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                if (volume != null && volume < 100.0) {
                    RefillChip()
                }
            }
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            contentPadding = 14.dp,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Status", color = colors.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                StatusGrid(
                    battery = battery,
                    fillPercent = (frac * 100).roundToInt(),
                    refillCount = refills.count,
                    refillTotalMl = refills.totalMl.roundToInt(),
                )
                if (battery != null && batteryAtMs > 0L) {
                    Text(
                        "Battery measured $batteryAge",
                        color = colors.inkSoft,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        FilterSummaryCard(
            active = activeFilter,
            health = filterHealth,
            onClick = { showFilter = true },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(
                label = "Sync",
                sub = if (lastSyncMs == 0L) "—" else syncedAgo,
                primary = true,
                enabled = connState == ConnectionState.Ready && syncState !is SyncState.Syncing,
                busy = syncState is SyncState.Syncing,
                icon = HydraIconName.Sync,
                onClick = { connection.syncSipLog() },
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                label = if (cleaning) "Stop" else "Clean",
                sub = if (cleaning) "Cleaning…" else "UV cycle",
                icon = HydraIconName.Drop,
                enabled = ready,
                onClick = {
                    if (cleaning) {
                        connection.stopCleaning()
                        watchUntilMs = System.currentTimeMillis() + STATE_WATCH_MS
                    } else {
                        confirmClean = true
                    }
                },
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                label = "Measure",
                sub = when {
                    measuring -> "Measuring…"
                    measureFailed -> "No reply"
                    else -> levelCheckedAgo
                },
                icon = HydraIconName.Bottle,
                enabled = ready && !syncing && !measuring,
                busy = measuring,
                onClick = {
                    measureFailed = false
                    measureSentAtMs = System.currentTimeMillis()
                    if (connection.requestLevel() == null) {
                        measureSentAtMs = 0L
                        measureFailed = true
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            contentPadding = 14.dp,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "About this bottle",
                    color = colors.ink,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                AboutRow("Last cleaned", cleanedAgo)
                AboutRow("Model", deviceInfo.model.ifBlank { "—" })
                AboutRow("Serial", deviceInfo.serial.ifBlank { "—" })
                AboutRow("Firmware", deviceInfo.firmware.ifBlank { "—" })
                AboutRow(
                    "Manufacturer",
                    deviceInfo.manufacturer.ifBlank { "—" },
                    last = true,
                )
            }
        }

        Spacer(Modifier.height(80.dp))
    }

    if (confirmClean) {
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            containerColor = colors.surfaceSolid,
            titleContentColor = colors.ink,
            textContentColor = colors.inkSoft,
            title = { Text("Start a UV clean?") },
            text = { Text("Runs the bottle's standard UV cycle, the same as pressing the cap button. Keep the cap screwed on until it finishes.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClean = false
                    connection.startCleaning()
                    watchUntilMs = System.currentTimeMillis() + STATE_WATCH_MS
                }) { Text("Start", color = Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClean = false }) { Text("Cancel", color = colors.inkSoft) }
            },
        )
    }
}

private data class DeviceInfo(
    val manufacturer: String = "",
    val model: String = "",
    val serial: String = "",
    val firmware: String = "",
    val hardware: String = "",
)

@Composable
private fun FilterSummaryCard(active: ActiveFilter?, health: FilterHealth?, onClick: () -> Unit) {
    val colors = LocalHydraColors.current
    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        contentPadding = 14.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Filter", color = colors.ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                if (active == null || health == null) {
                    Text("Not tracked. Tap to add your filter.", color = colors.inkSoft, style = MaterialTheme.typography.bodySmall)
                } else {
                    val type = FilterType.fromKey(active.filter.type).label
                    val state = if (active.isPaused) "Temporarily removed" else health.status.shortLabel()
                    Text(
                        "$type · $state",
                        color = if (active.isPaused) colors.inkSoft else health.status.tint(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            if (active != null && health != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${health.percent}", color = colors.ink, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                    Text(
                        " %",
                        color = colors.inkSoft,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            HydraIcon(name = HydraIconName.Arrow, size = 14.dp, color = colors.inkDim)
        }
    }
}

@Composable
private fun RefillChip() {
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Warn.copy(alpha = 0.14f))
            .border(BorderStroke(1.dp, Warn.copy(alpha = 0.4f)), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Warn))
            Text(
                "Time for a refill",
                color = Warn,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun StatusGrid(
    battery: Int?,
    fillPercent: Int,
    refillCount: Int,
    refillTotalMl: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusCell(
            label = "Battery",
            value = battery?.toString() ?: "—",
            unit = if (battery != null) "%" else "",
            progress = (battery ?: 0) / 100f,
            tint = if ((battery ?: 100) < 20) Warn else Cyan,
            modifier = Modifier.weight(1f),
        )
        StatusCell(
            label = "Bottle level",
            value = fillPercent.toString(),
            unit = "%",
            progress = fillPercent / 100f,
            tint = Violet,
            modifier = Modifier.weight(1f),
        )
        StatusCell(
            label = "Refills today",
            value = refillCount.toString(),
            unit = if (refillTotalMl > 0) "+${refillTotalMl} ml" else "",
            progress = if (refillCount > 0) 1f else 0f,
            tint = Violet,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatusCell(
    label: String,
    value: String,
    unit: String,
    progress: Float,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHydraColors.current
    InsetCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = colors.inkSoft, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    color = colors.ink,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                )
                if (unit.isNotEmpty()) {
                    Text(
                        " $unit",
                        color = colors.inkSoft,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(colors.hair),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(tint),
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    sub: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    /** Shows a spinner in place of the icon while the action is in progress. */
    busy: Boolean = false,
    icon: HydraIconName,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHydraColors.current
    val shape = RoundedCornerShape(16.dp)
    val styled = if (primary) {
        Modifier.background(Brush.linearGradient(listOf(Cyan, Violet)))
    } else {
        Modifier
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.hair), shape)
    }
    Column(
        modifier = modifier
            .clip(shape)
            .then(styled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = if (primary) InkOnAccent else Cyan,
                strokeWidth = 2.dp,
            )
        } else {
            HydraIcon(
                name = icon,
                size = 20.dp,
                color = if (primary) InkOnAccent else if (enabled) colors.ink else colors.inkDim,
            )
        }
        Text(
            label,
            color = if (primary) InkOnAccent else if (enabled) colors.ink else colors.inkDim,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            sub,
            color = if (primary) InkOnAccent.copy(alpha = 0.75f) else colors.inkSoft,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String, last: Boolean = false) {
    val colors = LocalHydraColors.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = colors.inkSoft, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                color = colors.ink,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        if (!last) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.hair),
            )
        }
    }
}
