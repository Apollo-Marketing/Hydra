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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.hydra.app.ble.BottleEvent
import com.hydra.app.ble.ConnectionState
import com.hydra.app.ble.SyncState
import com.hydra.app.data.AppPreferencesRepository
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

@Composable
fun BottleScreen(connection: BottleConnection, modifier: Modifier = Modifier) {
    val colors = LocalHydraColors.current
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferencesRepository.get(context) }
    val sipRepo = remember(context) { SipRepository(HydraDatabase.get(context).sipDao()) }

    val sips by sipRepo.observeAll().collectAsState(initial = emptyList())
    val lastSyncMs by prefs.lastSyncMs.collectAsState(initial = 0L)
    val connState by connection.state.collectAsState()
    val syncState by connection.syncState.collectAsState()

    var battery by remember { mutableStateOf<Int?>(null) }
    var deviceInfo by remember { mutableStateOf(DeviceInfo()) }

    LaunchedEffect(connection) {
        connection.events.collect { ev ->
            if (ev is BottleEvent.StandardRead) {
                when (ev.charName) {
                    "Battery" -> battery = ev.value.removeSuffix("%").toIntOrNull()
                    "Manufacturer" -> deviceInfo = deviceInfo.copy(manufacturer = ev.value)
                    "Model" -> deviceInfo = deviceInfo.copy(model = ev.value)
                    "Serial" -> deviceInfo = deviceInfo.copy(serial = ev.value)
                    "FW Rev" -> deviceInfo = deviceInfo.copy(firmware = ev.value)
                    "HW Rev" -> deviceInfo = deviceInfo.copy(hardware = ev.value)
                }
            }
        }
    }

    LaunchedEffect(connection, connState) {
        if (connState == ConnectionState.Ready) {
            connection.readBatteryLevel()
            connection.readDeviceInfo()
        }
    }

    val bottleSize = BottleConfig.BOTTLE_SIZE_ML
    val today = BottleMath.currentLogicalDate()
    val volume = remember(sips) { BottleMath.latestVolumeMl(sips, bottleSize) }
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
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(
                label = "Sync",
                sub = if (lastSyncMs == 0L) "—" else syncedAgo,
                primary = true,
                enabled = connState == ConnectionState.Ready && syncState !is SyncState.Syncing,
                icon = HydraIconName.Sync,
                onClick = { connection.syncSipLog() },
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                label = "Clean",
                sub = "Coming soon",
                icon = HydraIconName.Drop,
                enabled = false,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                label = "Wake",
                sub = "Coming soon",
                icon = HydraIconName.Plus,
                enabled = false,
                onClick = {},
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
}

private data class DeviceInfo(
    val manufacturer: String = "",
    val model: String = "",
    val serial: String = "",
    val firmware: String = "",
    val hardware: String = "",
)

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
        HydraIcon(
            name = icon,
            size = 20.dp,
            color = if (primary) InkOnAccent else if (enabled) colors.ink else colors.inkDim,
        )
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
